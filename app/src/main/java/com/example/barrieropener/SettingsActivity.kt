package com.example.barrieropener

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Display the fragment as the main content.
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
        supportActionBar?.setTitle("Настройки")
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var geofenceHelper: GeofenceHelper
        private val backgroundLocationLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                addGeofence()
            } else {
                findPreference<SwitchPreference>("background_mode")?.isChecked = false
                Toast.makeText(requireContext(), "Требуется разрешение на фоновую геолокацию", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            geofenceHelper = GeofenceHelper(requireContext())

            // Load current values from SharedPreferences (or defaults from resources)
            val prefs = preferenceManager.sharedPreferences!!
            val context = requireContext()

            // Phone number
            val phonePref = findPreference<EditTextPreference>("phone_number")
            val currentPhone = prefs?.getString("phone_number", context.getString(R.string.phone_number)) ?: ""
            phonePref?.text = currentPhone
            phonePref?.summary = if (currentPhone.isEmpty()) "Не задано" else currentPhone
            phonePref?.setOnPreferenceChangeListener { _, newValue ->
                prefs?.edit()?.putString("phone_number", newValue as String)?.apply()
                phonePref.summary = newValue as String
                true
            }

            // USSD code
            val ussdPref = findPreference<EditTextPreference>("ussd_code")
            val currentUssd = prefs?.getString("ussd_code", context.getString(R.string.ussd_code)) ?: ""
            ussdPref?.text = currentUssd
            ussdPref?.summary = if (currentUssd.isEmpty()) "Не задано" else currentUssd
            ussdPref?.setOnPreferenceChangeListener { _, newValue ->
                prefs?.edit()?.putString("ussd_code", newValue as String)?.apply()
                ussdPref.summary = newValue as String
                true
            }

            // Radius
            val radiusPref = findPreference<EditTextPreference>("default_radius")
            val currentRadius = prefs?.getString("default_radius", context.getString(R.string.default_radius)) ?: "100"
            radiusPref?.text = currentRadius
            radiusPref?.summary = "$currentRadius м"
            radiusPref?.setOnPreferenceChangeListener { _, newValue ->
                prefs?.edit()?.putString("default_radius", newValue as String)?.apply()
                radiusPref.summary = "$newValue м"
                true
            }

            // Current location display
            val locationPref = findPreference<Preference>("current_location")
            val mainPrefs = context.getSharedPreferences("barrier", Context.MODE_PRIVATE)
            val lat = mainPrefs.getFloat("lat", 61.748333f)
            val lng = mainPrefs.getFloat("lng", 34.312777f)
            locationPref?.summary = "Широта: $lat, Долгота: $lng"

            // Reset location
            findPreference<Preference>("reset_location")?.setOnPreferenceClickListener {
                mainPrefs.edit()
                    .putFloat("lat", 61.748333f)
                    .putFloat("lng", 34.312777f)
                    .apply()
                locationPref?.summary = "Широта: 61.748333, Долгота: 34.312777"
                Toast.makeText(context, "Координаты сброшены", Toast.LENGTH_SHORT).show()
                // Update geofence if background mode is on
                if (prefs?.getBoolean("background_mode", false) == true) {
                    addGeofence()
                }
                true
            }

            // History count
            val historyPrefs = context.getSharedPreferences("barrier_history", Context.MODE_PRIVATE)
            val history = historyPrefs.getString("history", "") ?: ""
            val count = if (history.isEmpty()) 0 else history.split(";").size
            findPreference<Preference>("history_count")?.summary = "$count записей"

            // Clear history
            findPreference<Preference>("clear_history")?.setOnPreferenceClickListener {
                historyPrefs.edit().clear().apply()
                findPreference<Preference>("history_count")?.summary = "0 записей"
                Toast.makeText(context, "История очищена", Toast.LENGTH_SHORT).show()
                true
            }

            // Background mode switch
            val backgroundModePref = findPreference<SwitchPreference>("background_mode")
            val isBackgroundMode = prefs?.getBoolean("background_mode", false) ?: false
            backgroundModePref?.isChecked = isBackgroundMode
            updateGeofenceStatus(isBackgroundMode)
            backgroundModePref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                prefs?.edit()?.putBoolean("background_mode", enabled)?.apply()
                if (enabled) {
                    // Check permissions and add geofence
                    if (hasBackgroundLocationPermission()) {
                        addGeofence()
                    } else {
                        // Request permission
                        requestBackgroundLocationPermission()
                        // We'll add geofence after permission granted
                    }
                } else {
                    removeGeofence()
                }
                true
            }
        }

        private fun hasBackgroundLocationPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // below Q, fine location is enough
            }
        }

        private fun requestBackgroundLocationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        private fun addGeofence() {
            val mainPrefs = requireContext().getSharedPreferences("barrier", Context.MODE_PRIVATE)
            val lat = mainPrefs.getFloat("lat", 61.748333f).toDouble()
            val lng = mainPrefs.getFloat("lng", 34.312777f).toDouble()
            val radius = prefs?.getString("default_radius", "100")?.toFloatOrNull() ?: 100f
            geofenceHelper.addGeofence(lat, lng, radius)
            updateGeofenceStatus(true)
            Toast.makeText(requireContext(), "Геозона активирована", Toast.LENGTH_SHORT).show()
        }

        private fun removeGeofence() {
            geofenceHelper.removeGeofence()
            updateGeofenceStatus(false)
            Toast.makeText(requireContext(), "Геозона деактивирована", Toast.LENGTH_SHORT).show()
        }

        private fun updateGeofenceStatus(active: Boolean) {
            findPreference<Preference>("geofence_status")?.summary = if (active) "Активна" else "Не активна"
        }
    }
}
