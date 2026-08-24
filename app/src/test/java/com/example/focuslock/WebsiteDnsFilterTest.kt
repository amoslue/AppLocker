package com.example.focuslock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteDnsFilterTest {
    @Test
    fun normalizesUserInputAndMatchesSubdomains() {
        assertEquals("instagram.com", WebsiteBlockStore.normalize("instagram.com***"))
        assertEquals("instagram.com", WebsiteBlockStore.normalize("https://www.instagram.com/reels"))
        assertTrue(WebsiteBlockStore.isBlocked("www.instagram.com", setOf("instagram.com")))
        assertTrue(WebsiteBlockStore.isBlocked("api.cdn.instagram.com", setOf("instagram.com")))
        assertFalse(WebsiteBlockStore.isBlocked("notinstagram.com", setOf("instagram.com")))
    }

    @Test
    fun parsesQueryAndBuildsNxdomainResponse() {
        val request = dnsRequest("www.instagram.com")
        val query = DnsPacket.parse(request, request.size)!!

        assertEquals("www.instagram.com", query.host)
        assertEquals(53_000, query.sourcePort)

        val dnsResponse = DnsPacket.errorResponse(query.dnsPayload, 3)
        assertEquals(3, dnsResponse[3].toInt() and 0x0f)
        assertEquals(0, dnsResponse[6].toInt())
        assertEquals(0, dnsResponse[7].toInt())

        val response = DnsPacket.wrapResponse(query, dnsResponse)
        assertArrayEquals(byteArrayOf(10, 111, 0, 2), response.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 0, 1), response.copyOfRange(16, 20))
        assertEquals(53, readUnsignedShort(response, 20))
        assertEquals(53_000, readUnsignedShort(response, 22))
    }

    private fun dnsRequest(host: String): ByteArray {
        val question = buildList<Byte> {
            host.split('.').forEach { label ->
                add(label.length.toByte())
                label.toByteArray(Charsets.US_ASCII).forEach(::add)
            }
            add(0)
            add(0)
            add(1)
            add(0)
            add(1)
        }.toByteArray()
        val dns = ByteArray(12 + question.size)
        dns[0] = 0x12
        dns[1] = 0x34
        dns[2] = 0x01
        dns[5] = 0x01
        question.copyInto(dns, 12)

        val packet = ByteArray(28 + dns.size)
        packet[0] = 0x45
        packet[9] = 17
        byteArrayOf(10, 111, 0, 1).copyInto(packet, 12)
        byteArrayOf(10, 111, 0, 2).copyInto(packet, 16)
        writeUnsignedShort(packet, 20, 53_000)
        writeUnsignedShort(packet, 22, 53)
        writeUnsignedShort(packet, 24, 8 + dns.size)
        dns.copyInto(packet, 28)
        return packet
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    private fun writeUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
