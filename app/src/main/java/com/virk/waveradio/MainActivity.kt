package com.virk.waveradio

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.virk.waveradio.adapter.StationAdapter
import com.virk.waveradio.databinding.ActivityMainBinding
import com.virk.waveradio.model.RadioStation
import com.virk.waveradio.network.ApiClient
import com.virk.waveradio.network.ApiInterface
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stationAdapter: StationAdapter
    private var exoPlayer: ExoPlayer? = null
    private var currentStation: RadioStation? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        stationAdapter = StationAdapter { station ->
            currentStation = station
            startPlayback(station)
        }

        binding.recyclerView.apply {
            adapter = stationAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        fetchRadioStations()
        initializePlayer()

        binding.playButton.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                currentStation?.let { startPlayback(it) }
            }
        }
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.progressBar.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                isPlaying = (state == Player.STATE_READY) && (exoPlayer?.playWhenReady == true)
                updatePlayButton()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Stream error", Toast.LENGTH_SHORT).show()
                isPlaying = false
                updatePlayButton()
            }
        })
    }

    private fun fetchRadioStations() {
        lifecycleScope.launch {
            try {
                val api = ApiClient.client.create(ApiInterface::class.java)
                val response = api.getRadioStations()
                if (response.isSuccessful && response.body() != null) {
                    stationAdapter.updateStations(response.body()!!)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to load stations", Toast.LENGTH_SHORT).show()
                }
            } catch (e: HttpException) {
                Toast.makeText(this@MainActivity, "Network error", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(this@MainActivity, "No internet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPlayback(station: RadioStation) {
        currentStation = station
        binding.stationName.text = station.name
        binding.stationCountry.text = station.country

        exoPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(Uri.parse(station.streamUrl)))
            prepare()
            playWhenReady = true
            isPlaying = true
        }
        updatePlayButton()
    }

    private fun pausePlayback() {
        exoPlayer?.playWhenReady = false
        isPlaying = false
        updatePlayButton()
    }

    private fun updatePlayButton() {
        binding.playButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}