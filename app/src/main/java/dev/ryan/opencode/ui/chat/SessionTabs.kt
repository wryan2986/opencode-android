package dev.ryan.opencode.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.SessionRuntime

/**
 * One chip per session with activity, so you can flip between conversations that
 * are genuinely running side by side.
 *
 * A session stays live on the server regardless of whether it is on screen — this
 * only tracks liveness, and opening one rebuilds its conversation from the server.
 * So the tab strip is honest about the real constraint: work continues in the
 * background, but only one conversation is rendered at a time.
 */
@Composable
fun SessionTabs(
    runtimes: Map<String, SessionRuntime>,
    activeId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (runtimes.size < 2) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "running",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(runtimes.entries.toList(), key = { it.key }) { (id, r) ->
                val active = id == activeId
                Row(
                    Modifier
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else Color(0xFF1F2937),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (r.busy) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(Color(0xFF4ADE80), CircleShape),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    if (r.unread > 0) {
                        Text(
                            "${r.unread}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4ADE80),
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        r.title.ifBlank { id.takeLast(6) },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(88.dp),
                    )
                }
            }
        }
    }
}
