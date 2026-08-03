package ai.zorail.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/** Centred spinner for a screen that has nothing to show yet. */
@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

/**
 * The empty/error state. Deliberately one component for both: to a user, "there
 * is nothing here" and "we could not fetch it" want the same shape, differing
 * only in words and whether the tone is neutral or alarming.
 */
@Composable
fun EmptyPane(
    icon: ImageVector,
    title: String,
    description: String? = null,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon, null,
                modifier = Modifier.size(34.dp),
                tint = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** A bordered surface used for grouped settings and info blocks. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(CardRadius))
    ) { content() }
}

/** Standard horizontal inset for screen content. */
val ScreenPadding = PaddingValues(horizontal = 16.dp)

/**
 * Compact relative time ("4m", "3h", "2d") for list rows, where a full
 * timestamp would crowd out the subject. Falls back to the raw string if the
 * server ever sends something unparseable rather than showing an error.
 */
fun relativeTime(iso: String): String {
    if (iso.isBlank()) return ""
    val then = try {
        Instant.parse(iso)
    } catch (_: DateTimeParseException) {
        return iso
    }
    val d = Duration.between(then, Instant.now())
    val secs = d.seconds
    return when {
        secs < 60 -> "now"
        secs < 3_600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3_600}h"
        secs < 604_800 -> "${secs / 86_400}d"
        else -> "${secs / 604_800}w"
    }
}

/**
 * Pulls a display name out of a From header, preferring the human part:
 * `"Nischal" <n@x.com>` becomes `Nischal`, a bare address stays as-is.
 */
fun displayFrom(raw: String): String {
    if (raw.isBlank()) return "(unknown)"
    val angle = raw.indexOf('<')
    if (angle > 0) {
        val name = raw.substring(0, angle).trim().trim('"')
        if (name.isNotBlank()) return name
    }
    return raw.trim().trim('<', '>')
}
