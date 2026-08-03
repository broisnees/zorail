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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Word list for generated addresses, mirroring the web app's vocabulary. */
private val ADJECTIVES = listOf(
    "amber", "brisk", "calm", "dusk", "ember", "fern", "glint", "haze",
    "ivory", "jade", "kite", "lunar", "mint", "north", "onyx", "plume",
    "quartz", "rapid", "slate", "tidal", "umber", "vivid", "willow", "zinc",
)

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

    /** True once the local server has answered; drives the status indicator. */
    private val _serverUp = MutableStateFlow(false)
    val serverUp: StateFlow<Boolean> = _serverUp.asStateFlow()

    /** The SMTP port to show in the UI, so testers know where to point a client. */
    val smtpPort: Int = prefs.smtpPort

    /** Whether the listener accepts connections from the network, not just loopback. */
    val bindPublic: Boolean = prefs.bindPublic

    /**
     * True only while a *user-initiated* refresh runs. The initial load must not
     * set it, or the pull-to-refresh indicator animates in over the header on
     * every cold start, which reads as a glitch.
     */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Newly generated address, surfaced so the UI can confirm and copy it. */
    private val _minted = MutableStateFlow<String?>(null)
    val minted: StateFlow<String?> = _minted.asStateFlow()

    init {
        start()
    }

    /**
     * Waits for the in-process server, then loads. The Activity starts the
     * foreground service and builds this model in the same breath, so a plain
     * fetch here races the listener and loses — which is exactly the bug that
     * left the app stuck on "can't reach the local server".
     */
    private fun start() {
        _inboxes.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // ~20s of patience, checked often enough to feel instant when ready.
            var up = false
            repeat(70) {
                if (api.healthy()) { up = true; return@repeat }
                delay(300)
            }
            _serverUp.value = up
            if (!up) {
                _inboxes.value = InboxesState(
                    loading = false,
                    error = "The mail server didn't start. Check the notification is showing, " +
                        "then try again.",
                )
                return@launch
            }
            runCatching { api.config() }.onSuccess { c ->
                if (c.domain.isNotBlank()) _domain.value = c.domain
            }
            refreshInboxes(userInitiated = false)
            poll()
        }
    }

    /**
     * Keeps the lists current while the app is alive. Without this, mail that
     * arrives while you are looking at the screen simply does not appear, which
     * makes a working server look broken. Cheap enough to just do: the request
     * never leaves the device, so there is no radio and no DNS involved.
     *
     * Quiet by design — it never sets the loading or refreshing flags, so it
     * cannot flicker the UI or spin the pull-to-refresh indicator.
     */
    private suspend fun poll() {
        while (true) {
            delay(4_000)
            runCatching { api.inboxes() }.onSuccess { list ->
                if (list != _inboxes.value.inboxes) {
                    _inboxes.value = InboxesState(loading = false, inboxes = list)
                }
            }
            val open = _inbox.value.address
            if (open.isNotBlank()) {
                runCatching { api.messages(open) }.onSuccess { msgs ->
                    if (msgs != _inbox.value.messages && _inbox.value.address == open) {
                        _inbox.value = InboxState(open, false, msgs)
                    }
                }
            }
        }
    }

    /** Re-runs the wait-then-load sequence; the error state's retry action. */
    fun retry() = start()

    /**
     * Mints a fresh disposable address, matching the web app's shape
     * (`word-1234@domain`). Purely local: the server is a catch-all, so an
     * address exists the moment mail arrives for it — nothing to register.
     */
    fun generateAddress(): String {
        val word = ADJECTIVES.random()
        val n = 1000 + Random.nextInt(9000)
        val addr = "$word-$n@${_domain.value.ifBlank { "localhost" }}"
        _minted.value = addr
        return addr
    }

    fun clearMinted() { _minted.value = null }

    /** Turns an exception into a message worth putting on screen. */
    private fun reason(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "request failed"
        // Before the foreground service finishes binding, loopback simply refuses.
        else -> "Can't reach the local server yet — is the service running?"
    }

    @JvmOverloads
    fun refreshInboxes(userInitiated: Boolean = true) {
        if (userInitiated) _refreshing.value = true
        _inboxes.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.inboxes() }
                .onSuccess { list -> _inboxes.value = InboxesState(false, list) }
                .onFailure { e -> _inboxes.value = InboxesState(false, error = reason(e)) }
            _refreshing.value = false
        }
    }

    @JvmOverloads
    fun openInbox(address: String, userInitiated: Boolean = false) {
        if (userInitiated) _refreshing.value = true
        // Keep the existing rows while refetching so a refresh does not blank the
        // list and jump the scroll position back to the top.
        val keep = if (_inbox.value.address == address) _inbox.value.messages else emptyList()
        _inbox.value = InboxState(address = address, loading = true, messages = keep)
        viewModelScope.launch {
            runCatching { api.messages(address) }
                .onSuccess { m -> _inbox.value = InboxState(address, false, m) }
                .onFailure { e -> _inbox.value = InboxState(address, false, keep, reason(e)) }
            _refreshing.value = false
        }
    }

    fun refreshInbox() {
        val a = _inbox.value.address
        if (a.isNotBlank()) openInbox(a, userInitiated = true)
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
