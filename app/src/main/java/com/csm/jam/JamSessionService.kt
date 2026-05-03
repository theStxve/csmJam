package com.csm.jam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * Foreground service that keeps the jam session alive even when:
 * - The app is in the background (file picker, other apps)
 * - The user swipes the app away from recents
 *
 * The notification uses MediaStyle with Play/Pause/Skip controls.
 */
class JamSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "jam_session_channel"
        const val NOTIFICATION_ID = 1337

        const val ACTION_START = "com.csm.jam.START_SESSION"
        const val ACTION_STOP  = "com.csm.jam.STOP_SESSION"
        const val ACTION_PLAY_PAUSE = "com.csm.jam.PLAY_PAUSE"
        const val ACTION_SKIP_NEXT  = "com.csm.jam.SKIP_NEXT"
        const val ACTION_SKIP_PREV  = "com.csm.jam.SKIP_PREV"
        const val EXTRA_ROLE   = "role"
        const val EXTRA_TRACK  = "track"
        const val EXTRA_PLAYING = "isPlaying"

        fun start(context: Context, role: String, trackName: String? = null, isPlaying: Boolean = false) {
            val intent = Intent(context, JamSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROLE, role)
                if (trackName != null) putExtra(EXTRA_TRACK, trackName)
                putExtra(EXTRA_PLAYING, isPlaying)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, JamSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateTrack(context: Context, role: String, trackName: String?, isPlaying: Boolean = false) {
            start(context, role, trackName, isPlaying)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): JamSessionService = this@JamSessionService
    }

    private val binder = LocalBinder()
    private var currentRole: String = "host"
    private var currentTrack: String? = null
    private var isPlaying: Boolean = false

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val syncClient = MainActivity.syncClient

            when (intent?.action) {
                ACTION_PLAY_PAUSE -> {
                    if (syncClient != null) {
                        if (isPlaying) syncClient.sendPauseAction()
                        else syncClient.sendPlayAction(null)
                    }
                }
                ACTION_SKIP_NEXT -> {
                    syncClient?.sendSkipNext()
                }
                ACTION_SKIP_PREV -> {
                    syncClient?.sendSkipPrev()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_SKIP_NEXT)
            addAction(ACTION_SKIP_PREV)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaControlReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                currentRole = intent.getStringExtra(EXTRA_ROLE) ?: "host"
                if (intent.hasExtra(EXTRA_TRACK)) {
                    currentTrack = intent.getStringExtra(EXTRA_TRACK)
                }
                isPlaying = intent.getBooleanExtra(EXTRA_PLAYING, false)
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(mediaControlReceiver) } catch (_: Exception) {}
    }

    fun updateNotification(role: String, trackName: String?, playing: Boolean = isPlaying) {
        currentRole = role
        currentTrack = trackName
        isPlaying = playing
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pendingBroadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this, action.hashCode(),
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val roleLabel = if (currentRole == "host") "Host" else "Gast"
        val title = currentTrack ?: "Warten auf Song…"
        val subtitle = "CSM Jam · $roleLabel"

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (isPlaying) "Pause" else "Play"

        // Media action buttons
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Zurück", pendingBroadcast(ACTION_SKIP_PREV))
            .addAction(playPauseIcon, playPauseLabel, pendingBroadcast(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Weiter", pendingBroadcast(ACTION_SKIP_NEXT))

        // Attach Media3 MediaStyle
        val session = MainActivity.activeMediaSession
        if (session != null) {
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jam Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält die Jam Session auch im Hintergrund am Laufen"
                setSound(null, null)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
