package dev.ryan.opencode.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

import dev.ryan.opencode.core.net.PtySignal
import dev.ryan.opencode.terminal.Cell
import dev.ryan.opencode.terminal.DEFAULT_BG
import dev.ryan.opencode.terminal.PALETTE_INDEX_FLAG
import dev.ryan.opencode.terminal.PALETTE_INDEX_MASK
import dev.ryan.opencode.terminal.Xterm256
import dev.ryan.opencode.terminal.isPaletteIndex
import dev.ryan.opencode.terminal.DEFAULT_FG
import dev.ryan.opencode.terminal.TerminalEmulator
import dev.ryan.opencode.terminal.WideState
import dev.ryan.opencode.ui.LocalAppViewModel

private val TermBg = Color(0xFF0B0B0F)
private val TermFg = Color(0xFFD4D4D8)
private val TermCursor = Color(0xFF7DD3FC)

/** Width of a monospace glyph as a fraction of the font size. */
private const val MONOSPACE_ADVANCE = 0.6f

/** Alpha byte for an opaque colour built from a packed RGB value. */
private const val OPAQUE_ALPHA = 0xFF000000.toInt()

/**
 * Resolve a stored cell colour to something drawable.
 *
 * The emulator encodes colours three ways: a negative sentinel for "default", a
 * tagged 256-palette index (`PALETTE_INDEX_FLAG`), or packed RGB. Passing the raw
 * int straight to `Color()` — as an earlier version did — silently mis-renders the
 * tagged form as near-transparent ARGB, which is why a coloured bash prompt came
 * out almost invisible.
 */
private fun fgColor(stored: Int, default: Color): Color = when {
    stored == DEFAULT_FG -> default
    isPaletteIndex(stored) -> palette(paletteIndexOf(stored))
    stored < 0 -> default
    else -> packed(stored)
}

private fun bgColor(stored: Int): Color = when {
    stored == DEFAULT_BG -> Color.Transparent
    isPaletteIndex(stored) -> palette(paletteIndexOf(stored))
    stored < 0 -> Color.Transparent
    else -> Color(stored or OPAQUE_ALPHA)
}

private fun paletteIndexOf(stored: Int): Int = stored and PALETTE_INDEX_MASK

/** Packed `0xRRGGBB` from the emulator's palette table. */
private fun palette(index: Int): Color = Color(Xterm256.rgb(index) or OPAQUE_ALPHA)

/** Packed `0xRRGGBB` stored directly on a cell. */
private fun packed(rgb: Int): Color = Color(rgb or OPAQUE_ALPHA)

/**
 * Renders the server-side PTY.
 *
 * The emulator lives in the UI scope but the socket tracks its own byte offset, so
 * a network drop re-attaches and the server replays only the missed output. Nothing
 * here needs to ask the user to reconnect.
 */
@Composable
fun TerminalScreen() {
    val vm = LocalAppViewModel.current
    val pty = vm.connection.pty
    val connected by pty.connected.collectAsState()
    val settings by vm.settings.collectAsState()

    val emulator = remember { TerminalEmulator(cols = 100, rows = 30) }
    var lastSize by remember { mutableStateOf(0 to 0) }
    var screenStarted by remember { mutableStateOf(false) }
    var frame by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // The hidden input field always reports the full IME buffer, so we track it
    // ourselves and forward only new characters.
    var imeBuffer by remember { mutableStateOf("") }

    // The terminal must take hardware/soft key events and forward them to the PTY.
    // Without this the on-screen keyboard appears but nothing is typed: the screen
    // has no focusable input target at all.
    val keyHandler = remember { TerminalKeyHandler(pty) }
    LaunchedEffect(Unit) {
        // Grab focus as soon as the tab opens so typing works immediately.
        runCatching { focusRequester.requestFocus() }
    }
    val fontSize = settings.terminalFontSize.sp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val emPx = with(density) { fontSize.toPx() }

    LaunchedEffect(Unit) {
        // Backfill anything the shell emitted before we started listening. The
        // signal flow has no replay, so without this the opening prompt is lost
        // and — because bash then waits for input — the screen never recovers.
        pty.outputSince(0).forEach { emulator.write(it) }
        pty.signals.collect { signal ->
            if (signal is PtySignal.Output) {
                emulator.write(signal.bytes)
                // The shell may be asking us something (cursor report, attributes) — answer it.
                emulator.takePendingOutput().takeIf { it.isNotEmpty() }?.let(pty::reply)
            }
            frame++
        }
    }

    val lines = emulator.screenLines()
    val cursorRow = emulator.cursorRow
    val cursorCol = emulator.cursorCol

    // Follow the tail, the way a real terminal does.
    LaunchedEffect(frame) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Column(Modifier.fillMaxSize().background(TermBg)) {
        // Hidden input: the soft keyboard delivers printable text as IME commits,
        // which bypasses onKeyEvent entirely. A 1px transparent field keeps the IME
        // available while the grid stays the only visible thing.
        BasicTextField(
            value = androidx.compose.ui.text.input.TextFieldValue(imeBuffer, androidx.compose.ui.text.TextRange(imeBuffer.length)),
            onValueChange = { next: androidx.compose.ui.text.input.TextFieldValue ->
                // The IME reports the whole buffer on every commit, so writing it
                // verbatim would echo earlier characters again. Send only the part
                // that is new, then clear so the next commit starts clean.
                val shared = imeBuffer.length.coerceAtMost(next.text.length)
                if (next.text.length > shared) {
                    pty.write(next.text.substring(shared))
                }
                imeBuffer = next.text
            },
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier.size(1.dp).alpha(0f),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (connected) Color(0xFF4ADE80) else Color(0xFFF87171), CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (connected) "attached" else "reconnecting…",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF9CA3AF),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "offset ${pty.consumedOffset()}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6B7280),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 4.dp)
                .focusRequester(focusRequester)
                .onKeyEvent { keyHandler.onKey(it) }
                .onSizeChanged { size ->
            // Size the grid to the real viewport, and create the PTY at that size
            // rather than resizing afterwards: the emulator deliberately has no
            // reflow, so shrinking an already-populated grid splices rows and the
            // prompt appears twice. Changing size tears the PTY down instead.
            val cellPx = emPx * MONOSPACE_ADVANCE
            val cols = (size.width / cellPx).toInt().coerceIn(20, 400)
            val rows = (size.height / (emPx * 1.3f)).toInt().coerceIn(10, 200)
            if (cols != lastSize.first || rows != lastSize.second) {
                val first = !screenStarted
                lastSize = cols to rows
                screenStarted = true
                // Grid and PTY must agree on width. If they disagree the shell
                // wraps for its own width and the prompt lands in the wrong place.
                emulator.resize(cols, rows)
                if (first) vm.ensureTerminal(cols, rows) else vm.restartTerminal(cols, rows)
            }
        }) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(lines.size) { index ->
                    Row(Modifier.width(with(density) { (emulator.cols * emPx * MONOSPACE_ADVANCE).toDp() })) {
                        Text(
                            text = buildRow(
                                row = lines[index],
                                showCursor = index == cursorRow && emulator.cursorVisible,
                                cursorCol = cursorCol,
                            ),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                lineHeight = fontSize * 1.3f,
                            ),
                            softWrap = false,
                        )
                    }
                }
            }
        }

        ExtraKeysRow(onKey = { pty.write(it) })
    }
}

private val CircleShape = androidx.compose.foundation.shape.CircleShape

/** Build one styled row, painting the block cursor inline. */
private fun buildRow(
    row: List<Cell>,
    showCursor: Boolean,
    cursorCol: Int,
): AnnotatedString = AnnotatedString.Builder().apply {
    row.forEachIndexed { index, cell ->
        if (cell.wide == WideState.Continuation) return@forEachIndexed
        val underCursor = showCursor && index == cursorCol
        val style = SpanStyle(
            color = if (underCursor) TermBg else fgColor(cell.fg, TermFg),
            background = if (underCursor) TermCursor else bgColor(cell.bg),
            fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                cell.underline && cell.strike -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                )
                cell.underline -> TextDecoration.Underline
                cell.strike -> TextDecoration.LineThrough
                else -> null
            },
        )
        // Use cell.text, not cell.char: a Char cannot hold a base glyph plus
        // combining marks, nor an astral code point on its own.
        withStyle(style) { append(cell.text) }
    }
}.toAnnotatedString()

@Composable
private fun ExtraKeysRow(onKey: (String) -> Unit) {
    val esc = "\u001B"
    val keys = listOf(
        "esc" to esc + "[",
        "tab" to "\t",
        "ctrl-c" to "\u0003",
        "ctrl-d" to "\u0004",
        "ctrl-l" to "\u000C",
        "ctrl-z" to "\u001A",
        "up" to esc + "[A",
        "down" to esc + "[B",
        "left" to esc + "[D",
        "right" to esc + "[C",
        "home" to esc + "[H",
        "end" to esc + "[F",
        "pgup" to esc + "[5~",
        "pgdn" to esc + "[6~",
        "pipe" to "|",
        "tilde" to "~",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111114))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        keys.forEach { (label, sequence) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD1D5DB),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(Color(0xFF1F2937))
                    .clickable { onKey(sequence) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
