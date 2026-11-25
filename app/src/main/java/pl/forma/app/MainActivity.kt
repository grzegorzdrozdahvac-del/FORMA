package pl.forma.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        private const val HOST = "appassets.androidplatform.net"
        private const val BASE_URL = "https://appassets.androidplatform.net/assets/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: FormaBridge

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
        BleManager.init(this)
        Notifications.ensureChannels(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#0B0E11"))
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != HOST
            }
        }
        bridge = FormaBridge(this, webView)
        webView.addJavascriptInterface(bridge, "FormaNative")
        setContentView(webView)

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

        val route = intent?.getStringExtra(EXTRA_ROUTE)
        webView.loadUrl(if (route != null && route.startsWith("#/")) BASE_URL + route else BASE_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
