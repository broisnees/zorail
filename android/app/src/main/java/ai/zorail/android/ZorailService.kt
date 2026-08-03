package ai.zorail.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import ai.zorail.mobile.Listener
import ai.zorail.mobile.Mobile
import ai.zorail.mobile.Server
import java.io.File

/**
 * Runs the Zorail Go server for as long as Android will let us.
 *
 * A plain background thread would be killed within minutes, so this is a
 * foreground service: a persistent notification is the price of a socket that
 * stays bound. Even then the OS may stop us — on low memory, or if the user
 * never granted a battery-optimisation exemption — so treat the server as
 * restartable, never as always-on.
 */
class ZorailService : Service() {

    private var server: Server? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_STICKY

        // Must reach startForeground within ~10s of being started or Android
        // kills the process with a ForegroundServiceDidNotStartInTimeException.
        startForegroundNotice(getString(R.string.notice_starting))

        val prefs = Prefs(this)
        val opts = Mobile.newOptions().apply {
            // Scoped storage: filesDir is the only path reliably ours to write.
            dbPath = File(filesDir, "zorail.db").absolutePath
            domain = prefs.domain
            smtpPort = prefs.smtpPort       // 2525 — binding <1024 needs root
            httpPort = prefs.httpPort
            bindPublic = prefs.bindPublic   // off unless the user opted in
            apiToken = prefs.apiToken
            allowedDomains = prefs.allowedDomains
            retentionDays = prefs.retentionDays
        }

        try {
            val srv = Mobile.newServer(opts)
            srv.start(object : Listener {
                override fun onMail(inbox: String, msgId: String) =
                    main.post { notifyMail(inbox) }

                override fun onError(message: String) = main.post {
                    Log.e(TAG, "server error: $message")
                    updateNotice(getString(R.string.notice_error, message))
                    stopSelf()
                }

                override fun onStopped() = main.post { Log.i(TAG, "server stopped") }
            })
            server = srv
            acquireWifiLock()
            updateNotice(getString(R.string.notice_running, srv.smtpAddr()))
        } catch (e: Exception) {
            // Almost always a port already in use, or an unwritable dbPath.
            Log.e(TAG, "failed to start", e)
            updateNotice(getString(R.string.notice_error, e.message ?: "unknown"))
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // close() also stops the listeners and releases SQLite. Called on the
        // main thread, but shutdown is bounded by a 5s timeout on the Go side.
        runCatching { server?.close() }.onFailure { Log.w(TAG, "close failed", it) }
        server = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        super.onDestroy()
    }

    /** Keeps the Wi-Fi radio associated while the screen is off. */
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundNotice(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTICE_ID, buildNotice(text), type)
    }

    private fun buildNotice(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mail)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW) // status, not an alert
            .build()
    }

    private fun updateNotice(text: String) {
        NotificationManagerCompat.from(this).notify(NOTICE_ID, buildNotice(text))
    }

    /**
     * Posts an arrival notification. Silently dropped when POST_NOTIFICATIONS
     * was never granted (API 33+) — mail still lands in the database either way.
     */
    private fun notifyMail(inbox: String) {
        val open = PendingIntent.getActivity(
            this, inbox.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(EXTRA_INBOX, inbox),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_MAIL)
            .setContentTitle(getString(R.string.mail_title))
            .setContentText(inbox)
            .setSmallIcon(R.drawable.ic_mail)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(inbox.hashCode(), n) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIL,
                getString(R.string.channel_mail),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    companion object {
        private const val TAG = "ZorailService"
        private const val NOTICE_ID = 1
        private const val CHANNEL_STATUS = "zorail.status"
        private const val CHANNEL_MAIL = "zorail.mail"
        const val EXTRA_INBOX = "inbox"

        fun start(ctx: Context) {
            val i = Intent(ctx, ZorailService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ZorailService::class.java))
    }
}
