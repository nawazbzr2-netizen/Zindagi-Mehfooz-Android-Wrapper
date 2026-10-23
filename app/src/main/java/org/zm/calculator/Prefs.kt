package org.zm.calculator

import android.content.Context

object Prefs {
    private const val NAME = "epi_native"

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        var id = p.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = "d_android_" + System.currentTimeMillis().toString(36) +
                "_" + (1000..9999).random()
            p.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun saveProfile(
        ctx: Context,
        name: String,
        center: String,
        district: String,
        tehsil: String,
        uc: String
    ) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("name", name)
            .putString("center", center)
            .putString("district", district)
            .putString("tehsil", tehsil)
            .putString("uc", uc)
            .apply()
    }

    fun profile(ctx: Context): Map<String, String> {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return mapOf(
            "name" to (p.getString("name", "") ?: ""),
            "center" to (p.getString("center", "") ?: ""),
            "district" to (p.getString("district", "") ?: ""),
            "tehsil" to (p.getString("tehsil", "") ?: ""),
            "uc" to (p.getString("uc", "") ?: "")
        )
    }

    fun setTrackingEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("tracking", on).apply()
    }

    fun isTrackingEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean("tracking", true)
}
