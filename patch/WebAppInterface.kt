package org.zm.calculator

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

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
        activity.runOnUiThread {
            activity.ensurePermissionsAndStartTracking()
        }
    }

    @JavascriptInterface
    fun stopBackgroundLocation() {
    }

    @JavascriptInterface
    fun getNativeDeviceId(): String = Prefs.deviceId(activity)

    // Called by the page just before it opens the file chooser:
    // "daily" (Daily Immunization Reports) or "defaulters" (Children Defaulters
    // Reports). The app then picks only that kind of PDF from the reports folder.
    @JavascriptInterface
    fun setPickerKind(kind: String?) {
        activity.pickerKind = when (kind) {
            "daily", "defaulters" -> kind
            else -> "all"
        }
    }

    // Which folder the user chose in the 4-button chooser:
    // "seir" | "download" | "whatsapp" | "storage".
    @JavascriptInterface
    fun setPickerSource(src: String?) {
        activity.pickerSource = when (src) {
            "seir", "download", "whatsapp", "storage" -> src
            else -> "seir"
        }
    }

    // Plain-text share through the phone's native Share sheet (WhatsApp, SMS…).
    // Used to share several QR codes together, one per line.
    @JavascriptInterface
    fun shareText(text: String?, title: String?) {
        if (text.isNullOrBlank()) return
        activity.runOnUiThread {
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title ?: "QR Codes")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                activity.startActivity(Intent.createChooser(send, title ?: "Share"))
            } catch (e: Exception) {
                Toast.makeText(activity, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread {
            activity.finishAffinity()
        }
    }

    @JavascriptInterface
    fun downloadBase64(filename: String?, b64: String?, mime: String?) {
        if (filename.isNullOrBlank() || b64.isNullOrBlank()) return
        activity.runOnUiThread {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val safeName = filename.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                val mimeType = mime?.takeIf { it.isNotBlank() } ?: "application/pdf"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = activity.contentResolver.insert(collection, values)
                    if (uri != null) {
                        activity.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        activity.contentResolver.update(uri, values, null, null)
                        Toast.makeText(activity, "Downloaded: $safeName", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Download failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, safeName)
                    FileOutputStream(file).use { it.write(bytes) }
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    intent.data = Uri.fromFile(file)
                    activity.sendBroadcast(intent)
                    Toast.makeText(activity, "Downloaded: $safeName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @JavascriptInterface
    fun shareBase64(filename: String?, b64: String?, mime: String?, title: String?) {
        if (filename.isNullOrBlank() || b64.isNullOrBlank()) return
        activity.runOnUiThread {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val safeName = filename.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
                val mimeType = mime?.takeIf { it.isNotBlank() } ?: "application/pdf"

                val cacheFile = File(activity.cacheDir, safeName)
                FileOutputStream(cacheFile).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    cacheFile
                )

                val share = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title ?: safeName)
                    putExtra(Intent.EXTRA_TEXT, title ?: safeName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(share, title ?: "Share"))
            } catch (e: Exception) {
                Toast.makeText(activity, "Share error: ${e.message}", Toast.LENGTH_LONG).show()
                downloadBase64(filename, b64, mime)
            }
        }
    }
}
