package ai.zorail.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The app's home.
 *
 * Structured rather than a bare list, because the interesting fact about this
 * product is that the phone *is* the mailserver — so the state of that server is
 * the first thing on screen, and minting an address is one tap under it. An
 * earlier version showed only a list, which left a tall phone screen mostly
 * empty and buried the thing that makes it worth using.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxesScreen(vm: ZorailViewModel, onOpen: (String) -> Unit, onNewAddress: () -> Unit) {
    val state by vm.inboxes.collectAsStateWithLifecycle()
    val domain by vm.domain.collectAsStateWithLifecycle()
    val up by vm.serverUp.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { vm.refreshInboxes() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Zorail",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::refreshInboxes) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }

            item { ServerCard(up, vm.smtpPort, domain, vm.bindPublic) }

            item {
                Button(
                    onClick = onNewAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(CardRadius),
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New address", style = MaterialTheme.typography.bodyLarge)
                }
            }

            item {
                Text(
                    if (state.inboxes.isEmpty()) "Inboxes"
                    else "Inboxes · ${state.inboxes.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp),
                )
            }

            when {
                state.error != null -> item {
                    ErrorBlock(state.error!!, onRetry = vm::retry)
                }

                state.loading && state.inboxes.isEmpty() -> item {
                    Text(
                        "Starting the mail server…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }

                state.inboxes.isEmpty() -> item {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Send mail to any address at $domain on port ${vm.smtpPort} and " +
                                "the inbox appears here by itself.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> items(state.inboxes, key = { it.inbox }) { ib ->
                    InboxRow(
                        address = ib.inbox,
                        count = ib.messageCount,
                        when_ = relativeTime(ib.lastReceived.orEmpty()),
                        onClick = { onOpen(ib.inbox) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item { Spacer(Modifier.heightIn(min = 24.dp)) }
        }
    }
}

/**
 * Server status. The dot breathes while listening — the one bit of motion in the
 * app, reserved for the one thing that is genuinely live.
 */
@Composable
private fun ServerCard(up: Boolean, port: Int, domain: String, public: Boolean) {
    // Only animate while actually listening: an infinite transition that nobody
    // can see still drives a frame callback every 8ms.
    val alpha = if (up) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "dot",
        )
    } else {
        null
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(CardRadius))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(CardRadius),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                // graphicsLayer's lambda form, not Modifier.alpha(value): reading
                // the animation inside composition would recompose this whole
                // card every frame, where this keeps it in the draw phase.
                .graphicsLayer { this.alpha = alpha?.value ?: 1f }
                .background(
                    if (up) SuccessColor else MaterialTheme.colorScheme.error,
                    CircleShape,
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (up) "Receiving mail" else "Server stopped",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (up) {
                    (if (public) "Port $port · this network" else "Port $port · this device only") +
                        " · $domain"
                } else {
                    "Not listening"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.WarningAmber, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun InboxRow(address: String, count: Int, when_: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
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
            // The local part carries the meaning; the domain is the same for
            // every row, so it is de-emphasised rather than competing.
            val at = address.indexOf('@')
            Text(
                if (at > 0) address.substring(0, at) else address,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (at > 0) address.substring(at) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                when_,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.heightIn(min = 4.dp))
            Box(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** One inbox's messages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(vm: ZorailViewModel, onBack: () -> Unit, onOpenMessage: (String) -> Unit) {
    val state by vm.inbox.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            state.loading && state.messages.isEmpty() -> LoadingPane()

            state.error != null -> EmptyPane(
                Icons.Default.WarningAmber, "Can't load messages", state.error, isError = true,
            )

            state.messages.isEmpty() -> EmptyPane(
                Icons.Default.MarkEmailUnread, "This inbox is empty",
            )

            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refreshInbox,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
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
}

@Composable
private fun MessageRow(from: String, subject: String, when_: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 76.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
 * ellipsis, which the stock bar does not give.
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
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp)
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
