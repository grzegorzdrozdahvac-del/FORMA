package pl.forma.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        private const val HOST = "appassets.androidplatform.net"
        private const val BASE_URL = "https://appassets.androidplatform.net/assets/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: FormaBridge
    private var revealed = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            bridge.emitPermissions()
        }

    fun askPermissions(perms: Array<String>) {
        permLauncher.launch(perms)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dark system bars with light icons (stable API, no compileSdk 35 needed)
        window.statusBarColor = Color.parseColor("#0B0E11")
        window.navigationBarColor = Color.parseColor("#0B0E11")
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        BleManager.init(this)
        Notifications.ensureChannels(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#0B0E11"))
        webView.alpha = 0f // hidden until the page has loaded, then faded in (no white or half-drawn flash)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isHapticFeedbackEnabled = false
        // no long-press context menu on plain content; text fields keep the default behaviour (select / paste)
        webView.setOnLongClickListener { v ->
            val type = (v as WebView).hitTestResult.type
            type != WebView.HitTestResult.EDIT_TEXT_TYPE
        }
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                reveal()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u: Uri = request.url
                if (u.host == HOST) return false
                // external links (e.g. developer.whoop.com) open in the browser; the WebView itself never leaves the app page
                if (u.scheme == "https" || u.scheme == "http") {
                    try {
                        this@MainActivity.startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                        // no browser available: ignore
                    }
                }
                return true
            }
        }
        bridge = FormaBridge(this, webView)
        webView.addJavascriptInterface(bridge, "FormaNative")
        setContentView(webView)
        handleWhoopIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView.postDelayed({ reveal() }, 3000) // safety net if onPageFinished never arrives
        val route = intent?.getStringExtra(EXTRA_ROUTE)
        webView.loadUrl(if (route != null && route.startsWith("#/")) BASE_URL + route else BASE_URL)
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        webView.animate().alpha(1f).setDuration(200).start()
    }

    /** pl.forma.app://whoop-callback?code=..&state=.. (WHOOP OAuth redirect). The token exchange happens natively. */
    private fun handleWhoopIntent(i: Intent?) {
        val data: Uri? = i?.data
        if (data != null && data.scheme == "pl.forma.app" && data.host == "whoop-callback") {
            bridge.handleWhoopCallback(data)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWhoopIntent(intent)
        val route = intent.getStringExtra(EXTRA_ROUTE)
        if (route != null && route.startsWith("#/") && Regex("^#/[a-z/]+$").matches(route)) {
            webView.evaluateJavascript("location.hash='$route'", null)
        }
    }

    override fun onStart() {
        super.onStart()
        BleManager.addListener(bridge.listener)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        bridge.emitAppResumed()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        BleManager.removeListener(bridge.listener)
        super.onStop()
    }

    override fun onDestroy() {
        BleManager.removeListener(bridge.listener)
        webView.destroy()
        super.onDestroy()
    }
}
