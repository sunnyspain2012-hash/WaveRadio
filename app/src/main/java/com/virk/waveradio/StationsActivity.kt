package com.virk.waveradio

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout

class StationsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: StationsAdapter
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stations)

        // MaterialToolbar with back arrow
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.stationsRecycler)
        tabLayout = findViewById(R.id.tabLayout)

        adapter = StationsAdapter { station ->
            StationsRepository.setLastStation(station.id)
            val resultIntent = Intent().apply { putExtra("stationId", station.id) }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recyclerView.adapter = adapter

        // Tabs
        listOf("All Stations", "Favorites", "Recent").forEach {
            tabLayout.addTab(tabLayout.newTab().setText(it))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                refreshList(when (tab.position) {
                    0 -> ListType.ALL_STATIONS
                    1 -> ListType.FAVORITES
                    2 -> ListType.RECENT
                    else -> ListType.ALL_STATIONS
                })
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        refreshList(ListType.ALL_STATIONS)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refreshList(listType: ListType) {
        val list = when (listType) {
            ListType.ALL_STATIONS -> StationsRepository.allStations
            ListType.FAVORITES -> StationsRepository.getFavorites()
            ListType.RECENT -> StationsRepository.getRecentStations()
        }
        adapter.submitList(list)

        val emptyText = findViewById<TextView>(R.id.emptyStateText)
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        emptyText.text = when (listType) {
            ListType.ALL_STATIONS -> "No stations available"
            ListType.FAVORITES -> "No favorite stations"
            ListType.RECENT -> "No recent stations"
        }
    }

    private enum class ListType { ALL_STATIONS, FAVORITES, RECENT }

    private inner class StationsAdapter(
        private val onClick: (Station) -> Unit
    ) : RecyclerView.Adapter<StationsAdapter.VH>() {

        private var list = listOf<Station>()

        fun submitList(newList: List<Station>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivIcon: ImageView = itemView.findViewById(R.id.stationIcon)
            val tvName: TextView = itemView.findViewById(R.id.stationName)
            val favButton: ImageButton = itemView.findViewById(R.id.itemFav)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val st = list[position]
            holder.tvName.text = st.name
            holder.ivIcon.setImageResource(st.iconRes ?: R.drawable.default_radio)
            holder.itemView.setOnClickListener { onClick(st) }

            val isFav = StationsRepository.isFavorite(st.id)
            holder.favButton.setImageResource(if (isFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            holder.favButton.setOnClickListener {
                StationsRepository.toggleFavorite(st.id)
                notifyItemChanged(position)
                if (tabLayout.selectedTabPosition == 1) refreshList(ListType.FAVORITES)
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
