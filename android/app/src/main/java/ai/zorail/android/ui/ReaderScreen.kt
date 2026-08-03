package ai.zorail.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Reads one message.
 *
 * Shows the plain-text part and falls back to a stripped rendering of the HTML
 * part. It deliberately does not embed a WebView: rendering untrusted HTML from
 * arbitrary senders is the one place in a mail client where an engine that
 * executes script and loads remote resources is a genuine hazard, and a
 * disposable-inbox app receives exactly that kind of mail.
 */
@Composable
fun ReaderScreen(vm: ZorailViewModel, onBack: () -> Unit) {
    val state by vm.reader.collectAsStateWithLifecycle()
    val msg = state.message

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = msg?.subject?.ifBlank { "(no subject)" } ?: "Message",
            onBack = onBack,
            actions = {
                if (msg != null) {
                    IconButton(onClick = { vm.deleteMessage(msg.id) { onBack() } }) {
                        Icon(
                            Icons.Default.Delete, "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )

        when {
            state.loading -> LoadingPane()

            state.error != null -> EmptyPane(
                Icons.Default.WarningAmber, "Can't load message", state.error, isError = true,
            )

            msg == null -> EmptyPane(Icons.Default.WarningAmber, "Message not found")

            else -> Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Envelope
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayFrom(msg.from), style = MaterialTheme.typography.titleMedium)
                    Text(
                        msg.from,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "to ${msg.inbox} · ${relativeTime(msg.receivedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val body = msg.text.ifBlank { htmlToText(msg.html) }
                Text(
                    body.ifBlank { "(empty message)" },
                    style = MaterialTheme.typography.bodyLarge,
                )

                if (msg.attachments.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        if (msg.attachments.size == 1) "1 attachment"
                        else "${msg.attachments.size} attachments",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    msg.attachments.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.AttachFile, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                a.filename.ifBlank { "(unnamed)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                humanSize(a.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Crude HTML-to-text for the fallback path: drop script/style bodies and tags,
 * unescape the handful of entities that actually show up, and collapse the
 * blank-line storm that leaves behind. Good enough to read a signup email; not
 * a rendering engine, and intentionally so.
 */
internal fun htmlToText(html: String): String {
    if (html.isBlank()) return ""
    return html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|tr|h[1-6]|li)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/** Byte count as something readable in a list row. */
internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
