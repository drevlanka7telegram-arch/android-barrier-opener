package com.example.barrieropener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }
        if (geofencingEvent.hasError()) {
            val errorMessage = "Geofence error: ${geofencingEvent.errorCode}"
            Log.e(TAG, errorMessage)
            return
        }

        // Get the transition type
        val geofenceTransition = geofencingEvent.geofenceTransition

        // Check if the transition type is enter
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.i(TAG, "Geofence entered")
            // Start the service to handle barrier opening
            val serviceIntent = Intent(context, GeofenceService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: ${e.message}")
                // Fallback: start activity directly
                try {
                    val activityIntent = Intent(context, MainActivity::class.java)
                    activityIntent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    activityIntent.putExtra("auto_open", true)
                    context.startActivity(activityIntent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to start activity: ${e2.message}")
                }
            }
        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "Geofence exited")
        }
    }
}
