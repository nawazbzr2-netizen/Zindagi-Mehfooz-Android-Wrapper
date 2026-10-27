package org.zm.calculator

/**
 * Same Firebase Realtime Database as the web app (epi-calculator).
 * REST write does not need the web API key for public test-mode rules;
 * still sent as query param for compatibility.
 */
object FirebaseConfig {
    const val DATABASE_URL =
        "https://epi-calculator-default-rtdb.firebaseio.com"
    const val API_KEY = "AIzaSyCHLKnOE-g6Ec-9vaLL2AWakK3flFRV5s8"

    fun locationUrl(deviceId: String): String =
        "$DATABASE_URL/locations/$deviceId.json"

    fun activityUrl(deviceId: String, key: String): String =
        "$DATABASE_URL/locationActivity/$deviceId/$key.json"

    fun historyUrl(deviceId: String, key: String): String =
        "$DATABASE_URL/locationHistory/$deviceId/$key.json"
}
