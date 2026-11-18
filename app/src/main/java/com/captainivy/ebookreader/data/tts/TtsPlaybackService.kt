package com.captainivy.ebookreader.data.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.ui.reader.ReaderActivity

/**
 * 朗读前台服务：负责熄屏/后台保活、媒体会话、通知与 CPU 唤醒锁。
 *
 * 朗读循环仍由 [ReaderActivity] 驱动，这里只承载与系统交互的部分：
 * 前台服务避免进程被回收，媒体会话把锁屏/蓝牙按键转成命令回调给Activity，
 * 唤醒锁保证熄屏后离线合成与协程调度不中断。
 */
class TtsPlaybackService : Service() {

    enum class Command { PLAY, PAUSE, STOP, PREVIOUS, NEXT }

    data class PlaybackState(
        val bookId: Long = 0L,
        val title: String = "",
        val subtitle: String = "",
        val playing: Boolean = false,
        val paused: Boolean = false,
        val loading: Boolean = false
    )

    inner class LocalBinder : Binder() {
        fun service(): TtsPlaybackService = this@TtsPlaybackService
    }

    private val binder = LocalBinder()
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var state = PlaybackState()
    private var foregroundStarted = false

    /** 播放控制命令回调，由绑定的阅读器 Activity 注册 */
    var onCommand: ((Command) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val session = MediaSessionCompat(this, MEDIA_SESSION_TAG)
        session.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = dispatch(Command.PLAY)

            override fun onPause() = dispatch(Command.PAUSE)

            override fun onStop() = dispatch(Command.STOP)

            override fun onSkipToNext() = dispatch(Command.NEXT)

            override fun onSkipToPrevious() = dispatch(Command.PREVIOUS)
        })
        session.isActive = true
        mediaSession = session
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            dispatch(Command.STOP)
            stopSession()
            return START_NOT_STICKY
        }
        ensureForeground()
        when (intent?.action) {
            ACTION_PLAY -> dispatch(Command.PLAY)
            ACTION_PAUSE -> dispatch(Command.PAUSE)
            ACTION_PREVIOUS -> dispatch(Command.PREVIOUS)
            ACTION_NEXT -> dispatch(Command.NEXT)
        }
        return START_NOT_STICKY
    }

    /** 更新通知、媒体会话与唤醒锁；由阅读器在播放状态变化时调用 */
    fun updateState(newState: PlaybackState) {
        state = newState
        // 加载阶段也要持锁，避免熄屏后设备休眠拖慢离线模型准备
        applyWakeLock(!newState.paused)
        updateMediaSession()
        if (foregroundStarted) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) {
            }
        }
    }

    /** 结束朗读会话：移除通知、释放唤醒锁并停止服务 */
    fun stopSession() {
        releaseWakeLock()
        mediaSession?.let { session ->
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(PLAYBACK_ACTIONS)
                    .setState(PlaybackStateCompat.STATE_STOPPED, PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build()
            )
            session.isActive = false
        }
        if (foregroundStarted) {
            foregroundStarted = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        onCommand = null
        releaseWakeLock()
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun ensureForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                }
            )
            foregroundStarted = true
        } catch (_: Exception) {
        }
    }

    private fun dispatch(command: Command) {
        val listener = onCommand
        if (listener != null) {
            listener.invoke(command)
        } else if (command == Command.STOP) {
            stopSession()
        }
    }

    // ------------------------------------------------------------ media session

    private fun updateMediaSession() {
        val session = mediaSession ?: return
        val playbackState = when {
            state.loading -> PlaybackStateCompat.STATE_BUFFERING
            state.playing -> PlaybackStateCompat.STATE_PLAYING
            state.paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(playbackState, PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, state.bookId.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displaySubtitle())
                .build()
        )
        contentIntent()?.let { session.setSessionActivity(it) }
    }

    // ------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val playing = state.playing
        val playPauseAction = if (playing) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseLabel = getString(if (playing) R.string.tts_pause else R.string.tts_resume)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tts)
            .setContentTitle(displayTitle())
            .setContentText(displayStatus())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                R.drawable.ic_rewind,
                getString(R.string.tts_rewind),
                commandIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS)
            )
            .addAction(
                playPauseIcon,
                playPauseLabel,
                commandIntent(playPauseAction, REQUEST_PLAY_PAUSE)
            )
            .addAction(
                R.drawable.ic_forward,
                getString(R.string.tts_forward),
                commandIntent(ACTION_NEXT, REQUEST_NEXT)
            )
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.tts_stop),
                commandIntent(ACTION_STOP, REQUEST_STOP)
            )

        contentIntent()?.let { builder.setContentIntent(it) }
        mediaSession?.sessionToken?.let { token ->
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun displayTitle(): String =
        state.title.ifBlank { getString(R.string.app_name) }

    private fun displaySubtitle(): String =
        state.subtitle.ifBlank { getString(R.string.tts_notification_playing) }

    private fun displayStatus(): String = when {
        state.loading -> getString(R.string.tts_notification_preparing)
        state.paused -> getString(R.string.tts_notification_paused)
        else -> state.subtitle.ifBlank { getString(R.string.tts_notification_playing) }
    }

    private fun contentIntent(): PendingIntent? {
        if (state.bookId <= 0L) return null
        val intent = ReaderActivity.newIntent(this, state.bookId).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tts_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- wake lock

    private fun applyWakeLock(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply { setReferenceCounted(false) }
            }
            try {
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
            } catch (_: Exception) {
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.captainivy.ebookreader.tts.START"
        const val ACTION_STOP = "com.captainivy.ebookreader.tts.STOP"
        private const val ACTION_PLAY = "com.captainivy.ebookreader.tts.PLAY"
        private const val ACTION_PAUSE = "com.captainivy.ebookreader.tts.PAUSE"
        private const val ACTION_PREVIOUS = "com.captainivy.ebookreader.tts.PREVIOUS"
        private const val ACTION_NEXT = "com.captainivy.ebookreader.tts.NEXT"

        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1002

        private const val REQUEST_CONTENT = 10
        private const val REQUEST_PLAY_PAUSE = 11
        private const val REQUEST_PREVIOUS = 12
        private const val REQUEST_NEXT = 13
        private const val REQUEST_STOP = 14

        private const val MEDIA_SESSION_TAG = "TtsPlayback"
        private const val WAKE_LOCK_TAG = "ebookreader:tts"
        private const val PLAYBACK_POSITION_UNKNOWN = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN

        private const val PLAYBACK_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        fun start(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
