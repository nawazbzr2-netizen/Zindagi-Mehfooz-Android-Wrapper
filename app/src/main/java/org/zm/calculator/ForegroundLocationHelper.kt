package org.zm.calculator

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executors

/**
 * While app is open: rare low-power fixes → local queue + immediate upload attempt.
 * Stops when user leaves the screen.
 */
class ForegroundLocationHelper(private val ctx: Context) {

    companion object {
        private const val TAG = "EpiLocFg"
        private const val MIN_UPLOAD_MS = 120_000L
        private const val INTERVAL_MS = 180_000L // 3 min while open
    }

    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private val pool = Executors.newSingleThreadExecutor()
    private var lastUploadAt = 0L
    private var active = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onSample(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        active = true
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) onSample(
                    loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy else null
                )
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(120_000L)
                .setMinUpdateDistanceMeters(100f)
                .setMaxUpdateDelayMillis(300_000L)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            Log.i(TAG, "foreground low-power started")
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            active = false
        }
    }

    fun stop() {
        if (!active) return
        active = false
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) {}
    }

    private fun onSample(lat: Double, lng: Double, accuracy: Float?) {
        val now = System.currentTimeMillis()
        // Always keep a local copy
        LocalLocationStore.enqueue(ctx, lat, lng, accuracy, "app_open", "android_fg")
        if (now - lastUploadAt < MIN_UPLOAD_MS && lastUploadAt > 0) return
        lastUploadAt = now
        pool.execute {
            try {
                // Upload this point + flush any older queued samples
                LocationUploader.push(ctx.applicationContext, lat, lng, accuracy, "app_open", "android_fg")
                LocationUploader.flushQueue(ctx.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "upload", e)
            }
        }
    }
}
