package ai.zorail.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.zorail.android.Prefs
import ai.zorail.android.data.ApiException
import ai.zorail.android.data.CFConnectResult
import ai.zorail.android.data.CFZone
import ai.zorail.android.data.FullMessage
import ai.zorail.android.data.InboxSummary
import ai.zorail.android.data.MessageSummary
import ai.zorail.android.data.ZorailApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen state for the inbox list. */
data class InboxesState(
    val loading: Boolean = true,
    val inboxes: List<InboxSummary> = emptyList(),
    val error: String? = null,
)

/** Screen state for one inbox's messages. */
data class InboxState(
    val address: String = "",
    val loading: Boolean = true,
    val messages: List<MessageSummary> = emptyList(),
    val error: String? = null,
)

/** Screen state for the reader. */
data class ReaderState(
    val loading: Boolean = true,
    val message: FullMessage? = null,
    val error: String? = null,
)

/** Screen state for the Cloudflare connect flow. */
data class CloudflareState(
    val busy: Boolean = false,
    val zones: List<CFZone> = emptyList(),
    val result: CFConnectResult? = null,
    val error: String? = null,
)

/**
 * Single view model behind every screen. One object because the screens share
 * one server and one refresh cycle; splitting per screen would mean duplicating
 * the error handling and the api handle for no gain at this size.
 */
class ZorailViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val api = ZorailApi(prefs.httpPort, prefs.apiToken)

    private val _inboxes = MutableStateFlow(InboxesState())
    val inboxes: StateFlow<InboxesState> = _inboxes.asStateFlow()

    private val _inbox = MutableStateFlow(InboxState())
    val inbox: StateFlow<InboxState> = _inbox.asStateFlow()

    private val _reader = MutableStateFlow(ReaderState())
    val reader: StateFlow<ReaderState> = _reader.asStateFlow()

    private val _cloudflare = MutableStateFlow(CloudflareState())
    val cloudflare: StateFlow<CloudflareState> = _cloudflare.asStateFlow()

    private val _domain = MutableStateFlow(prefs.domain)
    val domain: StateFlow<String> = _domain.asStateFlow()

    /** The SMTP port to show in the UI, so testers know where to point a client. */
    val smtpPort: Int = prefs.smtpPort

    init {
        refreshInboxes()
        viewModelScope.launch {
            // Best-effort: the server may still be binding when the UI starts.
            runCatching { api.config() }.onSuccess { c ->
                if (c.domain.isNotBlank()) _domain.value = c.domain
            }
        }
    }

    /** Turns an exception into a message worth putting on screen. */
    private fun reason(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "request failed"
        // Before the foreground service finishes binding, loopback simply refuses.
        else -> "Can't reach the local server yet — is the service running?"
    }

    fun refreshInboxes() {
        _inboxes.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.inboxes() }
                .onSuccess { list -> _inboxes.value = InboxesState(false, list) }
                .onFailure { e -> _inboxes.value = InboxesState(false, error = reason(e)) }
        }
    }

    fun openInbox(address: String) {
        _inbox.value = InboxState(address = address, loading = true)
        viewModelScope.launch {
            runCatching { api.messages(address) }
                .onSuccess { m -> _inbox.value = InboxState(address, false, m) }
                .onFailure { e -> _inbox.value = InboxState(address, false, error = reason(e)) }
        }
    }

    fun refreshInbox() {
        val a = _inbox.value.address
        if (a.isNotBlank()) openInbox(a)
    }

    fun openMessage(id: String) {
        _reader.value = ReaderState(loading = true)
        viewModelScope.launch {
            runCatching { api.message(id) }
                .onSuccess { m -> _reader.value = ReaderState(false, m) }
                .onFailure { e -> _reader.value = ReaderState(false, error = reason(e)) }
        }
    }

    fun deleteMessage(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { api.deleteMessage(id) }
                .onSuccess { refreshInbox(); refreshInboxes(); onDone() }
                .onFailure { e -> _inbox.update { it.copy(error = reason(e)) } }
        }
    }

    fun deleteInbox(address: String) {
        viewModelScope.launch {
            runCatching { api.deleteInbox(address) }
                .onSuccess { refreshInboxes() }
                .onFailure { e -> _inboxes.update { it.copy(error = reason(e)) } }
        }
    }

    // ---- Cloudflare ----

    fun cfLoadZones(cfToken: String) {
        _cloudflare.update { it.copy(busy = true, error = null, zones = emptyList()) }
        viewModelScope.launch {
            runCatching { api.cfZones(cfToken) }
                .onSuccess { z -> _cloudflare.update { it.copy(busy = false, zones = z) } }
                .onFailure { e -> _cloudflare.update { it.copy(busy = false, error = reason(e)) } }
        }
    }

    fun cfConnect(cfToken: String, domain: String, hostname: String) {
        _cloudflare.update { it.copy(busy = true, error = null, result = null) }
        viewModelScope.launch {
            runCatching { api.cfConnect(cfToken, domain, hostname) }
                .onSuccess { r -> _cloudflare.update { it.copy(busy = false, result = r) } }
                .onFailure { e -> _cloudflare.update { it.copy(busy = false, error = reason(e)) } }
        }
    }

    fun cfReset() {
        _cloudflare.value = CloudflareState()
    }
}
