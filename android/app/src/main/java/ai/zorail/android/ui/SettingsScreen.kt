package ai.zorail.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.zorail.android.Prefs
import androidx.compose.ui.platform.LocalContext

/**
 * Server settings.
 *
 * Everything here needs the service restarted to take effect, since the Go side
 * reads its config once at Start — so the screen says so rather than silently
 * doing nothing.
 */
@Composable
fun SettingsScreen(vm: ZorailViewModel) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var bindPublic by remember { mutableStateOf(prefs.bindPublic) }
    var retention by remember { mutableStateOf(prefs.retentionDays) }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Settings")

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingRow(
                title = "Accept from the network",
                subtitle = if (bindPublic)
                    "Listening on all interfaces — other devices on this network can send mail here."
                else
                    "Loopback only. Turn on to receive from other devices on your network.",
            ) {
                Switch(
                    checked = bindPublic,
                    onCheckedChange = {
                        bindPublic = it
                        prefs.bindPublic = it
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            InfoRow("SMTP port", vm.smtpPort.toString())
            InfoRow("Retention", if (retention > 0) "$retention days" else "keep forever")

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                "Changes apply next time the server starts. The mail engine is native " +
                    "Go compiled for this device — the same code the server runs.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    control: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        control()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
