package com.buzz.mp3player

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaybackStore.init(this)
    }
}