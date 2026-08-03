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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Connects a real domain's inbound mail to this phone, from the phone.
 *
 * Three steps, because that is genuinely how the Cloudflare side works: paste a
 * token, pick a zone it can see, then say which public hostname already reaches
 * this server. The last one cannot be automated here — a Cloudflare Tunnel needs
 * a long-lived local daemon, and a phone has nowhere to run one — so the app
 * asks for it rather than pretending.
 *
 * The token is sent to the local server for the call and stored nowhere.
 */
@Composable
fun CloudflareScreen(vm: ZorailViewModel) {
    val state by vm.cloudflare.collectAsStateWithLifecycle()

    var token by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        ScreenHeader("Domain", "Route real mail to this phone via Cloudflare")

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- step 1: token ----
            StepLabel(1, "Cloudflare API token")
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = { Text("paste a user-owned token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Must be user-owned, not account-owned — Cloudflare's API rejects " +
                    "account tokens for some of these steps. The token is used for " +
                    "this request only and never stored.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { vm.cfLoadZones(token.trim()) },
                enabled = token.isNotBlank() && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy && state.zones.isEmpty()) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Verify token & list domains")
                }
            }

            // ---- step 2: zone ----
            if (state.zones.isNotEmpty()) {
                StepLabel(2, "Pick the mail domain")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.zones.forEach { z ->
                        FilterChip(
                            selected = zone == z.name,
                            onClick = { zone = z.name },
                            label = { Text(z.name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // ---- step 3: public hostname ----
                StepLabel(3, "Public hostname for this server")
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it },
                    placeholder = { Text("ingest.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A host that already reaches this phone's /api/ingest — a Cloudflare " +
                        "Tunnel, a Tailscale Funnel, or a reverse proxy. The Email Worker " +
                        "will POST every message there.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    onClick = { vm.cfConnect(token.trim(), zone, hostname.trim()) },
                    enabled = zone.isNotBlank() && hostname.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Connect $zone")
                    }
                }
            }

            state.error?.let { err ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.WarningAmber, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ---- result ----
            state.result?.let { r ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${r.domain} connected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    r.steps.forEach { s ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle, null,
                                modifier = Modifier.size(16.dp),
                                tint = SuccessColor,
                            )
                            Column {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                if (s.detail.isNotBlank()) {
                                    Text(
                                        s.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    r.warnings.forEach { wmsg ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Default.WarningAmber, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(wmsg, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (state.zones.isEmpty() && state.error == null && !state.busy) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Cloud, null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "A phone cannot be the MX itself — carriers block inbound port 25 " +
                            "and your IP keeps changing. Cloudflare holds the MX and forwards " +
                            "each message here instead.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepLabel(n: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$n",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
