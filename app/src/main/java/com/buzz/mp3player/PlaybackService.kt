package com.buzz.mp3player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

class PlaybackService : Service() {

    private var player: MediaPlayer? = null
    var isPlayerActive: Boolean = false

    private var mediaSession: MediaSessionCompat? = null
    private var currentTitle: String = "MP3播放器"

    private val lockscreenUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockscreenUpdateRunnable = object : Runnable {
        override fun run() {
            if (isActive && player != null) {
                val pos = player?.currentPosition?.toLong() ?: 0L
                val stateBuilder = PlaybackStateCompat.Builder()
                stateBuilder.setActions(
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, pos, 1.0f)
                mediaSession?.setPlaybackState(stateBuilder.build())
                lockscreenUpdateHandler.postDelayed(this, 1000)
            }
        }
    }

    // 定期保存播放进度到 SharedPreferences（即使 Activity 在后台也能保存）
    private val autoSaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoSaveRunnable = object : Runnable {
        override fun run() {
            if (isActive && player != null) {
                val pos = player?.currentPosition ?: 0
                val fk = folderKey
                if (fk != null && currentTitle != "MP3播放器") {
                    PlaybackStore.savePosition(fk, currentTitle, pos)
                    Log.i("PlaybackService", "autoSave: folderKey=$fk, title=$currentTitle, pos=$pos")
                }
                autoSaveHandler.postDelayed(this, 5000)
            }
        }
    }

    companion object {
        @Volatile
        private var playerRef: MediaPlayer? = null

        var currentPosition: Int = 0
            get() = playerRef?.currentPosition ?: field
            set(value) { field = value }

        var duration: Int = 0
            get() = playerRef?.duration ?: field
            set(value) { field = value }

        var folderKey: String? = null
        var isActive: Boolean = false
        var onTrackComplete: (() -> Unit)? = null
        var onTrackError: (() -> Unit)? = null

        fun play(context: Context, uri: Uri, positionMs: Int = 0, title: String? = null) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = "PLAY"
                putExtra("uri", uri.toString())
                putExtra("position", positionMs)
                if (title != null) putExtra("title", title)
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).apply { action = "PAUSE" })
        }

        fun resume(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).apply { action = "RESUME" })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).apply { action = "STOP" })
        }

        fun seekTo(context: Context, pos: Int) {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = "SEEK"
                putExtra("position", pos)
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        startForeground(1, buildNotification("等待播放"))
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MP3PlayerSession")
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                if (isPlayerActive) {
                    player?.start()
                    isActive = true
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification("正在播放")
                }
            }

            override fun onPause() {
                if (isPlayerActive) {
                    player?.pause()
                    currentPosition = player?.currentPosition ?: 0
                    isActive = false
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification("已暂停")
                }
            }

            override fun onStop() {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            override fun onSeekTo(pos: Long) {
                player?.seekTo(pos.toInt())
            }
        })
        mediaSession?.setActive(true)
        setMediaPlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    private fun setMediaPlaybackState(state: Int) {
        val stateBuilder = PlaybackStateCompat.Builder()
        val actions = when (state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
            }
            PlaybackStateCompat.STATE_PAUSED -> {
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
            }
            else -> {
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_STOP
            }
        }
        stateBuilder.setActions(actions)
        val pos = player?.currentPosition?.toLong() ?: PlaybackService.currentPosition.toLong()
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f
        stateBuilder.setState(state, pos, speed)
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    private fun updateMediaMetadata(title: String) {
        val dur = player?.duration ?: 0
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "MP3播放器")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur.toLong())
            .build()
        mediaSession?.setMetadata(metadata)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) return START_STICKY

        when (intent.action) {
            "PLAY" -> {
                val uriStr = intent.getStringExtra("uri") ?: return START_STICKY
                val pos = intent.getIntExtra("position", 0)
                val title = intent.getStringExtra("title") ?: "MP3播放器"
                startPlayback(Uri.parse(uriStr), pos, title)
            }
            "PAUSE" -> {
                if (isPlayerActive) {
                    player?.pause()
                    currentPosition = player?.currentPosition ?: 0
                    isActive = false
                    lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
                    autoSaveHandler.removeCallbacks(autoSaveRunnable)
                    // 暂停时立即保存一次
                    folderKey?.let { fk ->
                        if (currentTitle != "MP3播放器") PlaybackStore.savePosition(fk, currentTitle, currentPosition)
                    }
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    updateNotification("已暂停")
                }
            }
            "RESUME" -> {
                if (isPlayerActive) {
                    player?.start()
                    isActive = true
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification("正在播放")
                }
            }
            "SEEK" -> {
                val pos = intent.getIntExtra("position", 0)
                player?.seekTo(pos)
            }
            "STOP" -> {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startPlayback(uri: Uri, positionMs: Int, title: String = "MP3播放器") {
        Log.i("PlaybackService", "startPlayback: uri=$uri, positionMs=$positionMs, title=$title")
        stopPlayback()
        player = MediaPlayer()
        playerRef = player
        currentTitle = title

        player?.setOnErrorListener { mp, what, extra ->
            Log.i("PlaybackService", "OnErrorListener: what=$what extra=$extra")
            mp.release()
            player = null
            playerRef = null
            isPlayerActive = false
            isActive = false
            currentPosition = 0
            duration = 0
            setMediaPlaybackState(PlaybackStateCompat.STATE_ERROR)
            onTrackError?.invoke()
            true
        }

        try {
            player?.setDataSource(this, uri)
            player?.prepare()
            Log.i("PlaybackService", "prepare succeeded, duration=${player?.duration}")
            duration = player?.duration ?: 0
            currentPosition = if (positionMs > 0 && positionMs < duration) {
                player?.seekTo(positionMs)
                positionMs
            } else {
                player?.currentPosition ?: 0
            }
            player?.setOnCompletionListener {
                Log.i("PlaybackService", "OnCompletionListener fired")
                currentPosition = 0
                duration = 0
                setMediaPlaybackState(PlaybackStateCompat.STATE_NONE)
                onTrackComplete?.invoke()
            }
            player?.start()
            Log.i("PlaybackService", "start() called, isActive=true")
            isPlayerActive = true
            isActive = true

            // 更新 MediaSession 元数据和播放状态
            updateMediaMetadata(title)
            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification("正在播放")
            lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
            lockscreenUpdateHandler.post(lockscreenUpdateRunnable)
            autoSaveHandler.removeCallbacks(autoSaveRunnable)
            autoSaveHandler.postDelayed(autoSaveRunnable, 5000)
        } catch (e: Exception) {
            Log.e("PlaybackService", "MediaPlayer exception: ${e.message}", e)
            player?.release()
            player = null
            playerRef = null
            isPlayerActive = false
            isActive = false
            setMediaPlaybackState(PlaybackStateCompat.STATE_ERROR)
            onTrackError?.invoke()
        }
    }

    private fun stopPlayback() {
        lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        // 只有真正在播放时才保存位置（避免 stop 覆盖之前存的正确值）
        if (isPlayerActive && player != null) {
            currentPosition = player?.currentPosition ?: 0
            folderKey?.let { fk ->
                if (currentTitle != "MP3播放器") PlaybackStore.savePosition(fk, currentTitle, currentPosition)
            }
        }
        isPlayerActive = false
        isActive = false
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) { }
        player = null
        playerRef = null
        setMediaPlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        mediaSession?.setActive(false)
        mediaSession?.release()
        mediaSession = null
        stopPlayback()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("playback", "播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String): Notification {
        val style = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0) // 在紧凑通知中显示第1个action（播放/暂停）

        // 构建播放/暂停 action
        val playPauseAction = if (isActive) {
            androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause,
                "暂停",
                buildActionPendingIntent("PAUSE")
            ).build()
        } else {
            androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "播放",
                buildActionPendingIntent("RESUME")
            ).build()
        }

        return androidx.core.app.NotificationCompat.Builder(this, "playback")
            .setContentTitle(currentTitle)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(buildMainActivityPendingIntent())
            .addAction(playPauseAction)
            .setStyle(style)
            .build()
    }

    private fun buildActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java)
            .notify(1, notification)
    }
}
