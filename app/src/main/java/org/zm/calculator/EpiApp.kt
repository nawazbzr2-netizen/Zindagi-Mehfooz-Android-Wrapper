package org.zm.calculator

import android.app.Application

class EpiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schedule low-power passive sampling (no continuous GPS)
        try {
            PassiveLocationWorker.schedule(this)
        } catch (_: Exception) {
        }
    }
}
