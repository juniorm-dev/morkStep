package com.morkstep.sensing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.morkstep.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real walking-speed source from GPS (Fused Location Provider, Play Services).
 *
 * Emits instantaneous speed in mph derived from `Location.getSpeed()`.
 * If location permission was not granted at construction time, [speed] simply
 * stays `null` — there is NO simulated fallback; callers must treat `null`
 * as "unknown", never as fake data.
 */
class GpsSpeedSource(context: Context) : SpeedSource {
    private val appContext = context.applicationContext
    private val _speed = MutableStateFlow<Float?>(null)
    override val speed: StateFlow<Float?> = _speed.asStateFlow()

    private val client: FusedLocationProviderClient? =
        if (hasPermission()) LocationServices.getFusedLocationProviderClient(appContext) else null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val mps = if (loc.hasSpeed()) loc.speed else 0f
            val mph = mps * Constants.MPH_PER_MPS
            // Strictly-positive junk filter: reject zero/negative speed reads
            // (GPS standing-still noise) but keep showing real slow walking.
            if (mph > 0f) _speed.value = mph
        }
    }

    init {
        start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        client ?: return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, Constants.GPS_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(Constants.GPS_MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(Constants.GPS_MAX_UPDATE_DELAY_MS)
            .build()
        client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
    }

    fun stop() {
        client?.removeLocationUpdates(callback)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}