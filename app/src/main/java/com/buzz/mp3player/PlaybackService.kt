package com.buzz.mp3player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class PlaybackService : Service() {

    private var player: MediaPlayer? = null
    var isPlayerActive: Boolean = false

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

        fun play(context: Context, uri: Uri, positionMs: Int = 0) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = "PLAY"
                putExtra("uri", uri.toString())
                putExtra("position", positionMs)
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
        startForeground(1, buildNotification("等待播放"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) return START_STICKY

        when (intent.action) {
            "PLAY" -> {
                val uriStr = intent.getStringExtra("uri") ?: return START_STICKY
                val pos = intent.getIntExtra("position", 0)
                startPlayback(Uri.parse(uriStr), pos)
            }
            "PAUSE" -> {
                if (isPlayerActive) {
                    player?.pause()
                    currentPosition = player?.currentPosition ?: 0
                    updateNotification("已暂停")
                }
            }
            "RESUME" -> {
                if (isPlayerActive) {
                    player?.start()
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

    private fun startPlayback(uri: Uri, positionMs: Int) {
        Log.i("PlaybackService", "startPlayback: uri=$uri, positionMs=$positionMs")
        stopPlayback()
        player = MediaPlayer()
        playerRef = player

        player?.setOnErrorListener { mp, what, extra ->
            Log.i("PlaybackService", "OnErrorListener: what=$what extra=$extra")
            mp.release()
            player = null
            playerRef = null
            isPlayerActive = false
            isActive = false
            currentPosition = 0
            duration = 0
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
                onTrackComplete?.invoke()
            }
            player?.start()
            Log.i("PlaybackService", "start() called, isActive=true")
            isPlayerActive = true
            isActive = true
            updateNotification("正在播放")
        } catch (e: Exception) {
            Log.e("PlaybackService", "MediaPlayer exception: ${e.message}", e)
            player?.release()
            player = null
            playerRef = null
            isPlayerActive = false
            isActive = false
            onTrackError?.invoke()
        }
    }

    private fun stopPlayback() {
        currentPosition = player?.currentPosition ?: 0
        isPlayerActive = false
        isActive = false
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) { }
        player = null
        playerRef = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("playback", "播放", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, "playback")
            .setContentTitle("MP3播放器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(1, buildNotification(text))
    }
}