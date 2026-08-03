package ai.zorail.android

import android.content.Context
import java.security.SecureRandom

/**
 * Server settings, backed by SharedPreferences.
 *
 * Defaults are the safe ones: loopback-only, unprivileged ports, and a random
 * API token minted on first run so the API is never unauthenticated even if the
 * user later flips [bindPublic] on.
 */
class Prefs(ctx: Context) {

    private val sp = ctx.getSharedPreferences("zorail", Context.MODE_PRIVATE)

    var domain: String
        get() = sp.getString(KEY_DOMAIN, "localhost")!!
        set(v) = sp.edit().putString(KEY_DOMAIN, v).apply()

    /** Android refuses ports below 1024 without root, hence 2525 not 25. */
    var smtpPort: Int
        get() = sp.getInt(KEY_SMTP_PORT, 2525)
        set(v) = sp.edit().putInt(KEY_SMTP_PORT, v).apply()

    var httpPort: Int
        get() = sp.getInt(KEY_HTTP_PORT, 8080)
        set(v) = sp.edit().putInt(KEY_HTTP_PORT, v).apply()

    /** Off by default: a phone roams onto networks you do not control. */
    var bindPublic: Boolean
        get() = sp.getBoolean(KEY_BIND_PUBLIC, false)
        set(v) = sp.edit().putBoolean(KEY_BIND_PUBLIC, v).apply()

    var allowedDomains: String
        get() = sp.getString(KEY_ALLOWED, "")!!
        set(v) = sp.edit().putString(KEY_ALLOWED, v).apply()

    var retentionDays: Int
        get() = sp.getInt(KEY_RETENTION, 14)
        set(v) = sp.edit().putInt(KEY_RETENTION, v).apply()

    /** Minted once on first read and reused; never empty. */
    val apiToken: String
        get() = sp.getString(KEY_TOKEN, null) ?: randomToken().also {
            sp.edit().putString(KEY_TOKEN, it).apply()
        }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_DOMAIN = "domain"
        const val KEY_SMTP_PORT = "smtp_port"
        const val KEY_HTTP_PORT = "http_port"
        const val KEY_BIND_PUBLIC = "bind_public"
        const val KEY_ALLOWED = "allowed_domains"
        const val KEY_RETENTION = "retention_days"
        const val KEY_TOKEN = "api_token"
    }
}
