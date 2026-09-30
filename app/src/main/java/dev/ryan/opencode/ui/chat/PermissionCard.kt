package dev.ryan.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.model.PermissionRequest

/**
 * Inline approval prompt.
 *
 * Deliberately prominent and unmissable: the agent is blocked until this is
 * answered, so it has to be obvious even at the bottom of a long conversation.
 * The same decision is also offered from the notification, so the user can answer
 * without scrolling to it.
 */
@Composable
fun PermissionCard(
    request: PermissionRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Approval needed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                request.type.ifBlank { "permission" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontFamily = FontFamily.Monospace,
            )
        }

        val detail = request.title?.takeIf { it.isNotBlank() } ?: describeMetadata(request)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) { Text("Allow once") }
            OutlinedButton(onClick = onDeny) { Text("Deny") }
        }
    }
}

/** Best-effort human summary from the event's metadata blob. */
private fun describeMetadata(request: PermissionRequest): String {
    val meta = request.metadata ?: return ""
    val parts = buildList {
        meta["command"]?.let { add("$it") }
        meta["path"]?.let { add(it.toString().trim('"')) }
        meta["pattern"]?.let { add(it.toString().trim('"')) }
        meta["url"]?.let { add(it.toString().trim('"')) }
    }
    return parts.joinToString("  ")
}
