package com.virk.waveradio.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.virk.waveradio.MainActivity
import com.virk.waveradio.R

class RadioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        val session = MediaSession.Builder(this, player).build()
        mediaSession = session
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "radio_playback_channel"
        const val NOTIFICATION_ID = 1001
    }
}