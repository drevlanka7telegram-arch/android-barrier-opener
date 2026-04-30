package com.example.barrieropener

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import android.view.animation.AnimationUtils
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val prefs by lazy {
        getSharedPreferences("barrier", Context.MODE_PRIVATE)
    }

    // Load from resources or saved prefs
    private var targetLat: Double
    private var targetLng: Double
    private var radiusMeters: Double
    private val phoneNumber: String

    init {
        // These will be initialized in onCreate because we need context for resources.
        targetLat = 0.0
        targetLng = 0.0
        radiusMeters = 0.0
        phoneNumber = ""
    }

    // UI
    private lateinit var btnOpen: Button
    private lateinit var btnChange: Button

    // Permission launchers
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) startLocationCheck()
        }

    private val mapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data?.data ?: return@registerForActivityResult
            // Expected URI like "geo:lat,lng?q=..."
            val schemeSpecific = data.schemeSpecificPart
            val latLngPart = schemeSpecific.removePrefix("//").split("?")[0]
            val parts = latLngPart.split(",")
            if (parts.size >= 2) {
                try {
                    targetLat = parts[0].toDouble()
                    targetLng = parts[1].toDouble()
                    saveCoordinates()
                    Toast.makeText(this, "Coordinates updated", Toast.LENGTH_SHORT).show()
                } catch (e: NumberFormatException) {
                    Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize from resources
        phoneNumber = getString(R.string.phone_number)
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

        btnOpen = findViewById(R.id.btn_open)
        btnChange = findViewById(R.id.btn_change_location)

        btnOpen.setOnClickListener {
            // Disable button to prevent multiple clicks
            btnOpen.isEnabled = false
            val anim = AnimationUtils.loadAnimation(this, R.anim.scale_anim)
            anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    btnOpen.isEnabled = true
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            it.startAnimation(anim)
            if (checkPermissions()) startLocationCheck() else requestPermissions()
        }

        btnChange.setOnClickListener {
            // Check if Google Maps is installed
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$targetLat,$targetLng(Шлагбаум)"))
            mapsIntent.setPackage("com.google.android.apps.maps")
            if (mapsIntent.resolveActivity(packageManager) != null) {
                mapPickerLauncher.launch(mapsIntent)
            } else {
                // Fallback: open any geo app
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$targetLat,$targetLng?q=$targetLat,$targetLng(Шлагбаум)"))
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
                Manifest.permission.CALL_PHONE
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
        } catch (e: SecurityException) {
            Toast.makeText(this, "Cannot make call: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
