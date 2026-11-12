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
                stateBuilder.setState(PlaybackStateCompat.STATE_PLAYING, pos, currentSpeed)
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
                // 只在拿到真实播放位置时才写，避免 seekTo 尚未生效时把 0 写回磁盘
                if (pos > 0) {
                    persistPosition(pos)
                    Log.i("PlaybackService", "autoSave: pos=$pos")
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

        /** 是否持有真实的 MediaPlayer 实例。进程重建后一定为 false，此时禁止任何写盘 */
        val hasLivePosition: Boolean
            get() = playerRef != null

        /** 当前倍速（全局、持久化）。只允许 Service 内部更新，Activity 只读 */
        @Volatile
        var currentSpeed: Float = 1.0f

        var folderKey: String? = null
        var isActive: Boolean = false
        var onTrackComplete: (() -> Unit)? = null
        var onTrackError: (() -> Unit)? = null
        /** 设备不支持所选倍速时的界面回调，回退到 1× 后触发 */
        var onSpeedUnsupported: (() -> Unit)? = null

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

        /**
         * 切换文件夹：先让 Service 用旧 folderKey 保存当前曲目，再把 folderKey 换成新路径。
         * 顺序放在 Service 内部完成，避免 Activity 先改 key 导致旧进度写进新文件夹。
         */
        fun switchFolder(context: Context, path: String) {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = "SWITCH_FOLDER"
                putExtra("folder", path)
            })
        }

        /** 播完整个文件夹后清空该文件夹的进度。静态方法，后台回调也能安全调用 */
        fun clearProgress() {
            folderKey?.let { PlaybackStore.clearPosition(it) }
        }

        /**
         * 用户拖动进度条：位置来自 SeekBar 的显式输入，立即写盘。
         * 这个值不是进程重建后的内存默认值，因此即使播放器实例不存在也允许写，
         * 保证之后冷启动 / 切后台读到的都是用户指定的位置。
         */
        fun seekTo(context: Context, pos: Int, folderPath: String, fileName: String) {
            if (pos < 0) return
            PlaybackStore.savePosition(folderPath, fileName, pos)
            if (hasLivePosition) {
                context.startService(Intent(context, PlaybackService::class.java).apply {
                    action = "SEEK"
                    putExtra("position", pos)
                })
            }
        }

        /**
         * 切换倍速：先落盘（覆盖「Service 尚未启动」的冷启动场景），再让正在运行的 Service 应用到播放器。
         * 没有活跃播放器时不启动服务，等下次开播在 startPlayback() 里应用磁盘上的档位。
         */
        fun setSpeed(context: Context, speed: Float) {
            if (speed <= 0f || speed > 4.0f) return
            PlaybackStore.saveSpeed(speed)
            currentSpeed = speed
            if (hasLivePosition) {
                context.startService(Intent(context, PlaybackService::class.java).apply {
                    action = "SPEED"
                    putExtra("speed", speed)
                })
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程重建后从磁盘恢复倍速（Activity 可能已经设置过，这里读到的仍是最新值）
        currentSpeed = PlaybackStore.getSpeed()
        createNotificationChannel()
        initMediaSession()
        startForeground(1, buildNotification("等待播放"))
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "MP3PlayerSession")
        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { resumePlayback() }
            override fun onPause() { pausePlayback() }
            override fun onStop() {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            override fun onSeekTo(pos: Long) { seekPlayback(pos.toInt()) }
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
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) currentSpeed else 0.0f
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
            "PAUSE" -> pausePlayback()
            "RESUME" -> resumePlayback()
            "SEEK" -> seekPlayback(intent.getIntExtra("position", 0))
            "SPEED" -> {
                val s = intent.getFloatExtra("speed", 1.0f)
                currentSpeed = if (s > 0f && s <= 4.0f) s else 1.0f
                // 暂停时不改播放器参数：部分机型只在 STARTED 状态接受，resumePlayback() 会重新应用。
                // 播放中切档立即生效，并借这次调用检测设备是否真的支持该档位。
                if (isActive && player != null && !applySpeed()) {
                    revertSpeedToNormal()
                }
                setMediaPlaybackState(
                    if (isActive) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
                )
            }
            "STOP" -> {
                stopPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            "SWITCH_FOLDER" -> {
                // stopPlayback() 内部用旧 folderKey 保存当前曲目，之后才切换 key
                stopPlayback()
                folderKey = intent.getStringExtra("folder")
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
            // 本次播放的真实起点：恢复播放时是磁盘上的位置，切曲时是 0
            val startPos = if (positionMs > 0 && positionMs < duration) {
                player?.seekTo(positionMs)
                positionMs
            } else {
                0
            }
            currentPosition = startPos
            player?.setOnCompletionListener {
                Log.i("PlaybackService", "OnCompletionListener fired")
                currentPosition = 0
                duration = 0
                setMediaPlaybackState(PlaybackStateCompat.STATE_NONE)
                onTrackComplete?.invoke()
            }
            player?.start()
            Log.i("PlaybackService", "start() called, isActive=true, speed=$currentSpeed")
            isPlayerActive = true
            isActive = true
            // 开播后应用全局倍速；设备不支持该档则回退 1× 并通知界面
            if (!applySpeed()) revertSpeedToNormal()
            // 开播即把真实起点写入磁盘（含切曲时的 0），进度由 Service 统一维护
            persistPosition(startPos)

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
        // 只有真正在播放、且持有有效播放器时才保存（避免用内存默认值覆盖磁盘）
        if (isPlayerActive && player != null) {
            persistPosition(player?.currentPosition ?: 0)
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

    /**
     * 播放进度唯一的写盘入口。
     * 进程重建后 player 为 null、静态位置归零，这里的守卫可以彻底阻止
     * 内存默认值被写回 SharedPreferences。Activity 一律只读，不得调用本方法。
     */
    private fun persistPosition(pos: Int) {
        val fk = folderKey ?: return
        if (currentTitle.isEmpty() || currentTitle == "MP3播放器") return
        if (player == null) return
        if (pos < 0) return
        PlaybackStore.savePosition(fk, currentTitle, pos)
    }

    /** 统一的暂停流程：onStartCommand 与锁屏/通知栏 MediaSession 共用，暂停即写盘 */
    private fun pausePlayback() {
        if (!isPlayerActive) return
        player?.pause()
        currentPosition = player?.currentPosition ?: 0
        isActive = false
        lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        persistPosition(currentPosition)
        setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification("已暂停")
    }

    /** 恢复播放时重新挂上锁屏刷新与 5 秒自动保存，否则暂停过一次后就再也不写盘了 */
    private fun resumePlayback() {
        if (!isPlayerActive) return
        player?.start()
        isActive = true
        // 暂停期间可能换过档，恢复时重新应用（部分机型只在 STARTED 状态接受）
        if (!applySpeed()) revertSpeedToNormal()
        lockscreenUpdateHandler.removeCallbacks(lockscreenUpdateRunnable)
        lockscreenUpdateHandler.post(lockscreenUpdateRunnable)
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        autoSaveHandler.postDelayed(autoSaveRunnable, 5000)
        setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        updateNotification("正在播放")
    }

    /** 拖动进度后立即写盘，避免 seek 生效前进程被杀导致位置回退 */
    private fun seekPlayback(pos: Int) {
        if (player == null) return
        player?.seekTo(pos)
        persistPosition(pos)
    }

    /**
     * 把 currentSpeed 应用到 MediaPlayer，pitch 固定 1.0（不变调）。
     * 返回 false = 设备/编码明确不支持该档；状态类异常返回 true，
     * 由下一次 resumePlayback() 重试，避免把临时状态问题误报成「不支持」。
     */
    private fun applySpeed(): Boolean {
        val p = player ?: return true
        return try {
            val params = p.playbackParams
            params.speed = currentSpeed
            params.pitch = 1.0f
            p.playbackParams = params
            Log.i("PlaybackService", "applySpeed: speed=$currentSpeed")
            true
        } catch (e: IllegalArgumentException) {
            Log.w("PlaybackService", "applySpeed unsupported: ${e.message}")
            false
        } catch (e: UnsupportedOperationException) {
            Log.w("PlaybackService", "applySpeed unsupported: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w("PlaybackService", "applySpeed deferred: ${e.message}")
            true
        }
    }

    /** 设备不支持所选倍速：回到 1×、同步落盘，并回调界面把选中态退回 1× 后提示 */
    private fun revertSpeedToNormal() {
        currentSpeed = 1.0f
        PlaybackStore.saveSpeed(1.0f)
        try {
            player?.let { p ->
                val params = p.playbackParams
                params.speed = 1.0f
                params.pitch = 1.0f
                p.playbackParams = params
            }
        } catch (e: Exception) { }
        Log.w("PlaybackService", "revertSpeedToNormal: 已回退 1×")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onSpeedUnsupported?.invoke()
        }
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
