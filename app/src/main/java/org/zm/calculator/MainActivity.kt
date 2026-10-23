package org.zm.calculator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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

    /** File chooser callback from WebView <input type="file"> */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * Default folder for Insert PDF files:
     * Android/data/org.seir.android.norad/files/download/
     * (Documents UI URI so the system picker opens there when possible)
     */
    private val defaultInsertFolderUri: Uri by lazy {
        // primary:Android/data/org.seir.android.norad/files/download
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/data/org.seir.android.norad/files/download"
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

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data!!
        val uris: Array<Uri>? = when {
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
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
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any previous pending callback
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf"))
                    // Open the NORAD / SEIR download folder by default
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, defaultInsertFolderUri)
                }

                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, "PDF فائلیں منتخب کریں")
                    )
                    true
                } catch (e: Exception) {
                    Log.e("EpiMain", "file chooser failed", e)
                    this@MainActivity.filePathCallback = null
                    filePathCallback?.onReceiveValue(null)
                    Toast.makeText(
                        this@MainActivity,
                        "فائل منتخب کرنے میں مسئلہ: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    false
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
                android.util.Log.i("EpiMain", "flushed queue on open: $n")
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
