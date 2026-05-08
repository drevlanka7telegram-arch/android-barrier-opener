package com.example.barrieropener

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import android.telephony.PhoneStateListener
import android.util.Log

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager
    private val prefs by lazy {
        getSharedPreferences("barrier", Context.MODE_PRIVATE)
    }
    private val historyPrefs by lazy {
        getSharedPreferences("barrier_history", Context.MODE_PRIVATE)
    }

    // Load from resources or saved prefs
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var radiusMeters: Double = 0.0
    private lateinit var phoneNumber: String
    private lateinit var ussdCode: String

    // UI
    private lateinit var btnOpen: MaterialButton
    private lateinit var btnChange: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvLastOpened: TextView
    private lateinit var tvDistance: TextView
    private var callActive = false

    // Permission launchers
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) startLocationCheck()
            else Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }

    private val mapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data?.data ?: return@registerForActivityResult
            try {
                // Parse geo URI: geo:lat,lng?q=...
                val uriString = data.toString()
                val latLngPattern = "geo:([0-9.-]+),([0-9.-]+)".toRegex()
                val match = latLngPattern.find(uriString)
                if (match != null) {
                    targetLat = match.groupValues[1].toDouble()
                    targetLng = match.groupValues[2].toDouble()
                    saveCoordinates()
                    updateStatus("Местоположение обновлено")
                    Toast.makeText(this, "Coordinates updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid coordinates format", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error parsing coordinates: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize settings from preferences or resources
        reloadSettings()

        // Load saved coordinates or defaults
        if (prefs.contains("lat") && prefs.contains("lng")) {
            targetLat = prefs.getFloat("lat", 61.748333f).toDouble()
            targetLng = prefs.getFloat("lng", 34.312777f).toDouble()
        } else {
            targetLat = 61.748333
            targetLng = 34.312777
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // UI elements
        btnOpen = findViewById(R.id.btn_open)
        btnChange = findViewById(R.id.btn_change_location)
        btnSettings = findViewById(R.id.btn_settings)
        tvStatus = findViewById(R.id.tv_status)
        tvLastOpened = findViewById(R.id.tv_last_opened)
        tvDistance = findViewById(R.id.tv_distance)

        // Update UI with current settings
        updateDistanceText()
        updateLastOpenedText()

        btnOpen.setOnClickListener {
            if (callActive) {
                Toast.makeText(this, "Call already in progress", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnOpen.isEnabled = false
            updateStatus("Проверка местоположения...")
            if (checkPermissions()) startLocationCheck() else requestPermissions()
        }

        btnChange.setOnClickListener {
            // Encode label for URI
            val label = URLEncoder.encode("Шлагбаум", "UTF-8")
            // Check if Google Maps is installed
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$targetLat,$targetLng($label)"))
            mapsIntent.setPackage("com.google.android.apps.maps")
            if (mapsIntent.resolveActivity(packageManager) != null) {
                mapPickerLauncher.launch(mapsIntent)
            } else {
                // Fallback: open any geo app
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$targetLat,$targetLng?q=$targetLat,$targetLng($label)"))
                if (fallback.resolveActivity(packageManager) != null) {
                    mapPickerLauncher.launch(fallback)
                } else {
                    Toast.makeText(this, "No maps application found", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Check if auto-open triggered from geofence
        if (intent.getBooleanExtra("auto_open", false)) {
            updateStatus("Авто-открытие по геозоне...")
            if (checkPermissions()) startLocationCheck() else requestPermissions()
        }

        // Auto-check permissions on start
        if (checkPermissions()) {
            updateStatus("Готов к работе")
        } else {
            requestPermissions()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("auto_open", false) == true) {
            updateStatus("Авто-открытие по геозоне...")
            if (checkPermissions()) startLocationCheck() else requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload settings in case they changed
        reloadSettings()
        updateDistanceText()
        updateLastOpenedText()
        
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val backgroundMode = defaultPrefs.getBoolean("background_mode", false)
        val helper = GeofenceHelper(this)

        if (backgroundMode) {
            // Check for background location permission before adding geofence
            if (hasBackgroundLocationPermission()) {
                val radius = radiusMeters.toFloat()
                helper.addGeofence(targetLat, targetLng, radius)
            } else {
                // Permission missing, disable mode to avoid silent failures
                defaultPrefs.edit().putBoolean("background_mode", false).apply()
                Toast.makeText(this, "Нет разрешения на фоновую геолокацию", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Remove geofence if mode is disabled
            helper.removeGeofence()
        }
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Below Q, FINE_LOCATION is enough
        }
    }

    private fun reloadSettings() {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        // Load from default shared preferences (set via SettingsActivity) or fallback to resources
        phoneNumber = defaultPrefs.getString("phone_number", getString(R.string.phone_number)) ?: getString(R.string.phone_number)
        ussdCode = defaultPrefs.getString("ussd_code", getString(R.string.ussd_code)) ?: getString(R.string.ussd_code)
        val radiusStr = defaultPrefs.getString("default_radius", getString(R.string.default_radius)) ?: getString(R.string.default_radius)
        radiusMeters = radiusStr.toDoubleOrNull() ?: 100.0
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            tvStatus.text = status
        }
    }

    private fun updateDistanceText() {
        // This will be updated when we get location
        tvDistance.text = "Радиус действия: ${radiusMeters.toInt()} м"
    }

    private fun updateLastOpenedText() {
        val lastTimestamp = historyPrefs.getLong("last_timestamp", 0L)
        if (lastTimestamp > 0) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val date = Date(lastTimestamp)
            tvLastOpened.text = "Последнее открытие: ${sdf.format(date)}"
            tvLastOpened.visibility = TextView.VISIBLE
        } else {
            tvLastOpened.text = "Последнее открытие: -"
            tvLastOpened.visibility = TextView.GONE
        }
    }

    private fun saveCoordinates() {
        prefs.edit()
            .putFloat("lat", targetLat.toFloat())
            .putFloat("lng", targetLng.toFloat())
            .apply()
    }

    private fun saveToHistory(lat: Double, lng: Double) {
        val timestamp = System.currentTimeMillis()
        val history = historyPrefs.getString("history", "") ?: ""
        val newEntry = "$timestamp|$lat|$lng"
        val entries = if (history.isEmpty()) mutableListOf() else history.split(";").toMutableList()
        entries.add(0, newEntry) // Add to beginning
        // Keep only last 10
        if (entries.size > 10) {
            entries.removeAt(entries.size - 1)
        }
        historyPrefs.edit()
            .putString("history", entries.joinToString(";"))
            .putLong("last_timestamp", timestamp)
            .apply()
        updateLastOpenedText()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
            )
        )
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationCheck() {
        updateStatus("Получение местоположения...")
        // First try last location
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    checkDistanceAndCall(location.latitude, location.longitude)
                } else {
                    // Request fresh location
                    requestFreshLocation()
                }
            }
            .addOnFailureListener {
                requestFreshLocation()
            }
    }

    private fun requestFreshLocation() {
        val request = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.requestLocationUpdates(
            request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        checkDistanceAndCall(location.latitude, location.longitude)
                    } else {
                        updateStatus("Ошибка геолокации")
                        btnOpen.isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            "Unable to get location",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
            mainLooper
        )
    }

    private fun checkDistanceAndCall(lat: Double, lng: Double) {
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(
            targetLat, targetLng,
            lat, lng,
            distance
        )
        val dist = distance[0]
        tvDistance.text = "До шлагбаума: ${dist.toInt()} м"
        if (dist <= radiusMeters) {
            updateStatus("Открываем шлагбаум...")
            makeCall()
        } else {
            updateStatus("Вы далеко от шлагбаума")
            btnOpen.isEnabled = true
            Toast.makeText(
                this,
                "You are ${dist.toInt()} m away from barrier. Need to be within ${radiusMeters.toInt()} m.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun makeCall() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "CALL_PHONE permission not granted", Toast.LENGTH_SHORT).show()
            btnOpen.isEnabled = true
            return
        }

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }
        try {
            startActivity(intent)
            callActive = true
            // Listen for call state to send USSD when call is active
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                registerCallStateListener()
            } else {
                Toast.makeText(this, "READ_PHONE_STATE permission missing, cannot send USSD", Toast.LENGTH_SHORT).show()
                btnOpen.isEnabled = true
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Cannot make call: ${e.message}", Toast.LENGTH_SHORT).show()
            btnOpen.isEnabled = true
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            btnOpen.isEnabled = true
        }
    }

    private var currentTelephonyCallback: Any? = null // Для хранения ссылки на callback (Android 12+)
    
    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Modern API (Android 12+)
            val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChange(state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
            currentTelephonyCallback = telephonyCallback // Сохраняем ссылку для последующей отмены
        } else {
            // Legacy API (pre-Android 12)
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private val phoneStateListener = object : android.telephony.PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallStateChange(state)
        }
    }

    private fun handleCallStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call is active, send USSD
                sendUssd()
                unregisterCallStateListener()
                callActive = false
                updateStatus("Шлагбаум открыт!")
                btnOpen.isEnabled = true
                // Save to history
                saveToHistory(targetLat, targetLng)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended, clean up
                unregisterCallStateListener()
                callActive = false
                updateStatus("Готов к работе")
                btnOpen.isEnabled = true
            }
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For modern API, unregister the callback we stored
            currentTelephonyCallback?.let { callback ->
                try {
                    telephonyManager.unregisterTelephonyCallback(callback as TelephonyCallback)
                    currentTelephonyCallback = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering TelephonyCallback: ${e.message}")
                }
            }
        } else {
            // Legacy API
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    private fun sendUssd() {
        // Format USSD code (replace # with URI-encoded %23 if needed)
        val ussdFormatted = ussdCode.replace("#", "%23")
        val ussdUri = Uri.parse("tel:$ussdFormatted")
        val ussdIntent = Intent(Intent.ACTION_CALL).apply {
            data = ussdUri
        }
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startActivity(ussdIntent)
                Toast.makeText(this, "USSD command sent", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Cannot send USSD: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "USSD error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}
