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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real walking-pace source from GPS (Fused Location Provider, Play Services).
 *
 * Emits instantaneous pace in mph derived from `Location.getSpeed()`.
 * If location permission was not granted at construction time, [pace] simply
 * stays `null` — there is NO simulated fallback; callers must treat `null`
 * as "unknown", never as fake data.
 */
class GpsPaceSource(context: Context) : PaceSource {
    private val appContext = context.applicationContext
    private val _pace = MutableStateFlow<Float?>(null)
    override val pace: StateFlow<Float?> = _pace.asStateFlow()

    private val client: FusedLocationProviderClient? =
        if (hasPermission()) LocationServices.getFusedLocationProviderClient(appContext) else null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val mps = if (loc.hasSpeed()) loc.speed else 0f
            val mph = mps * MPH_PER_MPS
            if (mph > 0f) _pace.value = mph
        }
    }

    init {
        start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        client ?: return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(2000L)
            .build()
        client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
    }

    fun stop() {
        client?.removeLocationUpdates(callback)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val MPH_PER_MPS = 2.23694f
    }
}