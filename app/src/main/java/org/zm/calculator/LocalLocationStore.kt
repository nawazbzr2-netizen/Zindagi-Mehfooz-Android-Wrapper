package org.zm.calculator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline queue of location/activity samples.
 * Filled while app may be closed (passive/last-known); uploaded when app opens.
 */
object LocalLocationStore {
    private const val PREF = "epi_loc_queue"
    private const val KEY = "pending"
    private const val MAX = 200

    fun enqueue(
        ctx: Context,
        lat: Double,
        lng: Double,
        accuracy: Float?,
        event: String,
        source: String
    ) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(p.getString(KEY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val o = JSONObject()
            .put("lat", lat)
            .put("lng", lng)
            .put("accuracy", if (accuracy != null) accuracy.toDouble() else JSONObject.NULL)
            .put("updatedAt", System.currentTimeMillis())
            .put("event", event)
            .put("source", source)
        arr.put(o)
        // keep last MAX
        val trimmed = JSONArray()
        val start = (arr.length() - MAX).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
        p.edit().putString(KEY, trimmed.toString()).apply()
    }

    fun snapshot(ctx: Context): List<JSONObject> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(p.getString(KEY, "[]"))
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    fun size(ctx: Context): Int = snapshot(ctx).size
}
