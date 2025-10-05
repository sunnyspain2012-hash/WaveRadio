package com.virk.waveradio

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class RadioViewModel : ViewModel() {
    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _visualizerData = MutableLiveData<ByteArray>()
    val visualizerData: LiveData<ByteArray> = _visualizerData

    fun setPlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun updateVisualizerData(data: ByteArray) {
        viewModelScope.launch {
            _visualizerData.value = data
        }
    }
}