package org.zm.calculator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var locHelper: ForegroundLocationHelper? = null
    private val io = Executors.newSingleThreadExecutor()
    private var splashShown = false

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

    private fun defaultReportsFolderUri(): Uri {
        val docId = "primary:Android/data/org.seir.android.norad/files/Download"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            docId
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val locOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locOk) {
            PassiveLocationWorker.schedule(this)
            locHelper?.start()
            flushQueueAsync()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        val splash = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            try {
                val ism = assets.open("splash.jpg")
                setImageBitmap(android.graphics.BitmapFactory.decodeStream(ism))
                ism.close()
            } catch (_: Exception) {
                setBackgroundColor(0xFF1e3a5f.toInt())
            }
        }
        root.addView(splash)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = android.view.View.GONE
        }
        root.addView(webView)

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
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val acceptType = fileChooserParams?.acceptTypes
                    ?.firstOrNull { it.isNotBlank() }
                    ?: "application/pdf"

                // Unlimited: always allow multiple PDF selection
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = acceptType
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, defaultReportsFolderUri())
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.w("EpiMain", "Primary chooser failed", e)
                    try {
                        val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        fileChooserLauncher.launch(Intent.createChooser(fallback, "Select PDF Report(s)"))
                        true
                    } catch (e2: Exception) {
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

        requestAllNeededPermissions()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!splashShown) {
                splashShown = true
                splash.animate().alpha(0f).setDuration(400).withEndAction {
                    root.removeView(splash)
                    webView.visibility = android.view.View.VISIBLE
                    webView.loadUrl("file:///android_asset/index.html")
                }.start()
            }
        }, 2500)

        PassiveLocationWorker.schedule(this)
    }

    private fun requestAllNeededPermissions() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        ) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (need.isNotEmpty()) {
            permissionLauncher.launch(need.toTypedArray())
        } else {
            PassiveLocationWorker.schedule(this)
            locHelper?.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (splashShown) {
            requestLocationIfNeeded()
            flushQueueAsync()
        }
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
        requestAllNeededPermissions()
    }

    fun ensurePermissionsAndStartTracking() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        requestLocationIfNeeded()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.visibility == android.view.View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
