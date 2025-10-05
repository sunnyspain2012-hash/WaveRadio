package com.virk.waveradio.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.virk.waveradio.R
import com.virk.waveradio.model.RadioStation

class StationAdapter(
    private val onStationClick: (RadioStation) -> Unit
) : RecyclerView.Adapter<StationAdapter.StationViewHolder>() {

    private var stations = listOf<RadioStation>()

    fun updateStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(stations[position])
    }

    override fun getItemCount() = stations.size

    inner class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.stationItemName)
        private val countryText: TextView = itemView.findViewById(R.id.stationItemCountry)

        fun bind(station: RadioStation) {
            nameText.text = station.name
            countryText.text = station.country
            itemView.setOnClickListener { onStationClick(station) }
        }
    }
}