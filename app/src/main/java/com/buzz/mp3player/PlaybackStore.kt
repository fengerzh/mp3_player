package com.buzz.mp3player

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object PlaybackStore {
    private lateinit var prefs: SharedPreferences

    private const val MAX_RECENT_FOLDERS = 10
    private const val KEY_PLAYBACK_SPEED = "playback_speed"

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
    }

    fun saveFolderPath(path: String) {
        prefs.edit().putString("saved_folder_path", path).apply()
    }

    fun getSavedFolderPath(): String? {
        return prefs.getString("saved_folder_path", null)
    }

    /**
     * 写入播放进度。只允许 PlaybackService 调用（persistPosition / seekTo）。
     * Activity 一律只读（getSavedFile / getSavedPosition），不得直接写盘。
     * 传入的位置必须来自真实播放器或用户显式拖动，绝不能是进程重建后的内存默认值 0。
     */
    fun savePosition(folderPath: String, fileName: String, positionMs: Int) {
        prefs.edit()
            .putString("${folderPath}|file", fileName)
            .putInt("${folderPath}|pos", positionMs)
            .commit()
    }

    fun getSavedFile(folderPath: String): String? {
        return prefs.getString("${folderPath}|file", null)
    }

    fun getSavedPosition(folderPath: String): Int {
        return prefs.getInt("${folderPath}|pos", 0)
    }

    /** 清空进度仅供 PlaybackService.clearProgress() 调用（播完整个文件夹时） */
    fun clearPosition(folderPath: String) {
        prefs.edit()
            .remove("${folderPath}|file")
            .remove("${folderPath}|pos")
            .apply()
    }

    // ===== 倍速 =====
    // 与进度同理：写权限只在 PlaybackService（setSpeed / 失败回退），Activity 只读 getSpeed()。
    // 倍速是全局设置，切曲、换文件夹都不重置。

    /** 当前版本支持的档位白名单。增删档位时同步 MainActivity.speedOptions */
    private val SUPPORTED_SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

    fun isSupportedSpeed(speed: Float): Boolean =
        SUPPORTED_SPEEDS.any { kotlin.math.abs(it - speed) < 0.01f }

    /** 写入倍速。只允许 PlaybackService.setSpeed() / 失败回退 / getSpeed() 的旧值规范化调用 */
    fun saveSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_PLAYBACK_SPEED, speed).commit()
    }

    /**
     * 读取倍速。若存档里是已下线的旧档位（例如 4.0）或非法值，规范化回 1× 并写回，
     * 否则界面会停在「没有任何档位高亮」的状态。这是 Store 内部的数据自愈，
     * 不违反「Service 写、Activity 读」的边界：Activity 仍然只读。
     */
    fun getSpeed(): Float {
        val v = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        if (isSupportedSpeed(v)) return v
        saveSpeed(1.0f)
        return 1.0f
    }

    // ===== 最近播放过的文件夹 =====

    /** 将文件夹路径添加到最近列表（移到最前面，去重，最多保留 MAX_RECENT_FOLDERS 个） */
    fun addRecentFolder(path: String) {
        val list = getRecentFolders().toMutableList()
        list.remove(path)          // 去重
        list.add(0, path)          // 最新的放最前面
        if (list.size > MAX_RECENT_FOLDERS) {
            list.subList(MAX_RECENT_FOLDERS, list.size).clear()
        }
        prefs.edit()
            .putString("recent_folders", list.joinToString("|"))
            .apply()
    }

    /** 获取最近播放过的文件夹列表（按时间倒序） */
    fun getRecentFolders(): List<String> {
        val raw = prefs.getString("recent_folders", null) ?: return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    /** 从最近列表中移除一个文件夹（文件夹被删时调用） */
    fun removeRecentFolder(path: String) {
        val list = getRecentFolders().toMutableList()
        list.remove(path)
        prefs.edit()
            .putString("recent_folders", list.joinToString("|"))
            .apply()
    }
}
