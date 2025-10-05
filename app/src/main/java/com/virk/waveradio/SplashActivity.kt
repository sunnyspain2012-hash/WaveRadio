package com.virk.waveradio

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install Material 3 SplashScreen (prevents white flash)
        val splashScreen: SplashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        // Optionally, keep the splash for a short duration
        splashScreen.setKeepOnScreenCondition { false }

        // Delay for 2 seconds then navigate
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}
