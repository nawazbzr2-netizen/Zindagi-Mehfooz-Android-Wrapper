package org.zm.calculator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var locHelper: ForegroundLocationHelper? = null
    private val io = Executors.newSingleThreadExecutor()

    // File chooser for "Insert Daily Immunization Report" and
    // "Insert Children Defaulters Report". Tries to open the SEIR app's
    // Download folder by default so vaccinators don't have to navigate every time.
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && result.data != null) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    /** Preferred initial folder for the system file picker. */
    private fun defaultReportsFolderUri(): Uri {
        // SEIR / NoRAD reports are saved here on most devices.
        val docId = "primary:Android/data/org.seir.android.norad/files/Download"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId
        )
    }

    /** Fallback to public Downloads if the SEIR path is not accessible. */
    private fun publicDownloadsUri(): Uri {
        val docId = "primary:Download"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            PassiveLocationWorker.schedule(this)
            locHelper?.start()
            flushQueueAsync()
        } else {
            Toast.makeText(
                this,
                "لوکیشن اجازت دیں (While using the app) — پس منظر کی اجازت نہیں مانگی جاتی",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        locHelper = ForegroundLocationHelper(this)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setGeolocationEnabled(true)

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidEpi")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any previous pending callback
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val acceptType = fileChooserParams?.acceptTypes
                    ?.firstOrNull { it.isNotBlank() }
                    ?: "application/pdf"

                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                // Primary intent: open document picker pointed at SEIR Download folder
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = acceptType
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Try SEIR folder first (where reports are actually saved)
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, defaultReportsFolderUri())
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.w("EpiMain", "Primary file chooser failed, trying fallback", e)
                    // Fallback: plain GET_CONTENT without initial URI
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = acceptType
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                        }
                        fileChooserLauncher.launch(Intent.createChooser(fallback, "Select PDF Report"))
                        true
                    } catch (e2: Exception) {
                        Log.e("EpiMain", "File chooser completely failed", e2)
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        Toast.makeText(
                            this@MainActivity,
                            "فائل منتخب نہیں ہو سکی۔ براہ کرم دوبارہ کوشش کریں۔",
                            Toast.LENGTH_LONG
                        ).show()
                        false
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectProfileSync(view)
            }
        }

        webView.loadUrl("file:///android_asset/index.html")
        PassiveLocationWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        requestLocationIfNeeded()
        flushQueueAsync()
    }

    override fun onPause() {
        locHelper?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        locHelper?.stop()
        super.onDestroy()
    }

    private fun flushQueueAsync() {
        io.execute {
            try {
                val n = LocationUploader.flushQueue(applicationContext)
                Log.i("EpiMain", "flushed queue on open: $n")
            } catch (_: Exception) {
            }
        }
    }

    private fun injectProfileSync(view: WebView?) {
        val js = """
            (function(){
              try {
                if (window.AndroidEpi) {
                  var n = localStorage.getItem('epi_login_name') || '';
                  var c = localStorage.getItem('epi_login_center') || '';
                  var d = localStorage.getItem('epi_login_district') || '';
                  var t = localStorage.getItem('epi_login_tehsil') || '';
                  var u = localStorage.getItem('epi_login_uc') || '';
                  AndroidEpi.saveProfile(n, c, d, t, u);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    fun requestLocationIfNeeded() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
        else {
            PassiveLocationWorker.schedule(this)
            locHelper?.start()
        }
    }

    fun ensurePermissionsAndStartTracking() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        requestLocationIfNeeded()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
