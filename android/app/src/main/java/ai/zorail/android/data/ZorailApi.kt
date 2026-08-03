package ai.zorail.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// DTOs. Only the fields the UI actually renders are declared; ignoreUnknownKeys
// lets the server's payloads grow without breaking the app.

@Serializable
data class InboxSummary(
    val inbox: String,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_received") val lastReceived: String? = null,
)

@Serializable
data class MessageSummary(
    val id: String,
    val inbox: String = "",
    val from: String = "",
    @SerialName("env_from") val envFrom: String = "",
    val subject: String = "",
    @SerialName("received_at") val receivedAt: String = "",
    val size: Long = 0,
)

@Serializable
data class Attachment(
    val id: String = "",
    val filename: String = "",
    @SerialName("content_type") val contentType: String = "",
    val size: Long = 0,
)

@Serializable
data class FullMessage(
    val id: String,
    val inbox: String = "",
    val from: String = "",
    val subject: String = "",
    @SerialName("received_at") val receivedAt: String = "",
    val text: String = "",
    val html: String = "",
    val attachments: List<Attachment> = emptyList(),
)

@Serializable
data class ServerConfig(
    val domain: String = "",
    val version: String = "",
)

@Serializable
data class CFZone(val name: String, val id: String)

@Serializable
data class CFStep(val name: String, val detail: String = "")

@Serializable
data class CFConnectResult(
    val domain: String = "",
    val worker: String = "",
    @SerialName("ingest_url") val ingestUrl: String = "",
    val steps: List<CFStep> = emptyList(),
    val warnings: List<String> = emptyList(),
)

// Request bodies. Declared types rather than mapOf(...) because kotlinx
// serialization needs a serializer it can resolve at compile time.

@Serializable
private data class TokenReq(val token: String)

@Serializable
private data class ConnectReq(val token: String, val domain: String, val hostname: String)

/** Server-side error carrying the API's own message, which is worth showing. */
class ApiException(val status: Int, message: String) : Exception(message)

@Serializable
private data class ErrorBody(val error: String = "")

/**
 * Client for the Zorail JSON API running inside this very process.
 *
 * Going over loopback HTTP rather than widening the gomobile binding is a
 * deliberate trade: the JSON API is already the tested, stable surface, whereas
 * every extra bound Go type has to be expressed in gomobile's narrow subset
 * (no maps, no slices but []byte). Loopback has no radio and no DNS, so the
 * round-trip cost is irrelevant here.
 */
class ZorailApi(private val port: Int, private val token: String) {

    private val base = "http://127.0.0.1:$port/api"

    private val http = OkHttpClient.Builder()
        // Generous: /wait is a long-poll, and Cloudflare provisioning is slow.
        .callTimeout(4, TimeUnit.MINUTES)
        .readTimeout(4, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$base$path")
            .header("Authorization", "Bearer $token")
            .apply {
                if (body != null) {
                    method(method, body.toRequestBody("application/json".toMediaType()))
                } else if (method != "GET") {
                    method(method, null)
                }
            }
            .build()

        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                // Surface the server's message; fall back to the status line.
                val msg = runCatching { json.decodeFromString<ErrorBody>(text).error }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${res.code}"
                throw ApiException(res.code, msg)
            }
            text
        }
    }

    private inline fun <reified T> decode(s: String): T = json.decodeFromString(s)

    suspend fun config(): ServerConfig = decode(request("/config"))

    suspend fun inboxes(): List<InboxSummary> = decode(request("/inboxes"))

    suspend fun messages(inbox: String): List<MessageSummary> =
        decode(request("/inboxes/${enc(inbox)}/messages"))

    suspend fun message(id: String): FullMessage = decode(request("/messages/${enc(id)}"))

    suspend fun deleteMessage(id: String) { request("/messages/${enc(id)}", "DELETE") }

    suspend fun deleteInbox(inbox: String) { request("/inboxes/${enc(inbox)}", "DELETE") }

    suspend fun search(q: String): List<MessageSummary> = decode(request("/search?q=${enc(q)}"))

    suspend fun cfZones(cfToken: String): List<CFZone> =
        decode(request("/cloudflare/zones", "POST", json.encodeToString(TokenReq(cfToken))))

    suspend fun cfConnect(cfToken: String, domain: String, hostname: String): CFConnectResult =
        decode(
            request(
                "/cloudflare/connect", "POST",
                json.encodeToString(ConnectReq(cfToken, domain, hostname)),
            )
        )

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
