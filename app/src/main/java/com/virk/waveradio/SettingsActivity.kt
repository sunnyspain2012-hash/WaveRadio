package com.virk.waveradio

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Setup top app bar back button
        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        // Initialize repository if needed
        if (!StationsRepository.isInitialized) {
            StationsRepository.init(applicationContext)
        }

        // Button Actions
        findViewById<Button>(R.id.btnClearFavorites).setOnClickListener {
            clearData { StationsRepository.clearFavorites() }
        }

        findViewById<Button>(R.id.btnClearRecent).setOnClickListener {
            clearData { StationsRepository.clearRecent() }
        }

        findViewById<Button>(R.id.btnClearAll).setOnClickListener {
            clearData { StationsRepository.clearAllData() }
        }

        // Visualizer Settings
        findViewById<Button>(R.id.btnVisualizerSettings).setOnClickListener {
            showVisualizerSettingsDialog()
        }

        // Visualizer Themes
        findViewById<Button>(R.id.btnVisualizerThemes).setOnClickListener {
            showVisualizerThemeSelector()
        }
    }

    /** Reusable method for clearing data safely */
    private fun clearData(action: () -> Unit) {
        try {
            action()
            Toast.makeText(this, "Action successful", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows a slider dialog for adjusting visualizer sensitivity */
    private fun showVisualizerSettingsDialog() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val currentSensitivity = prefs.getFloat("visualizer_sensitivity", 1.2f)

        val slider = Slider(this).apply {
            valueFrom = 0.1f
            valueTo = 2.0f
            stepSize = 0.1f
            value = currentSensitivity.coerceIn(valueFrom, valueTo)

            // Real-time updates while dragging
            addOnChangeListener { _, newValue, _ ->
                prefs.edit { putFloat("visualizer_sensitivity", newValue) }
                sendSensitivityBroadcast(newValue)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Visualizer Sensitivity")
            .setView(slider)
            .setPositiveButton("OK") { _, _ ->
                val newValue = slider.value
                prefs.edit { putFloat("visualizer_sensitivity", newValue) }
                sendSensitivityBroadcast(newValue)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sends a broadcast to update sensitivity */
    private fun sendSensitivityBroadcast(value: Float) {
        sendBroadcast(Intent("com.virk.waveradio.SENSITIVITY_CHANGED").apply {
            putExtra("new_sensitivity", value)
        })
    }

    /** Shows theme selector for visualizer */
    private fun showVisualizerThemeSelector() {
        val themes = arrayOf("Rainbow", "Ocean", "Sunset", "Forest", "Neon", "Fire")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Visualizer Theme")
            .setItems(themes) { _, which ->
                val selectedTheme = themes[which]
                try {
                    getSharedPreferences("app_settings", MODE_PRIVATE).edit {
                        putString("visualizer_theme", selectedTheme)
                    }
                    sendThemeBroadcast(selectedTheme)
                    Toast.makeText(this, "Theme set to: $selectedTheme", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error saving theme", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Sends a broadcast to update theme */
    private fun sendThemeBroadcast(theme: String) {
        sendBroadcast(Intent("com.virk.waveradio.THEME_CHANGED").apply {
            putExtra("selected_theme", theme)
        })
    }
}
