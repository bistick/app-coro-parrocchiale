package it.parrocchia.coro.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as PlatformPlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.parrocchia.coro.MainActivity
import it.parrocchia.coro.R

/**
 * Foreground Service `mediaPlayback` con [MediaSession] nativa Android e notifica `MediaStyle`:
 * - Mantiene attiva la riproduzione in background/a schermo spento durante la messa.
 * - Espone l'app come lettore multimediale di sistema con widget sulla lock screen,
 *   Quick Settings, metadati Bluetooth e comandi rapidi Sfuma / Stop.
 */
class PlaybackForegroundService : Service() {

    private var mediaSession: MediaSession? = null
    private var isForegroundStarted: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureMediaSession()
    }

    override fun onDestroy() {
        releaseMediaSession()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                deactivateMediaSession()
                isForegroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FADE_OUT -> {
                onFadeOutCallback?.invoke()
                return START_NOT_STICKY
            }

            ACTION_STOP_PLAYBACK -> {
                onStopCallback?.invoke()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Riproduzione base" }
                val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L).coerceAtLeast(0L)
                val isFading = intent.getBooleanExtra(EXTRA_IS_FADING, false)
                publishMediaState(title, phase, positionMs, durationMs, isFading, forceStartForeground = false)
            }

            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Riproduzione base" }
                val phase = intent?.getStringExtra(EXTRA_PHASE).orEmpty()
                val positionMs = intent?.getLongExtra(EXTRA_POSITION_MS, 0L)?.coerceAtLeast(0L) ?: 0L
                val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, 0L)?.coerceAtLeast(0L) ?: 0L
                val isFading = intent?.getBooleanExtra(EXTRA_IS_FADING, false) ?: false
                publishMediaState(title, phase, positionMs, durationMs, isFading, forceStartForeground = true)
            }
        }
        return START_NOT_STICKY
    }

    private fun publishMediaState(
        title: String,
        phase: String,
        positionMs: Long,
        durationMs: Long,
        isFading: Boolean,
        forceStartForeground: Boolean
    ) {
        ensureChannel()
        val session = ensureMediaSession()
        val subtitle = when {
            isFading && phase.isNotBlank() -> "$phase · Sfumatura..."
            isFading -> "Sfumatura in corso..."
            phase.isNotBlank() -> phase
            else -> "Coro"
        }

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Coro")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build()
        session.setMetadata(metadata)

        val playbackState = PlatformPlaybackState.Builder()
            .setActions(
                PlatformPlaybackState.ACTION_PAUSE or
                    PlatformPlaybackState.ACTION_STOP or
                    PlatformPlaybackState.ACTION_PLAY_PAUSE
            )
            .setState(PlatformPlaybackState.STATE_PLAYING, positionMs, 1.0f)
            .build()
        session.setPlaybackState(playbackState)
        if (!session.isActive) {
            session.isActive = true
        }

        val notification = buildNotification(title, subtitle, session.sessionToken)
        if (forceStartForeground || !isForegroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureMediaSession(): MediaSession {
        mediaSession?.let { return it }
        val created = MediaSession(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() {
                    onFadeOutCallback?.invoke()
                }

                override fun onStop() {
                    onStopCallback?.invoke()
                }
            })
        }
        mediaSession = created
        return created
    }

    private fun deactivateMediaSession() {
        mediaSession?.let { session ->
            runCatching {
                session.setPlaybackState(
                    PlatformPlaybackState.Builder()
                        .setState(PlatformPlaybackState.STATE_STOPPED, 0L, 0f)
                        .build()
                )
                session.isActive = false
            }
        }
    }

    private fun releaseMediaSession() {
        deactivateMediaSession()
        runCatching { mediaSession?.release() }
        mediaSession = null
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_desc)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        subtitle: String,
        sessionToken: MediaSession.Token
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fadeIntent = Intent(this, PlaybackForegroundService::class.java).apply {
            action = ACTION_FADE_OUT
        }
        val fadePendingIntent = PendingIntent.getService(
            this,
            1,
            fadeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PlaybackForegroundService::class.java).apply {
            action = ACTION_STOP_PLAYBACK
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fadeAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            "Sfuma",
            fadePendingIntent
        ).build()

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stopPendingIntent
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSubText("Coro")
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(contentPendingIntent)
            .addAction(fadeAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "coro_playback_channel"
        private const val MEDIA_SESSION_TAG = "CoroPlaybackSession"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "it.parrocchia.coro.action.START_PLAYBACK_SERVICE"
        const val ACTION_UPDATE = "it.parrocchia.coro.action.UPDATE_PLAYBACK_SERVICE"
        const val ACTION_STOP = "it.parrocchia.coro.action.STOP_PLAYBACK_SERVICE"
        const val ACTION_FADE_OUT = "it.parrocchia.coro.action.FADE_OUT_FROM_NOTIFICATION"
        const val ACTION_STOP_PLAYBACK = "it.parrocchia.coro.action.STOP_FROM_NOTIFICATION"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PHASE = "extra_phase"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_IS_FADING = "extra_is_fading"

        @Volatile
        private var onFadeOutCallback: (() -> Unit)? = null

        @Volatile
        private var onStopCallback: (() -> Unit)? = null

        fun registerTransportCallbacks(
            onFadeOut: (() -> Unit)?,
            onStop: (() -> Unit)?
        ) {
            onFadeOutCallback = onFadeOut
            onStopCallback = onStop
        }

        fun start(
            context: Context,
            title: String,
            phase: String,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            isFading: Boolean = false
        ) {
            val intent = Intent(context, PlaybackForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PHASE, phase)
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_IS_FADING, isFading)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun update(
            context: Context,
            title: String,
            phase: String,
            positionMs: Long,
            durationMs: Long,
            isFading: Boolean
        ) {
            val intent = Intent(context, PlaybackForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PHASE, phase)
                putExtra(EXTRA_POSITION_MS, positionMs)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_IS_FADING, isFading)
            }
            runCatching {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }
        }
    }
}
