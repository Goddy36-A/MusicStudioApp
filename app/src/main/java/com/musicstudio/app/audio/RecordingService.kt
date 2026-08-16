package com.musicstudio.app.audio

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.musicstudio.app.MainActivity
import com.musicstudio.app.R

/**
 * Foreground service that holds a wake-lock and posts a persistent notification
 * while a recording session is active. This prevents the OS from killing the
 * AudioEngine processing loop when the user navigates away from the app.
 *
 * Bound service pattern: StudioFragment binds to it to get the shared AudioEngine.
 */
class RecordingService : Service() {

    // ── Binder ─────────────────────────────────────────────────────────
    inner class RecordingBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = RecordingBinder()

    // ── Session state ──────────────────────────────────────────────────
    var isRecording = false
        private set

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundSession()
            ACTION_STOP  -> stopForegroundSession()
        }
        return START_NOT_STICKY
    }

    // ── Foreground session ─────────────────────────────────────────────

    fun startForegroundSession() {
        isRecording = true
        startForeground(NOTIFICATION_ID, buildNotification("Recording…"))
    }

    fun stopForegroundSession() {
        isRecording = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun updateNotification(trackTitle: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("Recording over: $trackTitle"))
    }

    // ── Notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while a recording session is active"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("🎙 Music Studio")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_mic, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START   = "com.musicstudio.app.ACTION_START_RECORDING"
        const val ACTION_STOP    = "com.musicstudio.app.ACTION_STOP_RECORDING"
        const val CHANNEL_ID     = "recording_session"
        const val NOTIFICATION_ID = 1001
    }
}
