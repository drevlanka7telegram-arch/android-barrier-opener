package com.example.barrieropener

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceHelper(private val context: Context) {

    companion object {
        const val TAG = "GeofenceHelper"
        const val GEOFENCE_ID = "barrier_geofence"
    }

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    fun addGeofence(latitude: Double, longitude: Double, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(latitude, longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        try {
            geofencingClient.addGeofences(request, geofencePendingIntent).run {
                addOnSuccessListener {
                    Log.i(TAG, "Geofence added successfully")
                }
                addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofence: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
        }
    }

    fun removeGeofence() {
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener {
                Log.i(TAG, "Geofence removed successfully")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofence: ${e.message}")
            }
        }
    }
}
