package com.morkstep

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.morkstep.data.PhaseType
import com.morkstep.engine.LiveState

/**
 * Foreground keep-alive for a running workout.
 *
 * Holds a partial wake lock so the 1 Hz engine ticker stays on schedule with
 * the screen locked (a sleeping CPU stretches ticks, which delays phase
 * transitions and makes audio cues miss their moment), and posts an ongoing
 * notification so the session keeps foreground priority — the app keeps
 * tracking while backgrounded and the user can see it is running.
 */
class WorkoutService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morkStep:workout")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(intent?.getBooleanExtra(EXTRA_SIMULATED, false) ?: false)
        // Safety timeout in case a stop is ever missed; sessions are bounded
        // by the workout plan and stopped explicitly on finish/discard.
        wakeLock?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
        return START_NOT_STICKY
    }

    /**
     * Start as a foreground service. Real-sensor sessions use only the sensor
     * types whose permissions are actually granted; when none is granted
     * (permissions missing, or simulated mode) the manifest's dataSync type is
     * used — it requires only INTERNET, so the service always stays foreground.
     * Any denial degrades to a plain service rather than crash the workout.
     */
    private fun startInForeground(simulated: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(this))
            return
        }
        var type = if (simulated) 0 else grantedTypes()
        if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(this), type)
        } catch (e: SecurityException) {
            Log.w(TAG, "foreground service type denied; running unguarded", e)
            stopSelf()
        }
    }

    /** location (GPS speed) + connectedDevice (BLE strap), subset to granted permissions. */
    private fun grantedTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        val locationOk =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (locationOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        } else {
            // Pre-S the connectedDevice type has no permission requirement.
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return type
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Workout session", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val TAG = "WorkoutService"
        const val CHANNEL_ID = "workout"
        private const val NOTIF_ID = 1
        /** True when the session uses simulated sensors (no real location/BLE source). */
        private const val EXTRA_SIMULATED = "simulated"

        /** Latest session state, written by [com.morkstep.ui.MainViewModel] once per engine tick. */
        @Volatile
        private var lastLive: LiveState? = null

        fun start(context: Context, simulated: Boolean) {
            val intent = Intent(context, WorkoutService::class.java)
                .putExtra(EXTRA_SIMULATED, simulated)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            lastLive = null
            context.stopService(Intent(context, WorkoutService::class.java))
        }

        /** Refresh the notification for the current engine snapshot. */
        fun update(context: Context, live: LiveState) {
            lastLive = live
            NotificationManagerCompat.from(context).notify(NOTIF_ID, buildNotification(context))
        }

        private fun buildNotification(context: Context): Notification {
            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("morkStep session running")
                .setContentText(describe(lastLive))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tap)
                .build()
        }

        private fun describe(live: LiveState?): String {
            if (live == null) return "Getting ready..."
            val phase = when (live.phase) {
                PhaseType.WARMUP -> "Warm-up"
                PhaseType.FAST -> "Push"
                PhaseType.SLOW -> "Recovery"
                PhaseType.COOLDOWN -> "Cool-down"
            }
            val min = live.totalSeconds / 60
            val sec = live.totalSeconds % 60
            val speed = live.speed?.let { String.format("%.1f", it) } ?: "-"
            val hr = live.hr?.toString() ?: "-"
            val pace = live.pace?.toString() ?: "-"
            val pause = if (live.paused) " · Paused" else ""
            return String.format("%s %d:%02d · speed %s mph · pace %s spm · HR %s%s", phase, min, sec, speed, pace, hr, pause)
        }
    }
}