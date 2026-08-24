package com.example.focuslock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class WebsiteBlockVpnService : VpnService() {
    companion object {
        const val ACTION_REFRESH = "com.example.focuslock.REFRESH_WEBSITE_VPN"
        const val ACTION_STOP = "com.example.focuslock.STOP_WEBSITE_VPN"

        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "WEBSITE_BLOCK_CHANNEL"
        private const val NOTIFICATION_ID = 2
        private const val VPN_ADDRESS = "10.111.0.1"
        private const val VPN_DNS_ADDRESS = "10.111.0.2"
        private const val UPSTREAM_DNS = "1.1.1.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (WebsiteBlockStore.domains(this).isEmpty()) {
            stopVpn()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Website blocking active")
            .setContentText("Filtering blocked domains and subdomains")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        restartVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeInterface()
        isRunning = false
        super.onDestroy()
    }

    private fun restartVpn() {
        closeInterface()
        val descriptor = Builder()
            .setSession("Focus Lock website filter")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS_ADDRESS)
            .addRoute(VPN_DNS_ADDRESS, 32)
            .setBlocking(true)
            .establish() ?: run {
                stopSelf()
                return
            }

        vpnInterface = descriptor
        isRunning = true
        worker = thread(name = "website-dns-filter") {
            runDnsProxy(descriptor)
        }
    }

    private fun runDnsProxy(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val packet = ByteArray(32_767)

        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.read(packet)
                if (length <= 0) continue
                val query = DnsPacket.parse(packet, length) ?: continue
                val blockedDomains = WebsiteBlockStore.domains(this)
                val dnsResponse = if (WebsiteBlockStore.isBlocked(query.host, blockedDomains)) {
                    DnsPacket.errorResponse(query.dnsPayload, 3)
                } else {
                    forwardQuery(query.dnsPayload) ?: DnsPacket.errorResponse(query.dnsPayload, 2)
                }
                output.write(DnsPacket.wrapResponse(query, dnsResponse))
            }
        } catch (_: Exception) {
            if (vpnInterface === descriptor) stopSelf()
        } finally {
            input.close()
            output.close()
        }
    }

    private fun forwardQuery(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 3_000
                val upstream = InetAddress.getByName(UPSTREAM_DNS)
                socket.send(DatagramPacket(query, query.size, upstream, 53))
                val responseBuffer = ByteArray(4_096)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                response.data.copyOf(response.length)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun stopVpn() {
        closeInterface()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeInterface() {
        worker?.interrupt()
        worker = null
        vpnInterface?.close()
        vpnInterface = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Website blocking",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

internal data class DnsQuery(
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val dnsPayload: ByteArray,
    val host: String,
    val ipIdentification: Int
)

internal object DnsPacket {
    fun parse(packet: ByteArray, length: Int): DnsQuery? {
        if (length < 40 || (packet[0].toInt() ushr 4) != 4) return null
        val ipHeaderLength = (packet[0].toInt() and 0x0f) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 20) return null
        if (packet[9].toInt() and 0xff != 17) return null

        val udpOffset = ipHeaderLength
        val destinationPort = readUnsignedShort(packet, udpOffset + 2)
        if (destinationPort != 53) return null
        val udpLength = readUnsignedShort(packet, udpOffset + 4)
        val dnsOffset = udpOffset + 8
        val dnsLength = (udpLength - 8).coerceAtMost(length - dnsOffset)
        if (dnsLength < 13) return null
        val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val host = readQuestionHost(dnsPayload) ?: return null

        return DnsQuery(
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = readUnsignedShort(packet, udpOffset),
            dnsPayload = dnsPayload,
            host = host,
            ipIdentification = readUnsignedShort(packet, 4)
        )
    }

    fun errorResponse(query: ByteArray, responseCode: Int): ByteArray {
        val response = query.copyOf()
        response[2] = 0x81.toByte()
        response[3] = (0x80 or (responseCode and 0x0f)).toByte()
        for (index in 6..11) response[index] = 0
        return response
    }

    fun wrapResponse(query: DnsQuery, dnsResponse: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponse.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        writeUnsignedShort(packet, 2, totalLength)
        writeUnsignedShort(packet, 4, query.ipIdentification)
        writeUnsignedShort(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        query.destinationAddress.copyInto(packet, 12)
        query.sourceAddress.copyInto(packet, 16)
        writeUnsignedShort(packet, 10, checksum(packet, 0, 20))

        writeUnsignedShort(packet, 20, 53)
        writeUnsignedShort(packet, 22, query.sourcePort)
        writeUnsignedShort(packet, 24, udpLength)
        writeUnsignedShort(packet, 26, 0)
        dnsResponse.copyInto(packet, 28)
        return packet
    }

    private fun readQuestionHost(dns: ByteArray): String? {
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < dns.size) {
            val labelLength = dns[offset].toInt() and 0xff
            offset++
            if (labelLength == 0) break
            if (labelLength > 63 || offset + labelLength > dns.size) return null
            labels += dns.copyOfRange(offset, offset + labelLength).toString(Charsets.US_ASCII)
            offset += labelLength
        }
        return labels.joinToString(".").takeIf { it.isNotBlank() }
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index < offset + length) {
            if (index == offset + 10) {
                index += 2
                continue
            }
            sum += readUnsignedShort(bytes, index)
            while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
            index += 2
        }
        return sum.inv().toInt() and 0xffff
    }
}
