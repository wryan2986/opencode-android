package dev.ryan.opencode.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ryan.opencode.core.ChatItem
import dev.ryan.opencode.ui.LocalAppViewModel
import dev.ryan.opencode.voice.VoiceState
import kotlinx.coroutines.launch
import kotlin.math.sin

private data class Turn(val speaker: String, val text: String)

/**
 * Voice mode — a first-class top-level surface, not a toggle buried in chat.
 *
 * It runs against whatever session is currently open, so a voice conversation and
 * a typed conversation are the same thread.
 */
@Composable
fun VoiceScreen() {
    val vm = LocalAppViewModel.current
    val voiceState by vm.voice.state.collectAsState()
    val chatState by vm.chat.state.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val transcript = remember { mutableStateListOf<Turn>() }
    var handsFree by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        vm.voice.initialise()
        vm.voice.onFinalUtterance = { text ->
            transcript.add(Turn("You", text))
            scope.launch { vm.chat.send(text) }
        }
        vm.voice.onBargeIn = {
            transcript.add(Turn("You", "— interrupted —"))
            scope.launch { vm.chat.interrupt() }
        }
        onDispose {
            vm.voice.onFinalUtterance = null
            vm.voice.onBargeIn = null
            vm.voice.stopListening()
        }
    }

    // Stream the assistant's live text into sentence-level speech.
    LaunchedEffect(chatState.busy) {
        if (chatState.busy) vm.voice.beginAssistantTurn()
    }
    LaunchedEffect(chatState.items) {
        val last = chatState.items.lastOrNull()
        if (last is ChatItem.Assistant && last.streaming) {
            vm.voice.feedAssistantText(last.text)
        } else if (last is ChatItem.Assistant && last.finished) {
            vm.voice.endAssistantTurn()
            if (last.text.isNotBlank()) {
                transcript.add(Turn("opencode", last.text))
            }
        }
    }

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                Text(
                    chatState.sessionId.takeIf { it.isNotBlank() }?.let { "Continuing current session" }
                        ?: "Open a chat session first",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Hands-free", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Switch(checked = handsFree, onCheckedChange = { handsFree = it })
        }

        Spacer(Modifier.height(6.dp))
        VoiceOrb(
            state = voiceState.state,
            level = voiceState.micLevel,
            onTap = {
                if (voiceState.state == VoiceState.Listening) vm.voice.stopListening()
                else vm.voice.startListening(autoRestart = handsFree)
            },
        )

        Text(
            text = when (voiceState.state) {
                VoiceState.Listening -> if (voiceState.partialTranscript.isNotBlank())
                    "“${voiceState.partialTranscript}”" else "Listening…"
                VoiceState.Thinking -> "Thinking…"
                VoiceState.Speaking -> "Speaking — tap to interrupt"
                VoiceState.Interrupted -> "Interrupted"
                VoiceState.Error -> voiceState.lastError ?: "Voice error"
                VoiceState.Idle -> if (voiceState.speechAvailable) "Tap to talk" else "Speech unavailable"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (voiceState.state == VoiceState.Error) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        if (chatState.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Agent is working", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "Stop",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { scope.launch { vm.chat.interrupt() } },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(transcript) { turn ->
                Surface(
                    color = if (turn.speaker == "You") MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            turn.speaker,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (turn.speaker == "You") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * The orb. Driven by the live mic level while listening and by an animation while
 * speaking, so the state is legible at a glance from across a room.
 */
@Composable
private fun VoiceOrb(state: VoiceState, level: Float, onTap: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow = when (state) {
        VoiceState.Listening -> level.coerceIn(0f, 1f)
        VoiceState.Speaking -> 0.55f + 0.35f * sin(pulse * 6.28).toFloat().let { kotlin.math.abs(it) }
        VoiceState.Thinking -> 0.3f + 0.2f * pulse
        else -> 0.15f
    }
    val tint = when (state) {
        VoiceState.Listening -> MaterialTheme.colorScheme.primary
        VoiceState.Speaking -> MaterialTheme.colorScheme.secondary
        VoiceState.Thinking -> MaterialTheme.colorScheme.tertiary
        VoiceState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp).scale(0.9f + glow * 0.25f)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.05f)),
                    center = center,
                    radius = size.minDimension / 2,
                ),
                radius = size.minDimension / 2,
            )
        }
        Box(
            Modifier
                .size(96.dp)
                .background(tint.copy(alpha = 0.18f), CircleShape)
                .clickable { onTap() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state == VoiceState.Listening) Icons.Filled.Mic else Icons.Filled.MicOff,
                contentDescription = "Toggle listening",
                tint = tint,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
