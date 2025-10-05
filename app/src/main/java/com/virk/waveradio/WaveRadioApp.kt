package com.virk.waveradio

import android.app.Application

class WaveRadioApp : Application() {
    companion object {
        lateinit var instance: WaveRadioApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        StationsRepository.init(this) // <-- initialize repository
    }
}