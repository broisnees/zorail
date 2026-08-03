package ai.zorail.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ai.zorail.android.ui.ZorailApp
import ai.zorail.android.ui.ZorailTheme

/**
 * Hosts the native UI.
 *
 * The screens are Compose talking to the Go server's JSON API over loopback —
 * not a WebView over the bundled web dashboard. That dashboard is a desktop
 * layout: on a phone it spent a sixth of the width on a sidebar rail, wrapped
 * its own labels, and hid the message reader entirely below 900px. Native gets
 * real navigation, thumb-reachable targets, and the platform's own gestures.
 */
class MainActivity : ComponentActivity() {

    private val askNotify = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Denied only costs arrival notifications; mail still lands. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15 draws edge-to-edge regardless; opt in explicitly so the
        // Scaffold's own insets are what position the content.
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ZorailService.start(this)

        setContent {
            ZorailTheme { ZorailApp() }
        }
    }
}
