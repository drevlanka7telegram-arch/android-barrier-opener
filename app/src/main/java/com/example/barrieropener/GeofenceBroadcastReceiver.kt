package com.example.barrieropener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
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
            context.startForegroundService(serviceIntent)
        } else if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "Geofence exited")
        }
    }
}
