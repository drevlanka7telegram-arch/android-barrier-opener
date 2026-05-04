package com.example.barrieropener

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*

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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Load current values from SharedPreferences (or defaults from resources)
            val prefs = preferenceManager.sharedPreferences
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
            val lat = mainPrefs.getFloat("lat", 61.7876f)
            val lng = mainPrefs.getFloat("lng", 34.356f)
            locationPref?.summary = "Широта: $lat, Долгота: $lng"

            // Reset location
            findPreference<Preference>("reset_location")?.setOnPreferenceClickListener {
                mainPrefs.edit()
                    .putFloat("lat", 61.7876f)
                    .putFloat("lng", 34.356f)
                    .apply()
                locationPref?.summary = "Широта: 61.7876, Долгота: 34.356"
                Toast.makeText(context, "Координаты сброшены", Toast.LENGTH_SHORT).show()
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
        }
    }
}
