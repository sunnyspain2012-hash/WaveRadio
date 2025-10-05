package com.virk.waveradio

import android.Manifest
import android.content.*
import android.media.AudioManager
import android.os.IBinder
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.virk.waveradio.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class RadioController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding
) {
    private var radioService: RadioService? = null
    private var isServiceBound = false
    private var isMuted = false
    private var lastVolume = 0
    val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var blinkingJob: Job? = null

    // If user selects a station while service is not yet bound, queue it here
    private var pendingStationId: String? = null

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                binding.playerControls.volumeSeekBar.progress = currentVolume
                updateMuteIcon()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    binding.playerControls.tvStatus.text = "Buffering"
                    binding.playerControls.tvStatus.visibility = View.VISIBLE
                    binding.playerControls.playPauseButton.setImageResource(R.drawable.ic_play)
                }
                Player.STATE_READY -> {
                    binding.playerControls.tvStatus.text = "Playing"
                    binding.playerControls.tvStatus.visibility = View.VISIBLE
                    updatePlayButton()
                }
                Player.STATE_ENDED -> {
                    binding.playerControls.tvStatus.text = "Ended"
                    binding.playerControls.tvStatus.visibility = View.VISIBLE
                    activity.showToast("Stream ended")
                    updatePlayButton()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
        }

        override fun onPlayerError(error: PlaybackException) {
            activity.showToast("Playback error: ${error.message}")
            radioService?.pause()
            binding.playerControls.tvStatus.text = "Error"
            binding.playerControls.tvStatus.visibility = View.VISIBLE
            updatePlayButton()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val title = mediaMetadata.title?.toString() ?: ""
            val artist = mediaMetadata.artist?.toString() ?: ""

            if (title.isNotEmpty() || artist.isNotEmpty()) {
                binding.metadataText.visibility = View.VISIBLE
                binding.metadataText.text = if (artist.isNotEmpty()) "$artist - $title" else title
                binding.metadataText.isSelected = true
            } else {
                binding.metadataText.visibility = View.GONE
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.LocalBinder
            radioService = binder.getService()
            isServiceBound = true

            radioService?.player?.addListener(playerListener)

            radioService?.visualizerData?.observe(activity as LifecycleOwner) { data ->
                binding.audioVisualizer.updateVisualizer(data)
            }

            radioService?.sleepTimerRemaining?.observe(activity as LifecycleOwner) { remaining ->
                updateSleepTimerIndicator(remaining)
            }

            // Improved station restoration
            ensureStationLoaded()

            // If user selected a station while binding, set & play it now
            pendingStationId?.let { id ->
                StationsRepository.setLastStation(id)
                binding.audioVisualizer.reset()
                radioService?.setStation(id, true)
                updateUIForStation(id)
                pendingStationId = null
            }

            updatePlayButton()
            showCurrentStation()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService?.player?.removeListener(playerListener)
            isServiceBound = false
            radioService = null
        }
    }

    fun bindService() {
        val intent = Intent(activity, RadioService::class.java)
        activity.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isServiceBound) {
            radioService?.player?.removeListener(playerListener)
            activity.unbindService(serviceConnection)
            isServiceBound = false
            radioService = null
            blinkingJob?.cancel()
            binding.audioVisualizer.reset()
        }
    }

    fun updateUI() {
        updatePlayButton()
        showCurrentStation()
    }

    fun loadVisualizerSettings() {
        val sharedPreferences = activity.getSharedPreferences()
        val sensitivity = sharedPreferences.getFloat("visualizer_sensitivity", 1.2f)
        val theme = sharedPreferences.getString("visualizer_theme", "Rainbow")
        binding.audioVisualizer.setSensitivity(sensitivity)
        theme?.let { binding.audioVisualizer.setTheme(it) }
    }

    fun checkAudioPermission(requestPermissionLauncher: ActivityResultLauncher<String>) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            bindService()
        }
    }

    fun togglePlayPause() {
        if (isServiceBound) {
            val currentStationId = radioService?.getCurrentStationId() ?: StationsRepository.getLastStation()?.id

            if (currentStationId == null && StationsRepository.allStations.isNotEmpty()) {
                // If no station is loaded, load the first station
                val stationToPlay = StationsRepository.getLastStation() ?: StationsRepository.allStations.first()
                StationsRepository.setLastStation(stationToPlay.id)
                radioService?.setStation(stationToPlay.id, true)
                updateUIForStation(stationToPlay.id)
            } else if (radioService?.isPlaying() == true) {
                radioService?.pause()
            } else {
                radioService?.play()
            }
        } else {
            checkAudioPermission(activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) bindService()
            })
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        if (isMuted) {
            lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            binding.playerControls.muteButton.setImageResource(R.drawable.ic_volume_off)
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, lastVolume, 0)
            binding.playerControls.muteButton.setImageResource(R.drawable.ic_volume_up)
        }
    }

    fun setVolume(progress: Int) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
        updateMuteIcon()
    }

    fun toggleFavorite() {
        val currentStationId = radioService?.getCurrentStationId() ?: StationsRepository.getLastStation()?.id
        if (currentStationId != null) {
            StationsRepository.toggleFavorite(currentStationId)
            updateFavIcon(currentStationId)
            activity.showToast(if (StationsRepository.isFavorite(currentStationId)) "Added to favorites" else "Removed from favorites")
        } else {
            activity.showToast("No station selected")
        }
    }

    fun switchStation(direction: Int) {
        val currentStationId = radioService?.getCurrentStationId() ?: StationsRepository.getLastStation()?.id
        val stations = StationsRepository.allStations
        if (currentStationId != null && stations.isNotEmpty()) {
            val currentIndex = stations.indexOfFirst { it.id == currentStationId }
            if (currentIndex != -1) {
                var newIndex = currentIndex + direction
                if (newIndex < 0) newIndex = stations.size - 1
                if (newIndex >= stations.size) newIndex = 0

                val newStation = stations[newIndex]
                StationsRepository.setLastStation(newStation.id)
                binding.audioVisualizer.reset()
                radioService?.setStation(newStation.id, true)
                updateUIForStation(newStation.id)
            }
        } else if (StationsRepository.allStations.isNotEmpty()) {
            val firstStation = StationsRepository.allStations[0]
            StationsRepository.setLastStation(firstStation.id)
            binding.audioVisualizer.reset()
            radioService?.setStation(firstStation.id, true)
            updateUIForStation(firstStation.id)
        }
    }

    /**
     * Public method used by the activity when a station is tapped in the list.
     * If service is bound, set+play immediately. If not bound, queue station and bind service.
     */
    fun selectStation(stationId: String) {
        val station = StationsRepository.allStations.find { it.id == stationId }
        if (station != null) {
            StationsRepository.setLastStation(station.id)
            binding.audioVisualizer.reset()
            if (isServiceBound) {
                radioService?.setStation(station.id, true)
                updateUIForStation(station.id)
            } else {
                // Queue station to be set after binding completes
                pendingStationId = station.id
                bindService()
            }
        } else {
            activity.showToast("Station not found")
        }
    }

    fun ensureStationLoaded() {
        if (isServiceBound) {
            val currentStationId = radioService?.getCurrentStationId()
            if (currentStationId == null && StationsRepository.allStations.isNotEmpty()) {
                val stationToLoad = StationsRepository.getLastStation() ?: StationsRepository.allStations.first()
                stationToLoad?.let { station ->
                    StationsRepository.setLastStation(station.id)
                    radioService?.setStation(station.id, false)
                    updateUIForStation(station.id)
                }
            }
        } else {
            // Ensure UI shows station info even if service not bound yet
            showCurrentStation()
        }
    }

    fun showSleepTimerDialog() {
        val options = arrayOf("Off", "15 min", "30 min", "45 min", "60 min", "90 min")
        AlertDialog.Builder(activity)
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        radioService?.cancelSleepTimer()
                        binding.playerControls.tvTimer.visibility = View.GONE
                        activity.showToast("Sleep timer cancelled")
                    }
                    else -> {
                        val minutes = when (which) {
                            1 -> 15; 2 -> 30; 3 -> 45; 4 -> 60; 5 -> 90; else -> 15
                        }
                        radioService?.startSleepTimer(minutes)
                        activity.showToast("Will stop after $minutes minutes")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateSleepTimerIndicator(remaining: Long) {
        if (remaining > 0) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(remaining) - TimeUnit.MINUTES.toSeconds(minutes)
            val mmss = String.format("%02d:%02d", minutes, seconds)
            binding.playerControls.tvTimer.text = mmss
            binding.playerControls.sleepTimerButton.contentDescription = "Sleep timer: $mmss"
            binding.playerControls.tvTimer.visibility = View.VISIBLE
            startBlinkingTimerIcon()
        } else {
            binding.playerControls.sleepTimerButton.contentDescription = "Sleep timer"
            binding.playerControls.tvTimer.visibility = View.GONE
            stopBlinkingTimerIcon()
        }
    }

    private fun startBlinkingTimerIcon() {
        blinkingJob?.cancel()
        blinkingJob = coroutineScope.launch {
            while (true) {
                binding.playerControls.sleepTimerButton.animate().alpha(0.3f).duration = 500
                delay(500)
                binding.playerControls.sleepTimerButton.animate().alpha(1.0f).duration = 500
                delay(500)
            }
        }
    }

    private fun stopBlinkingTimerIcon() {
        blinkingJob?.cancel()
        binding.playerControls.sleepTimerButton.alpha = 1.0f
    }

    private fun updateMuteIcon() {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol == 0 && !isMuted) {
            isMuted = true
            binding.playerControls.muteButton.setImageResource(R.drawable.ic_volume_off)
        } else if (currentVol > 0 && isMuted) {
            isMuted = false
            binding.playerControls.muteButton.setImageResource(R.drawable.ic_volume_up)
        }
    }

    private fun updatePlayButton() {
        val isPlaying = radioService?.isPlaying() ?: false
        binding.playerControls.playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (isPlaying) {
            binding.playerControls.tvStatus.text = "Playing"
            binding.playerControls.tvStatus.visibility = View.VISIBLE
        } else {
            binding.playerControls.tvStatus.text = "Paused"
            binding.playerControls.tvStatus.visibility = View.VISIBLE
        }
    }

    private fun updateFavIcon(stationId: String) {
        val fav = StationsRepository.isFavorite(stationId)
        binding.playerControls.favButton.setImageResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
    }

    private fun showCurrentStation() {
        val currentStationId = radioService?.getCurrentStationId() ?: StationsRepository.getLastStation()?.id
        updateUIForStation(currentStationId)
    }

    private fun updateUIForStation(stationId: String?) {
        val station = stationId?.let { id -> StationsRepository.allStations.find { it.id == id } }
        binding.playerControls.nowPlayingBar.visibility = View.VISIBLE

        if (station != null) {
            binding.playerControls.tvNowPlaying.text = station.name
            binding.stationLogo.setImageResource(station.iconRes ?: R.drawable.default_radio)
            updateFavIcon(station.id)
        } else {
            binding.playerControls.tvNowPlaying.text = "Select a station"
            binding.stationLogo.setImageResource(R.drawable.default_radio)
            binding.playerControls.favButton.setImageResource(R.drawable.ic_heart_outline)
        }
    }

    fun registerVolumeReceiver() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        activity.registerReceiver(volumeChangeReceiver, filter)
    }

    fun unregisterVolumeReceiver() {
        activity.unregisterReceiver(volumeChangeReceiver)
    }

    fun getRecentStations(): List<Station> {
        return StationsRepository.getRecentStations()
    }
}
