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

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val prefs by lazy {
        getSharedPreferences("barrier", Context.MODE_PRIVATE)
    }

    // Текущие координаты (по умолчанию)
    private var targetLat = 61.7876
    private var targetLng = 34.356
    private val radiusMeters = 100.0
    private val phoneNumber = "89114086713"

    // Запрос разрешений
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) startLocationCheck()
        }

    // Запрос карты (выбор точки)
    private val mapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data?.data ?: return@registerForActivityResult
            // Ожидаем URI вида “geo:lat,lng?q=…”
            val latLng = data.schemeSpecificPart
                .removePrefix("//")
                .split("?")[0]
                .split(",")
            if (latLng.size >= 2) {
                targetLat = latLng[0].toDouble()
                targetLng = latLng[1].toDouble()
                // Сохраняем в SharedPreferences
                prefs.edit()
                    .putFloat("lat", targetLat.toFloat())
                    .putFloat("lng", targetLng.toFloat())
                    .apply()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Читаем сохранённые координаты (если есть)
        if (prefs.contains("lat") && prefs.contains("lng")) {
            targetLat = prefs.getFloat("lat", targetLat.toFloat()).toDouble()
            targetLng = prefs.getFloat("lng", targetLng.toFloat()).toDouble()
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContentView(R.layout.activity_main)

        // Кнопка «Открыть шлагбаум»
        findViewById<Button>(R.id.btn_open).setOnClickListener {
            // анимация
            val anim = AnimationUtils.loadAnimation(this, R.anim.scale_anim)
            it.startAnimation(anim)
            // проверка гео-радиуса и вызов
            if (checkPermissions()) startLocationCheck() else requestPermissions()
        }

        // Кнопка «Изменить местоположение шлагбаума»
        findViewById<Button>(R.id.btn_change_location).setOnClickListener {
            // Открываем Google Maps в режиме выбора точки
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$targetLat,$targetLng(Шлагбаум)"))
                .setPackage("com.google.android.apps.maps")
            mapPickerLauncher.launch(intent)
        }

        // Автопроверка прав при старте
        if (checkPermissions()) startLocationCheck() else requestPermissions()
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
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                location?.let {
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        targetLat, targetLng,
                        it.latitude, it.longitude,
                        distance
                    )
                    if (distance[0] <= radiusMeters) makeCall()
                }
            }
    }

    private fun makeCall() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
        }
        startActivity(intent)
    }
}