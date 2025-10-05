package com.virk.waveradio

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object StationsRepository {
    private const val PREF_NAME = "radio_stations"
    private const val PREF_LAST_STATION = "last_station"
    private const val PREF_FAVORITES = "favorite_stations"
    private const val PREF_RECENT_STATIONS = "recent_stations"
    private const val MAX_RECENT_STATIONS = 50

    private lateinit var sharedPreferences: SharedPreferences
    private var _initialized = false

    val allStations = listOf(
        Station(
            id = "station1",
            name = "PBC Saut-ul-Quran",
            streamUrl = "https://whmsonic.radio.gov.pk:7002/stream?type=http&nocache=12",
            iconRes = R.drawable.pbc_logo
        ),
        Station(
            id = "station2",
            name = "PBC NCAC",
            streamUrl = "    https://whmsonic.radio.gov.pk:7004/stream?type=http&nocache=12",
            imageUrl = "    https://radio.gov.pk/site_logo.png    "
        ),
        Station(
            id = "station3",
            name = "PBC W-S",
            streamUrl = "https://whmsonic.radio.gov.pk:7005/stream?type=http&nocache=12",
            imageUrl = "    https://radio.gov.pk/site_logo.png    "
        ),
        Station(
            id = "station4",
            name = "PBC Islamabad Station",
            streamUrl = "https://whmsonic.radio.gov.pk:7003/stream?type=http&nocache=12",
            imageUrl = "    https://radio.gov.pk/site_logo.png    "
        ),
        Station(
            id = "station5",
            name = "PBC FM 101",
            streamUrl = "https://whmsonic.radio.gov.pk:7008/stream?type=http&nocache=12",
            imageUrl = "    https://onlineradiofm.in/assets/image/radio/180/fm101pakistan.jpg    "
        ),
        Station(
            id = "station6",
            name = "PBC E-S",
            streamUrl = "https://whmsonic.radio.gov.pk:7006/stream?type=http&nocache=12",
            imageUrl = "    https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQirCbRX4mzIPE4YmrQtuM-iljtAhxDHghP-Q&s"
        )
    )

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        _initialized = true

        // Ensure there's always a last station
        if (getLastStation() == null && allStations.isNotEmpty()) {
            setLastStation(allStations.first().id)
        }
    }

    val isInitialized: Boolean get() = this::sharedPreferences.isInitialized && _initialized

    fun getLastStation(): Station? {
        val lastId = sharedPreferences.getString(PREF_LAST_STATION, null)
        return allStations.find { it.id == lastId }
    }

    fun setLastStation(stationId: String) {
        sharedPreferences.edit { putString(PREF_LAST_STATION, stationId) }
        addToRecentStations(stationId)
    }

    fun isFavorite(stationId: String): Boolean {
        val favorites = sharedPreferences.getStringSet(PREF_FAVORITES, emptySet()) ?: emptySet()
        return favorites.contains(stationId)
    }

    fun toggleFavorite(stationId: String) {
        val currentFavorites = sharedPreferences.getStringSet(PREF_FAVORITES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (currentFavorites.contains(stationId)) {
            currentFavorites.remove(stationId)
        } else {
            currentFavorites.add(stationId)
        }
        sharedPreferences.edit { putStringSet(PREF_FAVORITES, currentFavorites) }
    }

    fun getFavorites(): List<Station> {
        val favorites = sharedPreferences.getStringSet(PREF_FAVORITES, emptySet()) ?: emptySet()
        return allStations.filter { favorites.contains(it.id) }
    }

    fun addToRecentStations(stationId: String) {
        val recentStations = getRecentStationIds().toMutableList()
        recentStations.remove(stationId)
        recentStations.add(0, stationId)
        val trimmedList = recentStations.take(MAX_RECENT_STATIONS)
        sharedPreferences.edit { putStringSet(PREF_RECENT_STATIONS, trimmedList.toSet()) }
    }

    fun getRecentStations(): List<Station> {
        val recentIds = getRecentStationIds()
        return recentIds.mapNotNull { id -> allStations.find { it.id == id } }
    }

    private fun getRecentStationIds(): List<String> {
        val recentSet = sharedPreferences.getStringSet(PREF_RECENT_STATIONS, emptySet()) ?: emptySet()
        return recentSet.toList()
    }

    fun clearRecentStations() {
        sharedPreferences.edit { remove(PREF_RECENT_STATIONS) }
    }

    fun clearFavorites() {
        sharedPreferences.edit { remove(PREF_FAVORITES) }
    }

    fun clearRecent() {
        sharedPreferences.edit {
            remove(PREF_RECENT_STATIONS)
            remove(PREF_LAST_STATION)
        }
    }

    fun clearAllData() {
        sharedPreferences.edit { clear() }
    }
}

// ✅ Updated Station data class to support remote image URLs
data class Station(
    val id: String,
    val name: String,
    val streamUrl: String,
    val iconRes: Int? = null,      // for local drawables (e.g., R.drawable.pbc_logo)
    val imageUrl: String? = null   // for remote image URLs (e.g., "https://...")
)