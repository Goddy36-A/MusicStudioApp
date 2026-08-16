package com.musicstudio.app

import android.app.Application
import android.media.AudioManager
import androidx.core.content.getSystemService

class MusicStudioApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Request audio focus and configure for low-latency recording + playback
        val am = getSystemService<AudioManager>()
        am?.mode = AudioManager.MODE_NORMAL
        am?.isSpeakerphoneOn = false
    }
}
