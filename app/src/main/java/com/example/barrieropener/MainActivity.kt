package com.example.barrieropener

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.Toast
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager
    private val prefs by lazy {
        getSharedPreferences("barrier", Context.MODE_PRIVATE)
    }

    // Load from resources or saved prefs
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var radiusMeters: Double = 0.0
    private lateinit var phoneNumber: String
    private lateinit var ussdCode: String

    // UI
    private lateinit var btnOpen: Button
    private lateinit var btnChange: Button
    private lateinit var progressBar: ProgressBar
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

        // Initialize from resources
        phoneNumber = getString(R.string.phone_number)
        ussdCode = getString(R.string.ussd_code)
        radiusMeters = getString(R.string.default_radius).toDoubleOrNull() ?: 100.0

        // Load saved coordinates or defaults
        if (prefs.contains("lat") && prefs.contains("lng")) {
            targetLat = prefs.getFloat("lat", 61.7876f).toDouble()
            targetLng = prefs.getFloat("lng", 34.356f).toDouble()
        } else {
            targetLat = 61.7876
            targetLng = 34.356
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        btnOpen = findViewById(R.id.btn_open)
        btnChange = findViewById(R.id.btn_change_location)
        progressBar = findViewById(R.id.progress_bar)

        btnOpen.setOnClickListener {
            if (callActive) {
                Toast.makeText(this, "Call already in progress", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnOpen.isEnabled = false
            progressBar.visibility = ProgressBar.VISIBLE
            val anim = AnimationUtils.loadAnimation(this, R.anim.scale_anim)
            anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    btnOpen.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            it.startAnimation(anim)
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

        // Auto-check permissions on start
        if (checkPermissions()) startLocationCheck() else requestPermissions()
    }

    private fun saveCoordinates() {
        prefs.edit()
            .putFloat("lat", targetLat.toFloat())
            .putFloat("lng", targetLng.toFloat())
            .apply()
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
        if (distance[0] <= radiusMeters) {
            makeCall()
        } else {
            Toast.makeText(
                this,
                "You are ${distance[0].toInt()} m away from barrier. Need to be within ${radiusMeters.toInt()} m.",
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
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Cannot make call: ${e.message}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Modern API (Android 12+)
            val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChange(state)
                }
            }
            telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
        } else {
            // Legacy API (pre-Android 12)
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
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
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended, clean up
                unregisterCallStateListener()
                callActive = false
            }
        }
    }

    private fun unregisterCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For modern API, we need to keep track of the callback
            // In this simple case, we'll just note that we should re-register next time
        } else {
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
