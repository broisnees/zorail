package ai.zorail.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The whole UI, for free: Zorail already embeds its Nuxt inbox SPA in the Go
 * binary and serves it over HTTP, so the app is a WebView pointed at the
 * server running inside this same process. No duplicated inbox UI in Kotlin.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    private val askNotify = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Denied only costs us arrival notifications; mail still lands. */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ZorailService.start(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // the SPA keeps its session here
            webViewClient = WebViewClient()
        }
        setContentView(web)

        // The service binds its listeners asynchronously, so the first load can
        // race it. Retry on failure rather than showing a dead page.
        loadInbox()
    }

    private fun loadInbox() {
        val port = Prefs(this).httpPort
        web.loadUrl("http://127.0.0.1:$port/")
    }

    override fun onResume() {
        super.onResume()
        if (web.url == null) loadInbox()
    }

    override fun onDestroy() {
        // Deliberately does NOT stop the service — closing the inbox screen
        // should not stop receiving mail. Use the notification action for that.
        web.destroy()
        super.onDestroy()
    }
}
