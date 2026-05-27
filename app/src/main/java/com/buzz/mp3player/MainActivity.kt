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

    private var folderKey: String? = null
    private var currentFiles: List<File> = emptyList()
    private var currentIndex: Int = -1
    private var savedPositionMs: Int = 0
    private var isPlaying: Boolean = false
    private var seekBarTracking: Boolean = false

    private val REQUEST_ALL_FILES = 1001

    private val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSaveTime: Long = 0

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isPlaying && PlaybackService.isActive && !seekBarTracking && currentIndex >= 0 && currentIndex < currentFiles.size) {
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
                val now = System.currentTimeMillis()
                if (now - lastSaveTime >= 5000) {
                    saveCurrentPosition()
                    lastSaveTime = now
                }
            }
            updateHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        folderPathText = findViewById(R.id.folderPathText)
        trackList = findViewById(R.id.trackList)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        timeText = findViewById(R.id.timeText)
        trackCountText = findViewById(R.id.trackCountText)
        seekBar = findViewById(R.id.seekBar)
        playPauseBtn = findViewById(R.id.playPauseBtn)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)

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
        playPauseBtn.setOnClickListener { togglePlayPause() }
        prevBtn.setOnClickListener { playPrev() }
        nextBtn.setOnClickListener { playNext() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentIndex >= 0 && currentIndex < currentFiles.size) {
                    timeText.text = "${formatTime(progress)}/${formatTime(seekBar.max)}"
                    nowPlayingText.text = currentFiles[currentIndex].name
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seekBarTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekBarTracking = false
                PlaybackService.seekTo(this@MainActivity, seekBar.progress)
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
                folderKey?.let { PlaybackStore.clearPosition(it) }
            }
        }
        PlaybackService.onTrackError = {
            isPlaying = false
            playPauseBtn.setImageResource(R.drawable.ic_play)
            nowPlayingText.text = "播放出错，请重试"
            timeText.text = ""
        }
        updateHandler.post(updateRunnable)

        if (!Environment.isExternalStorageManager()) {
            requestAllFilesAccess()
        } else {
            autoLoadSavedFolder()
        }
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

    private fun getMp3Duration(file: File): String {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            retriever.release()
            formatTime(durationMs)
        } catch (e: Exception) {
            ""
        }
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

        PlaybackService.stop(this)
        saveCurrentPosition()

        folderKey = path
        currentFiles = files
        PlaybackService.folderKey = path
        folderPathText.text = dir.name
        PlaybackStore.saveFolderPath(path)

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
                // 初始化进度条：设置总时长和当前位置
                val durMs = try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(files[idx].absolutePath)
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                    retriever.release()
                    d
                } catch (e: Exception) { 0 }
                seekBar.max = durMs
                seekBar.progress = savedPos
                Log.i("MainActivity", "SeekBar: max=$durMs, progress=$savedPos")
                trackAdapter.highlightIndex = idx
                trackAdapter.notifyDataSetChanged()
            } else {
                currentIndex = 0
                nowPlayingText.text = files[0].name
                timeText.text = "0:00/${durations[0]}"
                trackAdapter.highlightIndex = 0
                trackAdapter.notifyDataSetChanged()
            }
        } else {
            currentIndex = 0
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
        folderKey?.let { fk ->
            PlaybackStore.savePosition(fk, trackName, positionMs)
        }
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
            saveCurrentPosition()
            playPauseBtn.setImageResource(R.drawable.ic_play)
        } else {
            if (PlaybackService.isActive) {
                PlaybackService.resume(this)
                isPlaying = true
                playPauseBtn.setImageResource(R.drawable.ic_pause)
            } else {
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

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    override fun onResume() {
        super.onResume()
        // 从 PlaybackService 同步最新播放状态到界面
        if (PlaybackService.isActive && currentIndex >= 0 && currentIndex < currentFiles.size) {
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
            // 暂停状态，从 SharedPreferences 读取最新保存的位置
            isPlaying = false
            playPauseBtn.setImageResource(R.drawable.ic_play)
            val savedPos = PlaybackService.currentPosition
            val dur = PlaybackService.duration
            if (dur > 0) {
                seekBar.max = dur
                seekBar.progress = savedPos
                timeText.text = "${formatTime(savedPos)}/${formatTime(dur)}"
            }
            nowPlayingText.text = currentFiles[currentIndex].name
        }
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateHandler.removeCallbacks(updateRunnable)
        saveCurrentPosition()
        PlaybackService.onTrackComplete = null
        PlaybackService.onTrackError = null
    }

    private fun saveCurrentPosition() {
        folderKey?.let { fk ->
            if (currentIndex >= 0 && currentIndex < currentFiles.size) {
                PlaybackStore.savePosition(fk, currentFiles[currentIndex].name, PlaybackService.currentPosition)
            }
        }
    }
}
