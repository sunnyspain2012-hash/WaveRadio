package com.virk.waveradio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Binder
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A background service for streaming internet radio with audio visualization support.
 */
class RadioService : Service() {

    // === CORE COMPONENTS ===
    private var exoPlayer: ExoPlayer? = null
    private var visualizer: Visualizer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // === LIVE DATA FOR UI OBSERVATION ===
    private val _visualizerData = MutableLiveData<ByteArray>()
    val visualizerData: MutableLiveData<ByteArray> get() = _visualizerData

    // === SLEEP TIMER ===
    private val _sleepTimerRemaining = MutableLiveData<Long>(0)
    val sleepTimerRemaining: MutableLiveData<Long> get() = _sleepTimerRemaining
    private var sleepTimer: CountDownTimer? = null

    // === STATE ===
    private var currentStationId: String? = null

    // === PUBLIC ACCESSORS ===
    val player: ExoPlayer?
        get() = exoPlayer

    // === BINDER FOR LOCAL COMMUNICATION ===
    inner class LocalBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    // === SERVICE LIFECYCLE ===
    override fun onCreate() {
        super.onCreate()
        initializePlayer()
        Log.d("RadioService", "Service created")
    }

    private fun initializePlayer() {
        try {
            // Configure OkHttp client for better streaming compatibility
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            exoPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    // Initialize audio focus
                    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setOnAudioFocusChangeListener { focusChange ->
                                // Handle audio focus changes if needed
                                when (focusChange) {
                                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                                    AudioManager.AUDIOFOCUS_GAIN -> play()
                                }
                            }
                            .setAcceptsDelayedFocusGain(true)
                            .build()

                        audioFocusRequest?.let {
                            audioManager?.requestAudioFocus(it)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager?.requestAudioFocus(
                            { focusChange ->
                                when (focusChange) {
                                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                                    AudioManager.AUDIOFOCUS_GAIN -> play()
                                }
                            },
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN
                        )
                    }

                    // Add player listener for state changes
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    Log.d("RadioService", "Player ready")
                                    setupVisualizer()
                                }
                                Player.STATE_BUFFERING -> Log.d("RadioService", "Player buffering")
                                Player.STATE_ENDED -> {
                                    Log.d("RadioService", "Player ended")
                                    stopSelf()
                                }
                                Player.STATE_IDLE -> Log.d("RadioService", "Player idle")
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e("RadioService", "Player error: ${error.message}")
                            error.cause?.printStackTrace()
                        }

                        override fun onAudioSessionIdChanged(audioSessionId: Int) {
                            Log.d("RadioService", "Audio session changed: $audioSessionId")
                            if (audioSessionId != 0) {
                                setupVisualizer()
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Log.d("RadioService", "Playing state changed: $isPlaying")
                        }
                    })
                }
        } catch (e: Exception) {
            Log.e("RadioService", "Error initializing player: ${e.message}")
        }
    }

    private fun setupVisualizer() {
        try {
            // Release previous visualizer
            visualizer?.release()
            visualizer = null

            val sessionId = exoPlayer?.audioSessionId ?: 0
            if (sessionId == 0) {
                Log.w("RadioService", "Invalid audio session ID")
                return
            }

            visualizer = Visualizer(sessionId).apply {
                // Get maximum capture size for better frequency resolution
                val captureSizeRange = Visualizer.getCaptureSizeRange()
                val maxCaptureSize = captureSizeRange[1]
                captureSize = maxCaptureSize

                // Set data capture listener for FFT data
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Not used - we only need FFT data
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Post FFT data to LiveData for UI consumption
                            fft?.let {
                                _visualizerData.postValue(it)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2, // ~10-12 FPS for performance
                    false, // disable waveform capture
                    true   // enable FFT capture
                )

                enabled = true
                Log.d("RadioService", "Visualizer enabled for session: $sessionId")
            }
        } catch (e: Exception) {
            Log.e("RadioService", "Error setting up visualizer: ${e.message}")
            // Visualizer may fail on some devices - app should degrade gracefully
        }
    }

    // === PUBLIC CONTROL METHODS ===
    fun setStation(stationId: String, playImmediately: Boolean = true) {
        val station = StationsRepository.allStations.find { it.id == stationId }
        station?.let { st ->
            currentStationId = stationId
            exoPlayer?.let { player ->
                try {
                    player.stop()
                    player.clearMediaItems()

                    val mediaItem = MediaItem.fromUri(st.streamUrl)
                    player.setMediaItem(mediaItem)
                    player.prepare()

                    if (playImmediately) {
                        player.play()
                    }

                    // Clear visualizer data during transition
                    _visualizerData.postValue(ByteArray(0))

                    Log.d("RadioService", "Station set: ${st.name}")
                } catch (e: Exception) {
                    Log.e("RadioService", "Error setting station: ${e.message}")
                }
            }
        } ?: run {
            Log.e("RadioService", "Station not found: $stationId")
        }
    }

    fun getCurrentStationId(): String? = currentStationId

    fun play() {
        try {
            exoPlayer?.play()
            Log.d("RadioService", "Play called")
        } catch (e: Exception) {
            Log.e("RadioService", "Error playing: ${e.message}")
        }
    }

    fun pause() {
        try {
            exoPlayer?.pause()
            Log.d("RadioService", "Pause called")
        } catch (e: Exception) {
            Log.e("RadioService", "Error pausing: ${e.message}")
        }
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    // === SLEEP TIMER LOGIC ===
    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()

        val millis = TimeUnit.MINUTES.toMillis(minutes.toLong())
        _sleepTimerRemaining.value = millis

        sleepTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _sleepTimerRemaining.postValue(millisUntilFinished)
            }

            override fun onFinish() {
                _sleepTimerRemaining.postValue(0)
                pause()
                Log.d("RadioService", "Sleep timer finished")
            }
        }.start()

        Log.d("RadioService", "Sleep timer started: $minutes minutes")
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        _sleepTimerRemaining.postValue(0)
        Log.d("RadioService", "Sleep timer cancelled")
    }

    // === SERVICE BINDING ===
    override fun onBind(intent: Intent?): IBinder {
        Log.d("RadioService", "Service bound")
        return LocalBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("RadioService", "Service unbound")
        return super.onUnbind(intent)
    }

    // === CLEANUP ===
    override fun onDestroy() {
        Log.d("RadioService", "Service destroyed")

        // Cancel sleep timer
        cancelSleepTimer()

        // Release audio focus
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager?.abandonAudioFocusRequest(it)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("RadioService", "Error releasing audio focus: ${e.message}")
        }

        // Release visualizer
        try {
            visualizer?.release()
        } catch (e: Exception) {
            Log.e("RadioService", "Error releasing visualizer: ${e.message}")
        }

        // Release player
        try {
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.e("RadioService", "Error releasing player: ${e.message}")
        }

        super.onDestroy()
    }
}