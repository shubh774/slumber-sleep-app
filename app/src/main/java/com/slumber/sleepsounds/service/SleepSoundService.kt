package com.slumber.sleepsounds.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.slumber.sleepsounds.MainActivity
import com.slumber.sleepsounds.R
import com.slumber.sleepsounds.audio.NoiseEngine
import com.slumber.sleepsounds.audio.NoiseType
import com.slumber.sleepsounds.widget.SlumberWidgetProvider

/**
 * Foreground Service that owns playback. This is what makes the app actually work as a
 * sleep-sounds app: without it, Android suspends the process a few seconds after the screen
 * locks and the sound cuts out -- which is exactly when the user needs it most.
 *
 * Supports mixing multiple sounds at once via NoiseEngine.toggleSound(). The Activity and
 * the home-screen widget both talk to this service; neither owns the audio directly.
 */
class SleepSoundService : Service() {

    companion object {
        const val CHANNEL_ID = "slumber_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.slumber.sleepsounds.action.PLAY_PAUSE"
        const val ACTION_STOP = "com.slumber.sleepsounds.action.STOP"
        const val ACTION_TOGGLE_SOUND = "com.slumber.sleepsounds.action.TOGGLE_SOUND"
        const val ACTION_SET_TIMER = "com.slumber.sleepsounds.action.SET_TIMER"
        const val ACTION_CANCEL_TIMER = "com.slumber.sleepsounds.action.CANCEL_TIMER"
        const val EXTRA_SOUND_ID = "extra_sound_id"
        const val EXTRA_TIMER_MINUTES = "extra_timer_minutes"
    }

    interface PlaybackListener {
        fun onStateChanged(isPlaying: Boolean, activeSounds: Set<NoiseType>)
        fun onTimerTick(remainingMillis: Long)
        fun onTimerFinished()
    }

    private val binder = LocalBinder()
    private val engine = NoiseEngine()
    private lateinit var mediaSession: MediaSessionCompat
    private var countDownTimer: CountDownTimer? = null
    private var listener: PlaybackListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): SleepSoundService = this@SleepSoundService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "SlumberSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumeIfPossible()
                override fun onPause() = pausePlayback()
                override fun onStop() = stopPlayback()
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_STOP -> stopPlayback()
            ACTION_TOGGLE_SOUND -> {
                val id = intent.getStringExtra(EXTRA_SOUND_ID) ?: return START_STICKY
                toggleSound(NoiseType.fromId(id))
            }
            ACTION_SET_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_TIMER_MINUTES, 0)
                if (minutes > 0) setTimer(minutes.toLong())
            }
            ACTION_CANCEL_TIMER -> cancelTimer()
        }
        // START_STICKY: if the OS kills the process under memory pressure it will try to
        // restart the service, though without our extras -- acceptable since the user would
        // have to re-pick sounds anyway; the important part is we don't silently vanish.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setListener(listener: PlaybackListener?) {
        this.listener = listener
    }

    fun isPlaying(): Boolean = engine.isPlaying
    fun activeSounds(): Set<NoiseType> = engine.currentActiveTypes()

    fun toggleSound(type: NoiseType) {
        engine.toggleSound(type)
        if (engine.isPlaying) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        publishState()
    }

    fun togglePlayPause() {
        if (engine.isPlaying) pausePlayback() else resumeIfPossible()
    }

    fun resumeIfPossible() {
        engine.resume()
        if (engine.isPlaying) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        publishState()
    }

    fun pausePlayback() {
        engine.pause()
        publishState()
        // Keep the service (and notification) around briefly so the user can resume
        // quickly, but drop foreground priority since we're silent now.
        stopForeground(STOP_FOREGROUND_DETACH)
        updateNotification()
    }

    fun stopPlayback() {
        engine.pause()
        // Also forget the selection entirely -- Stop means stop, unlike Pause.
        engine.currentActiveTypes().forEach { engine.toggleSound(it) }
        cancelTimer()
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setTimer(minutes: Long) {
        countDownTimer?.cancel()
        val millis = minutes * 60 * 1000
        countDownTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                listener?.onTimerTick(millisUntilFinished)
            }
            override fun onFinish() {
                listener?.onTimerFinished()
                pausePlayback()
            }
        }.start()
    }

    fun cancelTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    private fun publishState() {
        updatePlaybackState()
        updateNotification()
        listener?.onStateChanged(engine.isPlaying, engine.currentActiveTypes())
        SlumberWidgetProvider.updateAllWidgets(this, engine.isPlaying, engine.currentActiveTypes())
    }

    private fun updatePlaybackState() {
        val state = if (engine.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, 0, 1f)
                .build()
        )
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun mixLabel(): String {
        val active = engine.currentActiveTypes()
        return when {
            active.isEmpty() -> "Slumber"
            active.size == 1 -> "${active.first().emoji} ${active.first().displayName}"
            active.size <= 3 -> active.joinToString(" + ") { it.displayName }
            else -> "${active.size} sounds mixing"
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SleepSoundService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, SleepSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (engine.isPlaying) {
            NotificationCompat.Action.Builder(R.drawable.ic_pause, "Pause", playPauseIntent).build()
        } else {
            NotificationCompat.Action.Builder(R.drawable.ic_play, "Play", playPauseIntent).build()
        }
        val stopAction = NotificationCompat.Action.Builder(R.drawable.ic_stop, "Stop", stopIntent).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(mixLabel())
            .setContentText(if (engine.isPlaying) "Playing" else "Paused")
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(engine.isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sleep sound playback",
                NotificationManager.IMPORTANCE_LOW // LOW = no sound/heads-up for the notification itself
            ).apply {
                description = "Controls for the currently playing sleep sound mix"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        engine.release()
        cancelTimer()
        mediaSession.release()
        SlumberWidgetProvider.updateAllWidgets(this, false, emptySet())
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away from Recents while nothing is playing, it's fine
        // to fully stop. If a sound IS playing, we deliberately keep going -- swiping the
        // task away is not the same as tapping Stop, and killing sleep sounds when someone
        // swipes recents by accident (common while half-asleep) would defeat the app's purpose.
        if (!engine.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}
