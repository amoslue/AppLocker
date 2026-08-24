package com.example.focuslock

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FocusService : Service() {

    companion object {
        const val EXTRA_LOCKED_APPS = "LOCKED_APPS"
        const val EXTRA_BLOCK_END_TIME = "BLOCK_END_TIME"
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var monitoringJob: Job? = null
    private var lockedApps = setOf<String>()
    private var currentForegroundApp = ""
    private var currentForegroundEventTime = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "FOCUS_CHANNEL")
            .setContentTitle("Focus Mode Active")
            .setContentText("Blocking doomscrolling apps...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)

        val requestedApps = intent?.getStringArrayListExtra(EXTRA_LOCKED_APPS)?.toSet()
        val requestedEndTime = intent?.getLongExtra(EXTRA_BLOCK_END_TIME, 0L) ?: 0L

        if (!requestedApps.isNullOrEmpty() && requestedEndTime > System.currentTimeMillis()) {
            FocusSessionStore.add(
                context = this,
                packageNames = requestedApps,
                startedAt = System.currentTimeMillis(),
                endsAt = requestedEndTime
            )
        }

        val sessions = FocusSessionStore.currentAndUpcoming(this)
        if (sessions.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        lockedApps = FocusSessionStore.active(this).flatMap { it.packageNames }.toSet()

        startMonitoringForegroundApp()

        return START_STICKY
    }

    private fun startMonitoringForegroundApp() {
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            while (isActive) {
                val sessions = FocusSessionStore.currentAndUpcoming(this@FocusService)
                if (sessions.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        removeOverlay()
                        stopSelf()
                    }
                    break
                }
                val now = System.currentTimeMillis()
                val activeSessions = sessions.filter { it.startedAt <= now }
                lockedApps = activeSessions.flatMap { it.packageNames }.toSet()
                if (lockedApps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        removeOverlay()
                    }
                    delay(500)
                    continue
                }
                val currentApp = getForegroundApp(usageStatsManager)
                if (currentApp in lockedApps && Settings.canDrawOverlays(this@FocusService)) {
                    withContext(Dispatchers.Main) {
                        showOverlay()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        removeOverlay()
                    }
                }
                delay(500) // Poll every 500ms
            }
        }
    }

    private fun getForegroundApp(usm: UsageStatsManager): String {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15_000L, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                if (event.timeStamp >= currentForegroundEventTime) {
                    currentForegroundApp = event.packageName
                    currentForegroundEventTime = event.timeStamp
                }
            }
        }
        return currentForegroundApp
    }

    private fun showOverlay() {
        if (overlayView != null) return // Overlay already drawn

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 18))
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)

            val titleView = TextView(context).apply {
                text = "Focus session active"
                textSize = 30f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }

            val messageView = TextView(context).apply {
                text = "This app is blocked until your selected time ends."
                textSize = 18f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 48)
            }

            val homeButton = Button(context).apply {
                text = "Go to Home Screen"
                setOnClickListener {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                }
            }

            addView(titleView)
            addView(messageView)
            addView(homeButton)
        }

        overlayView = layout
        windowManager?.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "FOCUS_CHANNEL",
                "Focus Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}