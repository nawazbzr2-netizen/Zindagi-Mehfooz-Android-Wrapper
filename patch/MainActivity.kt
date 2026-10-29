package org.zm.calculator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        // Picture shown first when the app opens.
        private const val SPLASH_MS = 3500L

        // Where the SEIR app saves the vaccinator's reports.
        private const val REPORTS_PKG = "org.seir.android.norad"

        private const val PREFS = "epi_picker"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var splash: ImageView
    private var locHelper: ForegroundLocationHelper? = null
    private val io = Executors.newSingleThreadExecutor()

    // ---- splash / page-start state -------------------------------------
    private var splashTimerDone = false
    private var splashGone = false
    private var pageStarted = false
    private var pageFinished = false

    // "daily" | "defaulters" | "all" — set from the web page just before it
    // opens the file chooser, so each Insert button only takes its own PDFs.
    @Volatile
    var pickerKind: String = "all"

    // "seir" | "download" | "whatsapp" | "storage" — which folder the user chose.
    @Volatile
    var pickerSource: String = "seir"

    private val treeAskedThisSession = HashSet<String>()
    private var pendingTreeKey: String? = null

    // ---- file chooser ---------------------------------------------------
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        val arr = if (uris.isEmpty()) null else uris.toTypedArray()
        fileChooserCallback?.onReceiveValue(arr)
        fileChooserCallback = null
    }

    // One-time folder permission (Android 11+ blocks direct access to some
    // folders, so the folder is granted once and remembered per source).
    private val treeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val key = pendingTreeKey
        var done = false
        if (result.resultCode == RESULT_OK && uri != null && key != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("EpiMain", "persist tree permission failed", e)
            }
            prefs().edit().putString(key, uri.toString()).apply()
            val list = listTreeFiles(uri)
            if (list != null && list.isNotEmpty()) {
                deliverAutoPicked(list)
                done = true
            }
        }
        if (!done) openManualPicker(sourceConfig(pickerSource)?.docId)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        docId
    )

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
        // Whatever the answer, the page can start now (if location was denied
        // the page itself shows its "location required" screen with Retry).
        startPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        setContentView(root)

        // The web page sits underneath, the splash picture on top of it.
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(webView)

        splash = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true   // taps must not reach the page under the picture
            var loaded = false
            try {
                assets.open("splash.jpg").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        setImageBitmap(bmp)
                        loaded = true
                    }
                }
            } catch (_: Exception) { }
            if (!loaded) {
                setBackgroundColor(0xFF1e3a5f.toInt())
            }
        }
        root.addView(splash)

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

        val bridge = WebAppInterface(this)
        webView.addJavascriptInterface(bridge, "AndroidEpi")
        webView.addJavascriptInterface(bridge, "AndroidBridge")

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
                startAutoPick()
                return true
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
                view?.evaluateJavascript(
                    "if(typeof window.AndroidBridge==='undefined' && window.AndroidEpi){window.AndroidBridge=window.AndroidEpi;}",
                    null
                )
                pageFinished = true
                tryHideSplash()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!splashGone) {
                    // Picture is still showing — back just leaves the app.
                    finish()
                    return
                }
                webView.evaluateJavascript(
                    """
                    (function(){
                      try {
                        if (typeof goBackOneStep === 'function') {
                          var handled = goBackOneStep();
                          if (handled) return 'js';
                        }
                        if (typeof onNativeBackPressed === 'function') {
                          onNativeBackPressed();
                          return 'js-native';
                        }
                      } catch(e) {}
                      return 'none';
                    })();
                    """.trimIndent()
                ) { result ->
                    val r = result?.trim()?.trim('"') ?: "none"
                    if (r == "js" || r == "js-native") {
                        return@evaluateJavascript
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        })

        // If location is already allowed, the page loads quietly behind the
        // picture right away, so it is ready the moment the picture goes.
        // Otherwise the permission box is shown AFTER the picture, and the
        // page starts once it is answered.
        if (hasLocationPermission()) startPage()

        Handler(Looper.getMainLooper()).postDelayed({
            splashTimerDone = true
            requestAllNeededPermissions()
            if (hasLocationPermission()) startPage()
            tryHideSplash()
            // Safety net: never leave the picture stuck on screen.
            Handler(Looper.getMainLooper()).postDelayed({
                if (pageStarted) tryHideSplash(force = true)
            }, 10000)
        }, SPLASH_MS)

        PassiveLocationWorker.schedule(this)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startPage() {
        if (pageStarted) return
        pageStarted = true
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun tryHideSplash(force: Boolean = false) {
        if (splashGone || !splashTimerDone) return
        if (!force && !pageFinished) return
        splashGone = true
        splash.animate().alpha(0f).setDuration(350).withEndAction {
            root.removeView(splash)
        }.start()
    }

    // =====================================================================
    // Picking the report PDFs from the folder the user chose
    //   SEIR Folder / From Download / From WhatsApp: read that folder by itself
    //   (directly where Android allows it, otherwise through a one-time folder
    //   permission, otherwise the normal picker opened in that folder).
    //   Storage: the normal picker.
    // =====================================================================

    private class Source(val dirs: List<String>, val treeKey: String?, val docId: String?)

    private fun sourceConfig(src: String): Source? = when (src) {
        "seir" -> Source(
            listOf(
                "Android/data/$REPORTS_PKG/files/Download",
                "Android/data/$REPORTS_PKG/files/download"
            ),
            "tree_seir",
            "primary:Android/data/$REPORTS_PKG/files/Download"
        )
        // Android blocks folder-permission for the Download folder itself, so no tree key.
        "download" -> Source(listOf("Download"), null, "primary:Download")
        "whatsapp" -> Source(
            listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            "tree_whatsapp",
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"
        )
        else -> null   // "storage"
    }

    private fun wantName(name: String): Boolean {
        if (!name.endsWith(".pdf", ignoreCase = true)) return false
        val isDefaulter = name.contains("defaulter", ignoreCase = true)
        return when (pickerKind) {
            "defaulters" -> isDefaulter
            "daily" -> !isDefaulter
            else -> true
        }
    }

    // If some of the PDFs are clearly Daily Immunization Reports, take just those;
    // otherwise take all (the app ignores any file that is not a daily report).
    private fun preferDaily(items: List<Pair<String, Uri>>): List<Uri> {
        val sorted = items.sortedBy { it.first }
        if (pickerKind == "daily") {
            val clear = sorted.filter { it.first.contains("immuniz", ignoreCase = true) }
            if (clear.isNotEmpty()) return clear.map { it.second }
        }
        return sorted.map { it.second }
    }

    /** null = folder can't be read directly; empty = readable but no matching PDF. */
    private fun listDirectFiles(cfg: Source): List<Uri>? {
        val base = Environment.getExternalStorageDirectory()
        var readable = false
        val seen = HashSet<String>()
        val found = ArrayList<Pair<String, Uri>>()
        for (rel in cfg.dirs) {
            val dir = File(base, rel)
            val files: Array<File>? = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            }
            if (files == null) continue
            readable = true
            for (f in files) {
                if (f.isFile && f.canRead() && wantName(f.name)) {
                    // Storage is case-insensitive, so "Download" and "download" can be
                    // the same folder — list each file once.
                    if (seen.add(f.name + "|" + f.length())) found.add(f.name to Uri.fromFile(f))
                }
            }
        }
        if (!readable) return null
        // On Android 11+ an empty listing of someone else's files can't be trusted.
        if (found.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
        return preferDaily(found)
    }

    /** null = permission lost / unreadable; empty = readable but no matching PDF. */
    private fun listTreeFiles(treeUri: Uri): List<Uri>? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val out = ArrayList<Pair<String, Uri>>()
            val cursor = contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            ) ?: return null
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    if (wantName(name)) {
                        out.add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }
            preferDaily(out)
        } catch (e: Exception) {
            Log.w("EpiMain", "tree listing failed", e)
            null
        }
    }

    private fun startAutoPick() {
        val cfg = sourceConfig(pickerSource)
        if (cfg == null) {            // "Storage": just the normal picker
            openManualPicker(null)
            return
        }

        // 1) direct folder read
        val direct = listDirectFiles(cfg)
        if (direct != null) {
            if (direct.isNotEmpty()) {
                deliverAutoPicked(direct)
            } else {
                Toast.makeText(this, "فولڈر میں کوئی PDF فائل نہیں ملی", Toast.LENGTH_SHORT).show()
                openManualPicker(cfg.docId)
            }
            return
        }

        // 2) remembered folder permission
        val key = cfg.treeKey
        if (key != null) {
            val saved = prefs().getString(key, null)
            if (saved != null) {
                val viaTree = listTreeFiles(Uri.parse(saved))
                if (viaTree != null) {
                    if (viaTree.isNotEmpty()) {
                        deliverAutoPicked(viaTree)
                    } else {
                        Toast.makeText(this, "فولڈر میں کوئی PDF فائل نہیں ملی", Toast.LENGTH_SHORT).show()
                        openManualPicker(cfg.docId)
                    }
                    return
                }
                // Permission was taken away (or the folder moved) — forget it.
                prefs().edit().remove(key).apply()
            }

            // 3) ask once for the folder (once per app start, so a refusal is not nagging)
            if (treeAskedThisSession.add(key)) {
                try {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cfg.docId != null) {
                            putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri(cfg.docId))
                        }
                    }
                    Toast.makeText(
                        this,
                        "فولڈر کی اجازت ایک بار دیں: «Use this folder» دبائیں",
                        Toast.LENGTH_LONG
                    ).show()
                    pendingTreeKey = key
                    treeLauncher.launch(intent)
                    return
                } catch (e: Exception) {
                    Log.w("EpiMain", "tree picker unavailable", e)
                }
            }
        }

        // 4) plain picker, opened in that folder
        openManualPicker(cfg.docId)
    }

    private fun deliverAutoPicked(uris: List<Uri>) {
        webView.evaluateJavascript("window.__epiAutoPick=true;", null)
        Toast.makeText(this, "${uris.size} فائلیں فولڈر سے خودکار اٹھائی گئیں", Toast.LENGTH_SHORT).show()
        fileChooserCallback?.onReceiveValue(uris.toTypedArray())
        fileChooserCallback = null
    }

    private fun openManualPicker(initialDocId: String?) {
        webView.evaluateJavascript("window.__epiAutoPick=false;", null)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialDocId != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri(initialDocId))
            }
        }
        try {
            fileChooserLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("EpiMain", "file chooser failed", e)
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    // =====================================================================

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
        if (splashTimerDone) {
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
}
