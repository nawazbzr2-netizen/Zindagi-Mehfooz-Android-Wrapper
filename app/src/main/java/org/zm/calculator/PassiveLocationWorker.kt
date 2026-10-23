package org.zm.calculator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Low-battery background sample (~every 15 min, OS may delay):
 * 1) Prefer last-known location (no GPS wake → almost no battery)
 * 2) Else one soft getCurrentLocation (may fail in background — OK)
 * 3) Save to LOCAL queue only — Firebase upload happens when user opens app
 *
 * No ACCESS_BACKGROUND_LOCATION, no Foreground Service.
 */
class PassiveLocationWorker(
    ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    companion object {
        private const val TAG = "EpiPassiveLoc"
        private const val UNIQUE = "epi_passive_location"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PassiveLocationWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            Log.i(TAG, "periodic passive location scheduled")
        }
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!hasLocationPermission(ctx)) {
            Log.i(TAG, "no location permission — skip")
            return Result.success()
        }
        try {
            val sample = readCheapLocation(ctx) ?: return Result.success()
            LocalLocationStore.enqueue(
                ctx,
                sample.first,
                sample.second,
                sample.third,
                "passive",
                "android_passive"
            )
            Log.i(TAG, "queued passive sample size=${LocalLocationStore.size(ctx)}")
        } catch (e: Exception) {
            Log.w(TAG, "worker", e)
        }
        return Result.success()
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun readCheapLocation(ctx: Context): Triple<Double, Double, Float?>? {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        try {
            val last = Tasks.await(fused.lastLocation, 8, TimeUnit.SECONDS)
            if (last != null && (System.currentTimeMillis() - last.time) < 2 * 60 * 60 * 1000L) {
                return Triple(
                    last.latitude,
                    last.longitude,
                    if (last.hasAccuracy()) last.accuracy else null
                )
            }
        } catch (_: Exception) {
        }
        return try {
            val cts = CancellationTokenSource()
            val loc = Tasks.await(
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token),
                15,
                TimeUnit.SECONDS
            )
            if (loc != null) Triple(
                loc.latitude,
                loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy else null
            ) else null
        } catch (_: Exception) {
            null
        }
    }
}
