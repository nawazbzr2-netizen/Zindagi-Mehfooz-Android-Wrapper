package org.zm.calculator

import android.webkit.JavascriptInterface

class WebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun saveProfile(
        name: String?,
        center: String?,
        district: String?,
        tehsil: String?,
        uc: String?
    ) {
        Prefs.saveProfile(
            activity,
            name ?: "",
            center ?: "",
            district ?: "",
            tehsil ?: "",
            uc ?: ""
        )
    }

    @JavascriptInterface
    fun startBackgroundLocation() {
        // JS name kept — does NOT use background GPS (battery-friendly)
        activity.runOnUiThread {
            activity.ensurePermissionsAndStartTracking()
        }
    }

    @JavascriptInterface
    fun stopBackgroundLocation() {
    }

    @JavascriptInterface
    fun getNativeDeviceId(): String = Prefs.deviceId(activity)
}
