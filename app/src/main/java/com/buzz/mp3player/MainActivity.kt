package com.buzz.mp3player

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var folderPathText: TextView
    private lateinit var trackList: RecyclerView
    private lateinit var trackAdapter: TrackAdapter
    private lateinit var nowPlayingText: TextView
    private lateinit var timeText: TextView
    private lateinit var trackCountText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var recentFoldersBtn: Button

    /** 倍速档位与对应控件，顺序即界面从左到右的顺序 */
    private val speedOptions = listOf(
        1.0f to R.id.speedChip1,
        1.5f to R.id.speedChip15,
        2.0f to R.id.speedChip2,
        3.0f to R.id.speedChip3,
        4.0f to R.id.speedChip4
    )
    private val speedButtons = mutableListOf<TextView>()

    private var folderKey: String? = null
    private var currentFiles: List<File> = emptyList()
    private var currentIndex: Int = -1
    private var savedPositionMs: Int = 0
    private var currentDurationMs: Int = 0
    private var isPlaying: Boolean = false
    private var seekBarTracking: Boolean = false

    private val REQUEST_ALL_FILES = 1001

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && PlaybackService.isActive && PlaybackService.hasLivePosition && !seekBarTracking && currentIndex >= 0 && currentIndex < currentFiles.size) {
                val pos = PlaybackService.currentPosition
                val dur = PlaybackService.duration
                if (dur > 0) {
                    seekBar.max = dur
                    seekBar.progress = pos
                    timeText.text = "${formatTime(pos)}/${formatTime(dur)}"
                    nowPlayingText.text = currentFiles[currentIndex].name
                } else {
                    nowPlayingText.text = currentFiles[currentIndex].name
                }
                // 进度写盘只由 PlaybackService 负责，这里只刷新界面
            }
            updateHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15 (targetSdk 35) 强制 edge-to-edge，statusBarColor 已废弃无效，
        // 状态栏背后露出的是根布局的浅色背景。界面没有 values-night、恒为浅色，
        // 因此显式要求系统把状态栏图标画成深色，否则夜间模式下会白底白图标。
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        folderPathText = findViewById(R.id.folderPathText)
        trackList = findViewById(R.id.trackList)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        timeText = findViewById(R.id.timeText)
        trackCountText = findViewById(R.id.trackCountText)
        seekBar = findViewById(R.id.seekBar)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)
        recentFoldersBtn = findViewById(R.id.recentFoldersBtn)

        // 倍速分段按钮：写入由 PlaybackService 统一负责，界面只负责选中态与读盘恢复
        for ((speed, id) in speedOptions) {
            val chip = findViewById<TextView>(id)
            speedButtons.add(chip)
            chip.setOnClickListener {
                PlaybackService.setSpeed(this@MainActivity, speed)
                applySpeedUi(speed)
            }
        }
        applySpeedUi(PlaybackStore.getSpeed())

        trackAdapter = TrackAdapter { index -> playTrackAt(index) }
        trackList.layoutManager = LinearLayoutManager(this)
        trackList.adapter = trackAdapter

        findViewById<Button>(R.id.pickFolderBtn).setOnClickListener {
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccess()
            } else {
                showFolderPicker(Environment.getExternalStorageDirectory())
            }
        }

        recentFoldersBtn.setOnClickListener {
            showRecentFoldersDialog()
        }

        playPauseBtn.setOnClickListener { togglePlayPause() }
        prevBtn.setOnClickListener { playPrev() }
        nextBtn.setOnClickListener { playNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentIndex >= 0 && currentIndex < currentFiles.size) {
                    // 拖动过程中就同步续播起点，避免松手事件未到达时位置回退
                    savedPositionMs = progress
                    timeText.text = "${formatTime(progress)}/${formatTime(seekBar.max)}"
                    nowPlayingText.text = currentFiles[currentIndex].name
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seekBarTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekBarTracking = false
                val pos = seekBar.progress
                savedPositionMs = pos
                val fk = folderKey
                if (fk != null && currentIndex >= 0 && currentIndex < currentFiles.size) {
                    PlaybackService.seekTo(this@MainActivity, pos, fk, currentFiles[currentIndex].name)
                }
            }
        })

        PlaybackService.onTrackComplete = {
            isPlaying = false
            playPauseBtn.setImageResource(R.drawable.ic_play)
            if (currentIndex < currentFiles.size - 1) {
                playTrackAt(currentIndex + 1)
            } else {
                nowPlayingText.text = "播放完毕"
                timeText.text = ""
                PlaybackService.clearProgress()
            }
        }
        PlaybackService.onTrackError = {
            isPlaying = false
            playPauseBtn.setImageResource(R.drawable.ic_play)
            nowPlayingText.text = "播放出错，请重试"
            timeText.text = ""
        }
        PlaybackService.onSpeedUnsupported = {
            applySpeedUi(PlaybackStore.getSpeed())
            Toast.makeText(this, "设备不支持该倍速，已恢复 1×", Toast.LENGTH_SHORT).show()
        }
        updateHandler.post(updateRunnable)

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess()
        } else {
            autoLoadSavedFolder()
        }

        updateRecentFoldersBtnVisibility()
    }

    /** 显示或隐藏"最近"按钮：有最近文件夹时才显示 */
    private fun updateRecentFoldersBtnVisibility() {
        val recent = PlaybackStore.getRecentFolders()
        recentFoldersBtn.visibility = if (recent.isNotEmpty()) Button.VISIBLE else Button.GONE
    }

    /** 弹出最近播放过的文件夹列表供用户选择 */
    private fun showRecentFoldersDialog() {
        val recent = PlaybackStore.getRecentFolders()
        if (recent.isEmpty()) {
            Toast.makeText(this, "没有最近播放过的文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        // 过滤掉已不存在或没有MP3的文件夹，并获取显示名
        val validItems = recent.mapNotNull { path ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                val mp3Count = dir.listFiles()?.filter {
                    it.name.endsWith(".mp3", ignoreCase = true) && it.isFile
                }?.size ?: 0
                if (mp3Count > 0) {
                    Triple(path, dir.name, mp3Count)
                } else {
                    // 文件夹还在但没有MP3了，从最近列表中移除
                    PlaybackStore.removeRecentFolder(path)
                    null
                }
            } else {
                // 文件夹已不存在，从最近列表中移除
                PlaybackStore.removeRecentFolder(path)
                null
            }
        }

        if (validItems.isEmpty()) {
            updateRecentFoldersBtnVisibility()
            Toast.makeText(this, "没有最近播放过的文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        val displayNames = validItems.map { "${it.second}（${it.third}首）" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("最近播放过的文件夹")
            .setItems(displayNames) { _, which ->
                loadFolderFromPath(validItems[which].first)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestAllFilesAccess() {
        AlertDialog.Builder(this)
            .setTitle("需要文件访问权限")
            .setMessage("为了读取MP3文件，需要授予「所有文件访问」权限。请在设置页面中开启。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:com.buzz.mp3player"))
                startActivityForResult(intent, REQUEST_ALL_FILES)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ALL_FILES) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                autoLoadSavedFolder()
            } else {
                Toast.makeText(this, "未获得权限，无法读取文件", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun autoLoadSavedFolder() {
        val savedPath = PlaybackStore.getSavedFolderPath()
        if (savedPath != null && savedPath.isNotEmpty()) {
            val dir = File(savedPath)
            if (dir.exists() && dir.isDirectory) {
                loadFolderFromPath(savedPath)
                return
            }
        }
        folderPathText.text = "请选择包含MP3文件的文件夹"
    }

    private fun showFolderPicker(currentDir: File) {
        val dirs = currentDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

        val items = mutableListOf<String>()
        if (currentDir.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
            items.add(".. (上级目录)")
        }
        items.addAll(dirs.map { it.name })

        val title = currentDir.absolutePath

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                val selectedName = items[which]
                if (selectedName == ".. (上级目录)") {
                    showFolderPicker(currentDir.parentFile ?: currentDir)
                } else {
                    val subDir = dirs.first { it.name == selectedName }
                    showFolderPicker(subDir)
                }
            }
            .setPositiveButton("选择此文件夹") { _, _ ->
                loadFolderFromPath(currentDir.absolutePath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从文件本身读时长，不依赖播放器的内存状态 */
    private fun getMp3DurationMs(file: File): Int {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            retriever.release()
            durationMs
        } catch (e: Exception) {
            0
        }
    }

    private fun getMp3Duration(file: File): String {
        return formatTime(getMp3DurationMs(file))
    }

    private fun loadFolderFromPath(path: String) {
        val dir = File(path)
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".mp3", ignoreCase = true) && it.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()

        if (files.isEmpty()) {
            Toast.makeText(this, "该文件夹没有MP3文件", Toast.LENGTH_SHORT).show()
            return
        }

        // 由 Service 在内部完成「先按旧 key 保存 → 再换成新 key」，避免写错文件夹
        PlaybackService.switchFolder(this, path)

        folderKey = path
        currentFiles = files
        folderPathText.text = dir.name
        PlaybackStore.saveFolderPath(path)
        PlaybackStore.addRecentFolder(path)     // 记录到最近列表
        updateRecentFoldersBtnVisibility()      // 更新按钮可见性

        // Get durations for all files
        val durations = files.map { getMp3Duration(it) }

        trackAdapter.updateFiles(
            files.map { it.name },
            files.map { Uri.fromFile(it) },
            durations
        )

        trackCountText.text = "${files.size} 首歌曲"

        val savedFile = PlaybackStore.getSavedFile(path)
        val savedPos = PlaybackStore.getSavedPosition(path)
        Log.i("MainActivity", "loadFolderFromPath: path=$path, savedFile=$savedFile, savedPos=$savedPos")
        currentIndex = -1
        isPlaying = false
        savedPositionMs = 0
        playPauseBtn.setImageResource(R.drawable.ic_play)

        if (savedFile != null && savedFile.isNotEmpty()) {
            val idx = files.indexOfFirst { it.name == savedFile }
            if (idx >= 0) {
                currentIndex = idx
                savedPositionMs = savedPos
                nowPlayingText.text = files[idx].name
                timeText.text = "${formatTime(savedPos)}/${durations[idx]}"
                // 初始化进度条：时长取自文件本身，进度取自磁盘
                val durMs = getMp3DurationMs(files[idx])
                currentDurationMs = durMs
                seekBar.max = durMs
                seekBar.progress = savedPos
                Log.i("MainActivity", "SeekBar: max=$durMs, progress=$savedPos")
                trackAdapter.highlightIndex = idx
                trackAdapter.notifyDataSetChanged()
            } else {
                currentIndex = 0
                currentDurationMs = getMp3DurationMs(files[0])
                nowPlayingText.text = files[0].name
                timeText.text = "0:00/${durations[0]}"
                trackAdapter.highlightIndex = 0
                trackAdapter.notifyDataSetChanged()
            }
        } else {
            currentIndex = 0
            currentDurationMs = getMp3DurationMs(files[0])
            nowPlayingText.text = files[0].name
            timeText.text = "0:00/${durations[0]}"
            trackAdapter.highlightIndex = 0
            trackAdapter.notifyDataSetChanged()
        }
    }

    private fun playTrackAt(index: Int, positionMs: Int = 0) {
        if (index < 0 || index >= currentFiles.size) return
        currentIndex = index
        val trackName = currentFiles[index].name
        nowPlayingText.text = trackName
        val file = currentFiles[index]
        PlaybackService.play(this, Uri.fromFile(file), positionMs, trackName)
        isPlaying = true
        playPauseBtn.setImageResource(R.drawable.ic_pause)
        trackAdapter.highlightIndex = index
        trackAdapter.notifyDataSetChanged()
        // 开播位置由 PlaybackService 写入磁盘，这里不再写
    }

    private fun togglePlayPause() {
        if (currentIndex < 0) {
            Toast.makeText(this, "请先选择文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        if (isPlaying && PlaybackService.isActive) {
            PlaybackService.pause(this)
            isPlaying = false
            savedPositionMs = PlaybackService.currentPosition
            // 暂停位置由 PlaybackService 写盘；此处只更新界面用的临时值
            playPauseBtn.setImageResource(R.drawable.ic_play)
        } else {
            if (PlaybackService.hasLivePosition) {
                // 播放器还在（暂停状态）：直接恢复，保留拖动后的位置，不要重建播放器
                PlaybackService.resume(this)
                isPlaying = true
                playPauseBtn.setImageResource(R.drawable.ic_pause)
            } else {
                // 进程重建或服务已回收：按磁盘里的最新位置重新开播
                playTrackAt(currentIndex, savedPositionMs)
            }
        }
    }

    private fun playPrev() {
        if (currentIndex > 0) playTrackAt(currentIndex - 1)
        else Toast.makeText(this, "已经是第一首", Toast.LENGTH_SHORT).show()
    }

    private fun playNext() {
        if (currentIndex < currentFiles.size - 1) playTrackAt(currentIndex + 1)
        else Toast.makeText(this, "已经是最后一首", Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    /** 按当前档位刷新 5 个 chip 的选中态。倍速是全局设置，切曲/换文件夹不重置 */
    private fun applySpeedUi(speed: Float) {
        for (i in speedButtons.indices) {
            val selected = kotlin.math.abs(speedOptions[i].first - speed) < 0.01f
            val chip = speedButtons[i]
            chip.setBackgroundResource(
                if (selected) R.drawable.bg_speed_chip_selected else R.drawable.bg_speed_chip_normal
            )
            chip.setTextColor(
                if (selected) android.graphics.Color.parseColor("#FFFFFF")
                else android.graphics.Color.parseColor("#2A6DF7")
            )
            chip.isSelected = selected
        }
    }

    /**
     * 界面只负责恢复显示，绝不写进度。
     * 冷启动后播放器实例为空、内存位置为 0，任何在这里写盘的动作
     * 都会把 SharedPreferences 里正确的进度覆盖掉。
     */
    override fun onResume() {
        super.onResume()
        applySpeedUi(PlaybackStore.getSpeed())
        if (PlaybackService.isActive && PlaybackService.hasLivePosition && currentIndex >= 0 && currentIndex < currentFiles.size) {
            isPlaying = true
            playPauseBtn.setImageResource(R.drawable.ic_pause)
            val pos = PlaybackService.currentPosition
            val dur = PlaybackService.duration
            if (dur > 0) {
                seekBar.max = dur
                seekBar.progress = pos
                timeText.text = "${formatTime(pos)}/${formatTime(dur)}"
            }
            nowPlayingText.text = currentFiles[currentIndex].name
        } else if (currentIndex >= 0 && currentIndex < currentFiles.size) {
            // 暂停或进程重建：进度只从磁盘读取，时长从文件读取
            isPlaying = false
            playPauseBtn.setImageResource(R.drawable.ic_play)
            val savedPos = folderKey?.let { PlaybackStore.getSavedPosition(it) } ?: 0
            savedPositionMs = savedPos
            if (currentDurationMs > 0) {
                seekBar.max = currentDurationMs
                seekBar.progress = savedPos.coerceAtMost(currentDurationMs)
                timeText.text = "${formatTime(savedPos)}/${formatTime(currentDurationMs)}"
            }
            nowPlayingText.text = currentFiles[currentIndex].name
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateHandler.removeCallbacks(updateRunnable)
        PlaybackService.onTrackComplete = null
        PlaybackService.onTrackError = null
        PlaybackService.onSpeedUnsupported = null
    }
}
