package com.virk.waveradio.model

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val genre: String
)