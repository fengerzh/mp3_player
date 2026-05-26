package com.buzz.mp3player

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object PlaybackStore {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
    }

    fun saveFolderPath(path: String) {
        prefs.edit().putString("saved_folder_path", path).apply()
    }

    fun getSavedFolderPath(): String? {
        return prefs.getString("saved_folder_path", null)
    }

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

    fun clearPosition(folderPath: String) {
        prefs.edit()
            .remove("${folderPath}|file")
            .remove("${folderPath}|pos")
            .apply()
    }
}