package org.zm.calculator

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LocationUploader {
    private const val TAG = "EpiLocUpload"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun push(
        ctx: Context,
        lat: Double,
        lng: Double,
        accuracy: Float?,
        event: String = "live",
        source: String = "android_native",
        at: Long = System.currentTimeMillis()
    ): Boolean {
        val id = Prefs.deviceId(ctx)
        val profile = Prefs.profile(ctx)
        val body = JSONObject()
            .put("name", profile["name"] ?: "—")
            .put("center", profile["center"] ?: "")
            .put("district", profile["district"] ?: "")
            .put("tehsil", profile["tehsil"] ?: "")
            .put("uc", profile["uc"] ?: "")
            .put("lat", lat)
            .put("lng", lng)
            .put("accuracy", if (accuracy != null) Math.round(accuracy) else JSONObject.NULL)
            .put("updatedAt", at)
            .put("deviceId", id)
            .put("source", source)
            .put("event", event)
            .toString()

        val okLive = put(FirebaseConfig.locationUrl(id), body)
        put(
            FirebaseConfig.activityUrl(id, at.toString()),
            JSONObject()
                .put("event", event)
                .put("lat", lat)
                .put("lng", lng)
                .put("accuracy", if (accuracy != null) Math.round(accuracy) else JSONObject.NULL)
                .put("updatedAt", at)
                .put("name", profile["name"] ?: "—")
                .put("center", profile["center"] ?: "")
                .put("source", source)
                .toString()
        )
        put(
            FirebaseConfig.historyUrl(id, at.toString()),
            JSONObject()
                .put("lat", lat)
                .put("lng", lng)
                .put("accuracy", if (accuracy != null) Math.round(accuracy) else JSONObject.NULL)
                .put("updatedAt", at)
                .put("source", source)
                .toString()
        )
        return okLive
    }

    fun flushQueue(ctx: Context): Int {
        val items = LocalLocationStore.snapshot(ctx)
        if (items.isEmpty()) return 0
        var n = 0
        for (o in items) {
            try {
                val lat = o.getDouble("lat")
                val lng = o.getDouble("lng")
                val acc = if (o.isNull("accuracy")) null else o.getDouble("accuracy").toFloat()
                val event = o.optString("event", "queued")
                val source = o.optString("source", "android_queue")
                val at = o.optLong("updatedAt", System.currentTimeMillis())
                if (push(ctx, lat, lng, acc, event, source, at)) n++
            } catch (e: Exception) {
                Log.w(TAG, "flush item", e)
            }
        }
        if (n > 0) LocalLocationStore.clear(ctx)
        return n
    }

    private fun put(url: String, body: String): Boolean {
        return try {
            val u = "$url?auth=${FirebaseConfig.API_KEY}"
            val req = Request.Builder()
                .url(u)
                .put(body.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) Log.w(TAG, "PUT $url -> ${resp.code}")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "PUT fail $url", e)
            false
        }
    }
}
