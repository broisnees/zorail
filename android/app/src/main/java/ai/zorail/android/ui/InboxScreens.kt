package ai.zorail.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The inbox list — the app's home. Rows are 64dp+ so they are comfortable
 * one-handed, which is the main reason this is not the web layout.
 */
@Composable
fun InboxesScreen(vm: ZorailViewModel, onOpen: (String) -> Unit) {
    val state by vm.inboxes.collectAsStateWithLifecycle()
    val domain by vm.domain.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = "Inboxes",
            subtitle = "Receiving on port ${vm.smtpPort} · $domain",
            actions = {
                IconButton(onClick = vm::refreshInboxes) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            },
        )

        when {
            state.loading && state.inboxes.isEmpty() -> LoadingPane()

            state.error != null -> EmptyPane(
                Icons.Default.WarningAmber,
                "Can't load inboxes",
                state.error,
                isError = true,
            )

            state.inboxes.isEmpty() -> EmptyPane(
                Icons.Default.Inbox,
                "No mail yet",
                "Send a message to anything@$domain on port ${vm.smtpPort} and it will appear here.",
            )

            else -> LazyColumn {
                items(state.inboxes, key = { it.inbox }) { ib ->
                    InboxRow(
                        address = ib.inbox,
                        count = ib.messageCount,
                        when_ = relativeTime(ib.lastReceived.orEmpty()),
                        onClick = { onOpen(ib.inbox) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun InboxRow(address: String, count: Int, when_: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                address.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                address,
                style = MaterialTheme.typography.bodySmall, // monospace: it is an address
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (count == 1) "1 message" else "$count messages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when_,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One inbox's messages. */
@Composable
fun InboxScreen(vm: ZorailViewModel, onBack: () -> Unit, onOpenMessage: (String) -> Unit) {
    val state by vm.inbox.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = state.address,
            monoTitle = true,
            onBack = onBack,
            actions = {
                IconButton(onClick = vm::refreshInbox) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                IconButton(onClick = { vm.deleteInbox(state.address); onBack() }) {
                    Icon(
                        Icons.Default.Delete, "Clear inbox",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )

        when {
            state.loading && state.messages.isEmpty() -> LoadingPane()

            state.error != null -> EmptyPane(
                Icons.Default.WarningAmber, "Can't load messages", state.error, isError = true,
            )

            state.messages.isEmpty() -> EmptyPane(
                Icons.Default.MarkEmailUnread, "This inbox is empty",
            )

            else -> LazyColumn {
                items(state.messages, key = { it.id }) { m ->
                    MessageRow(
                        from = displayFrom(m.from.ifBlank { m.envFrom }),
                        subject = m.subject.ifBlank { "(no subject)" },
                        when_ = relativeTime(m.receivedAt),
                        onClick = { onOpenMessage(m.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(from: String, subject: String, when_: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                from,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when_,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            subject,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Screen title block. A shared composable rather than Material's TopAppBar
 * because these titles are often long addresses that need monospace and
 * middle-ellipsis, which the stock bar does not give.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    monoTitle: Boolean = false,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp)
            .heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (monoTitle) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}
