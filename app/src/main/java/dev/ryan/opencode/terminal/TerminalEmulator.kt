package dev.ryan.opencode.terminal

/*
 * ---------------------------------------------------------------------------
 * TerminalEmulator — a pure-Kotlin VT100 / xterm-subset terminal core.
 *
 * This is the *client side* view of a terminal: the app feeds it raw bytes read
 * off a PTY over a WebSocket and it turns them into a cell grid that a Compose
 * UI can render. It never spawns processes and has no Android dependencies, so
 * it is unit-testable on a plain JVM.
 *
 * Design notes
 * ------------
 * * A hand-written DEC ANSI parser is used rather than a table of regular
 *   expressions, because the input arrives as arbitrary byte chunks: escape
 *   sequences, UTF-8 code points and control characters may all be split
 *   across `write()` boundaries. All parser state lives in fields so it
 *   survives between calls.
 * * The screen is a flat `Array<Cell>` (row-major, `row * cols + col`) because
 *   Cell is immutable; rows are sliced into `List<Cell>` only for rendering.
 * * `onUpdate` is coalesced: writes set a dirty flag and the callback fires at
 *   most once per `write()` call, so a 1 MB burst costs one redraw, not 1 M.
 *
 * Deliberately *not* implemented (see the class docs for the rest):
 * reflow-on-resize, scroll regions per screen, reverse video colour resolution,
 * DCS passthrough to the PTY, sixel/kitty graphics, and the many DEC private
 * modes that only affect keyboard reporting (the UI layer handles keys).
 * ---------------------------------------------------------------------------
 */

// ───────────────────────────────── Colours ─────────────────────────────────

/**
 * Sentinel stored in [Cell.fg] meaning "use the renderer's default foreground".
 * Renderers that honour `reverse` video should swap this with [DEFAULT_BG].
 */
const val DEFAULT_FG: Int = -1

/** Sentinel stored in [Cell.bg] meaning "use the renderer's default background". */
const val DEFAULT_BG: Int = -2

/**
 * Set on a colour value that holds a 256-colour palette index rather than a
 * packed RGB triple. Packed RGB only occupies bits 0..23, so the top bits are
 * free to tag the value; the negative [DEFAULT_FG]/[DEFAULT_BG] sentinels are
 * signed, so the two encodings can never be confused either.
 */
const val PALETTE_INDEX_FLAG: Int = 0x40000000

/** Bits of the colour tag, excluding the palette index itself. */
private const val PALETTE_TAG_MASK: Int = 0xFC000000.toInt()

/** Mask for the palette index carried alongside [PALETTE_INDEX_FLAG]. */
const val PALETTE_INDEX_MASK: Int = 0xFF

/** Wraps a 0..255 index into the tagged form stored in a [Cell] colour field. */
fun paletteIndex(index: Int): Int = PALETTE_INDEX_FLAG or (index and PALETTE_INDEX_MASK)

/** True if [color] is a tagged palette index rather than packed RGB or a sentinel. */
fun isPaletteIndex(color: Int): Boolean = (color and PALETTE_TAG_MASK) == PALETTE_INDEX_FLAG

/**
 * The standard xterm 256-colour palette as packed `0xRRGGBB`, for renderers
 * that would rather resolve colours eagerly than call [isPaletteIndex].
 *
 * Indices 0..7 are the classic VT100/VGA ANSI colours and 8..15 the matching
 * bright variants — the self-consistent pairing emulators use when no theme is
 * supplied. Renderers are expected to theme 0..15 themselves and only fall
 * back to this table.
 */
object Xterm256 {
    /** Packed `0xRRGGBB` for palette [index] (0..255); index is wrapped. */
    fun rgb(index: Int): Int {
        val i = index and 0xFF
        if (i < 8) return STANDARD[i]
        if (i < 16) return BRIGHT[i - 8]
        if (i < 232) return cubeRgb((i - 16) / 36, (i - 16) / 6 % 6, (i - 16) % 6)
        val v = 8 + (i - 232) * 10
        return (v shl 16) or (v shl 8) or v
    }

    /** Packed `0xRRGGBB` for cube colour components [r], [g], [b] (each 0..5). */
    fun cubeRgb(r: Int, g: Int, b: Int): Int =
        (level(r) shl 16) or (level(g) shl 8) or level(b)

    private fun level(step: Int): Int = CUBE_STEPS[step.coerceIn(0, 5)]

    private val CUBE_STEPS = intArrayOf(0, 95, 135, 175, 215, 255)

    private val STANDARD = intArrayOf(
        0x000000, 0x800000, 0x008000, 0x808000,
        0x000080, 0x800080, 0x008080, 0xC0C0C0,
    )

    private val BRIGHT = intArrayOf(
        0x545454, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )
}

// ────────────────────────────────── Cell ───────────────────────────────────

/**
 * Marks how a cell participates in a double-width grapheme cluster.
 *
 * The first cell of a wide (2-column) character is [Normal] and carries the
 * character; the cell to its right is [Continuation] and must be skipped by
 * the renderer.
 */
enum class WideState { Normal, Continuation }

/**
 * One screen cell. Immutable, so a `Array<Cell>` can be mutated in place while
 * cells themselves are shared freely.
 *
 * @param char the leading code unit of the character occupying the cell.
 * @param combining extra code units drawn immediately after [char]: zero-width
 *   combining marks, or the trailing surrogate of an astral (emoji) code point.
 *   Renderers should draw [text]. This is a separate field because a UTF-16
 *   [Char] cannot hold a base character plus its marks.
 */
data class Cell(
    val char: Char = ' ',
    val fg: Int = DEFAULT_FG,
    val bg: Int = DEFAULT_BG,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val strike: Boolean = false,
    val wide: WideState = WideState.Normal,
    val combining: String? = null,
) {
    /** True when this cell is the right half of a double-width character. */
    val isContinuation: Boolean get() = wide == WideState.Continuation

    /** The full grapheme to draw: base character plus any combining marks. */
    val text: String get() = if (combining == null) char.toString() else "$char$combining"
}

/** The canonical "empty" cell; shared because [Cell] is immutable. */
internal val BLANK_CELL = Cell()

// ───────────────────────────── Character widths ─────────────────────────────

private const val REPLACEMENT = 0xFFFD

/**
 * Heuristic double-width test for East Asian Wide / Fullwidth characters and
 * common emoji. Not a full Unicode `East_Asian_Width` table — it covers the
 * ranges that actually show up in terminal output.
 */
internal fun isWideCodePoint(cp: Int): Boolean {
    if (cp < 0x1100) return false
    if (cp < 0x1160) return true              // Hangul Jamo initial consonants
    if (cp in 0x2E80..0x303E) return true     // CJK radicals .. CJK symbols
    if (cp == 0x303F) return false
    if (cp in 0x3041..0x33FF) return true     // Kana, Bopomofo, Hangul compat, CJK strokes
    if (cp in 0x3400..0x4DBF) return true     // CJK ext A
    if (cp in 0x4E00..0x9FFF) return true     // CJK unified ideographs
    if (cp in 0xA000..0xA4CF) return true     // Yi
    if (cp in 0xA960..0xA97F) return true     // Hangul Jamo extended-A
    if (cp in 0xAC00..0xD7A3) return true     // Hangul syllables
    if (cp in 0xF900..0xFAFF) return true     // CJK compatibility ideographs
    if (cp in 0xFE10..0xFE19) return true     // vertical forms
    if (cp in 0xFE30..0xFE6F) return true     // CJK compatibility / small forms
    if (cp in 0xFF00..0xFF60) return true     // fullwidth ASCII
    if (cp in 0xFFE0..0xFFE6) return true     // fullwidth currency signs
    if (cp in 0x1F1E6..0x1F1FF) return true    // regional indicators (flags)
    if (cp in 0x1F300..0x1F64F) return true    // emoji: misc symbols & pictographs, emoticons
    if (cp in 0x1F680..0x1F6FF) return true    // transport & map symbols
    if (cp in 0x1F900..0x1F9FF) return true    // supplemental symbols & pictographs
    if (cp in 0x1FA70..0x1FAFF) return true    // extended-A
    if (cp in 0x20000..0x2FFFD) return true    // CJK ext B..
    if (cp in 0x30000..0x3FFFD) return true    // CJK ext G..
    return false
}

/** Heuristic zero-width test: combining marks, joiners, variation selectors. */
internal fun isZeroWidthCodePoint(cp: Int): Boolean {
    if (cp < 0x0300) return false
    if (cp in 0x0300..0x036F) return true      // combining diacritical marks
    if (cp in 0x0483..0x0489) return true
    if (cp in 0x0591..0x05BD) return true
    if (cp in 0x0610..0x061A) return true
    if (cp in 0x064B..0x065F) return true
    if (cp in 0x0670..0x0670) return true
    if (cp in 0x06D6..0x06DC) return true
    if (cp in 0x0730..0x074A) return true
    if (cp in 0x07A6..0x07B0) return true
    if (cp in 0x0900..0x0903) return true
    if (cp in 0x093A..0x094F) return true
    if (cp in 0x0951..0x0957) return true
    if (cp in 0x0962..0x0963) return true
    if (cp in 0x0981..0x0983) return true
    if (cp in 0x09BC..0x09CD) return true
    if (cp in 0x0A01..0x0A03) return true
    if (cp in 0x0A3C..0x0A51) return true
    if (cp in 0x0B01..0x0B03) return true
    if (cp in 0x0E31..0x0E31) return true
    if (cp in 0x0E34..0x0E3A) return true
    if (cp in 0x0E47..0x0E4E) return true
    if (cp in 0x0EB1..0x0EB1) return true
    if (cp in 0x0EB4..0x0EBC) return true
    if (cp in 0x0EC8..0x0ECD) return true
    if (cp in 0x1AB0..0x1AFF) return true
    if (cp in 0x1DC0..0x1DFF) return true
    if (cp in 0x200B..0x200F) return true      // zero-width space .. RLM
    if (cp in 0x2060..0x2064) return true      // word joiner, invisible operators
    if (cp in 0x20D0..0x20F0) return true      // combining marks for symbols
    if (cp in 0xFE00..0xFE0F) return true      // variation selectors
    if (cp in 0xFE20..0xFE2F) return true      // combining half marks
    if (cp == 0xFEFF) return true              // zero-width no-break space / BOM
    if (cp in 0xE0100..0xE01EF) return true    // variation selectors supplement
    return false
}

// ──────────────────────────── Screen buffer ────────────────────────────────

/** A saved cursor position plus the attributes that go with it (DECSC). */
private class SavedCursor(
    var row: Int,
    var col: Int,
    var fg: Int,
    var bg: Int,
    var bold: Boolean,
    var faint: Boolean,
    var italic: Boolean,
    var underline: Boolean,
    var reverse: Boolean,
    var strike: Boolean,
)

/** One screen: a flat cell grid plus its own cursor. */
private class ScreenBuffer(val cols: Int, val rows: Int) {
    var cells: Array<Cell> = Array(rows * cols) { BLANK_CELL }
    var cursorRow = 0
    var cursorCol = 0
    var saved: SavedCursor? = null
}

/** Growable SGR/pen. Mutable so attribute changes never allocate. */
private class Pen {
    var fg = DEFAULT_FG
    var bg = DEFAULT_BG
    var bold = false
    var faint = false
    var italic = false
    var underline = false
    var reverse = false
    var strike = false

    private var eraseCache: Cell? = null

    fun reset() {
        fg = DEFAULT_FG
        bg = DEFAULT_BG
        bold = false
        faint = false
        italic = false
        underline = false
        reverse = false
        strike = false
        eraseCache = null
    }

    /**
     * The cell that erase operations paint with. xterm erases using the
     * current background colour only; other attributes revert to default.
     */
    fun eraseCell(): Cell {
        if (bg == DEFAULT_BG) return BLANK_CELL
        val cached = eraseCache
        if (cached != null && cached.bg == bg) return cached
        val cell = Cell(' ', DEFAULT_FG, bg)
        eraseCache = cell
        return cell
    }

    fun applyTo(cell: Cell): Cell = Cell(
        cell.char, fg, bg, bold, faint, italic, underline, reverse, strike,
        cell.wide, cell.combining,
    )

    fun snapshot(row: Int, col: Int) = SavedCursor(
        row, col, fg, bg, bold, faint, italic, underline, reverse, strike,
    )

    fun restore(s: SavedCursor) {
        fg = s.fg
        bg = s.bg
        bold = s.bold
        faint = s.faint
        italic = s.italic
        underline = s.underline
        reverse = s.reverse
        strike = s.strike
        eraseCache = null
    }
}

/** Parser states of the DEC ANSI state machine. */
private enum class St {
    GROUND,     // printing
    ESCAPE,     // after ESC, possibly collecting intermediates
    CSI,        // inside ESC [ ... final
    OSC,        // inside ESC ] ... (BEL or ST)
    OSC_ESC,    // ESC seen inside an OSC; may be ST
    STR,        // DCS / SOS / PM / APC payload, consumed to ST
    STR_ESC,    // ESC seen inside a string; may be ST
}

/**
 * CSI parameters. Values are stored flat; [subs] records whether a value was
 * preceded by `:` (a sub-parameter) rather than `;`, which is how SGR's
 * `38:2::r:g:b` form is distinguished from the legacy `38;2;r;g;b`.
 */
private class Params {
    var values = IntArray(16)
    var subs = BooleanArray(16)
    var count = 0

    fun clear() {
        count = 0
    }

    fun push(v: Int, colon: Boolean) {
        if (count >= MAX_PARAMS) return // xterm ignores parameters beyond the limit
        if (count == values.size) {
            values = values.copyOf(values.size * 2)
            subs = subs.copyOf(subs.size * 2)
        }
        values[count] = v
        subs[count] = colon
        count++
    }

    /** Appends a digit to the value currently being accumulated. */
    fun digit(d: Int) {
        if (count == 0) {
            push(d, false)
            return
        }
        val i = count - 1
        values[i] = values[i] * 10 + d
    }

    /** Ends the current value and starts an empty one. */
    fun separator(colon: Boolean) = push(0, colon)

    operator fun get(i: Int): Int = values[i]

    fun isSub(i: Int): Boolean = subs[i]

    private companion object {
        const val MAX_PARAMS = 32
    }
}

// ───────────────────────────── TerminalEmulator ────────────────────────────

/**
 * A VT100/xterm-subset terminal: it consumes raw PTY bytes and maintains the
 * screen grid, cursor, scroll region, SGR attributes, scrollback, alternate
 * screen and window title.
 *
 * Features: the C0 controls, the common CSI cursor/erase/edit/scroll/SGR
 * family, DECSTBM scrolling regions, DEC private mode `?25` (cursor
 * visibility), `?7` (autowrap), `?47`/`?1047`/`?1048`/`?1049` (alternate
 * screen and cursor save/restore), `?2004` (bracketed paste), IRM insert
 * mode, DSR/DA device reports, OSC 0/1/2 window titles, and complete
 * consumption of OSC/DCS/SOS/PM/APC strings.
 *
 * Limitations worth knowing:
 * * **No reflow on resize.** Content is preserved on a naive row/column
 *   overlap; soft-wrapped lines are not re-flowed when the width changes.
 * * **Combining marks** are kept in [Cell.combining] rather than merged into
 *   [Cell.char], because a UTF-16 [Char] cannot hold a base character plus
 *   marks. Renderers should draw [Cell.text].
 * * **No sixel/kitty graphics, no DCS passthrough**, no DECRQSS and no
 *   keyboard-encoding modes (the UI layer owns those).
 * * Not thread-safe; call from the socket reader thread only.
 */
@Suppress("NAME_SHADOWING")
class TerminalEmulator(cols: Int, rows: Int, private val maxScrollback: Int = 5000) {

    /** Current number of columns. Updated by [resize]. */
    var cols: Int = cols
        private set

    /** Current number of rows. Updated by [resize]. */
    var rows: Int = rows
        private set

    /** Invoked at most once per [write] when the screen has changed. */
    var onUpdate: (() -> Unit)? = null

    /** True when bracketed paste is enabled; the UI wraps pastes in `ESC[200~`/`ESC[201~`. */
    var bracketedPaste: Boolean = false
        private set

    /** True while the alternate (full-screen TUI) buffer is displayed. */
    val isAlternateScreen: Boolean get() = onAlt

    // ── Screens ────────────────────────────────────────────────────────────

    private var normal = ScreenBuffer(this.cols, this.rows)
    private var alt = ScreenBuffer(this.cols, this.rows)
    private var onAlt = false
    private val screen: ScreenBuffer get() = if (onAlt) alt else normal
    private val cells: Array<Cell> get() = if (onAlt) alt.cells else normal.cells

    /** Cursor saved by `?1049h`, restored by `?1049l`. */
    private var altSavedCursor: IntArray? = null

    // ── Scroll region ──────────────────────────────────────────────────────

    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var originMode = false
    private var autowrap = true
    private var insertMode = false
    private var cursorVisibleFlag = true
    private var newLineMode = false
    private var wrapPending = false

    // ── Attributes ─────────────────────────────────────────────────────────

    private val pen = Pen()
    private var titleText = ""
    private var lastChar: Char = ' '
    private var lastWidth = 1

    // ── Scrollback ─────────────────────────────────────────────────────────

    private val scrollback = ArrayDeque<Array<Cell>>()

    // ── Output to the PTY ──────────────────────────────────────────────────

    private val pendingOut = java.io.ByteArrayOutputStream(64)

    // ── Parser state (all of it survives across write() calls) ──────────────

    private var state = St.GROUND
    private var escInter = -1
    private var escInterCount = 0
    private val params = Params()
    private var privateMarker = 0
    private val csiInter = StringBuilder(4)
    private val oscBuf = StringBuilder(128)

    // Incremental UTF-8 decoder state.
    private var utf8Need = 0
    private var utf8Acc = 0
    private var utf8Min = 0
    private var highSurrogate = 0

    private var dirty = false

    init {
        require(cols >= 1 && rows >= 1) { "terminal must be at least 1x1" }
    }

    // ═══════════════════════════════ Input ═════════════════════════════════

    /**
     * Feeds raw PTY bytes. Only the first [len] bytes are consumed; [len] is
     * clamped to the array bounds. UTF-8 sequences, escape sequences and
     * control characters may be split across calls without loss.
     */
    fun write(bytes: ByteArray, len: Int = bytes.size) {
        val end = if (len < 0) 0 else if (len > bytes.size) bytes.size else len
        for (i in 0 until end) {
            val b = bytes[i].toInt() and 0xFF
            // A byte may be rejected once and re-dispatched (bad UTF-8
            // continuation, or an ESC that aborted a string). The budget
            // bounds the loop even if a state ever failed to advance.
            var budget = 4
            while (budget-- > 0) {
                if (!step(b)) break
            }
        }
        flushUpdate()
    }

    /** Feeds a string, encoding it as UTF-8 first. */
    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Returns and clears the bytes the emulator wants to send back to the PTY
     * (device status reports, device attributes, ...). Call after each [write].
     */
    fun takePendingOutput(): ByteArray = pendingOut.toByteArray().also { pendingOut.reset() }

    // ══════════════════════════════ Screen ═════════════════════════════════

    /** The visible screen, row 0 = top. Every row has exactly [cols] cells. */
    fun screenLines(): List<List<Cell>> {
        val c = cells
        val out = ArrayList<List<Cell>>(rows)
        for (r in 0 until rows) {
            out.add(c.copyOfRange(r * cols, r * cols + cols).toList())
        }
        return out
    }

    /** A single visible cell; [row]/[col] are clamped into range. */
    fun cellAt(row: Int, col: Int): Cell =
        cells[row.coerceIn(0, rows - 1) * cols + col.coerceIn(0, cols - 1)]

    val cursorRow: Int get() = screen.cursorRow
    val cursorCol: Int get() = screen.cursorCol
    val cursorVisible: Boolean get() = cursorVisibleFlag

    /** The window title last set via OSC 0/1/2, or `""`. */
    fun title(): String = titleText

    // ════════════════════════════ Scrollback ═══════════════════════════════

    /** Lines that have scrolled off the top of the *normal* screen, oldest first. */
    fun scrollbackLines(): List<List<Cell>> = scrollback.map { it.toList() }

    /** Number of lines currently held in the scrollback ring. */
    fun scrollbackSize(): Int = scrollback.size

    // ══════════════════════════════ Resize ════════════════════════════════

    /**
     * Changes the terminal size. The overlapping region of both screens is
     * preserved; reflow is **not** performed.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        if (cols == this.cols && rows == this.rows) return
        this.cols = cols
        this.rows = rows
        normal = regrid(normal, cols, rows)
        alt = regrid(alt, cols, rows)
        scrollTop = scrollTop.coerceIn(0, rows - 1)
        scrollBottom = scrollBottom.coerceIn(scrollTop, rows - 1)
        wrapPending = false
        markDirty()
        flushUpdate()
    }

    private fun regrid(old: ScreenBuffer, cols: Int, rows: Int): ScreenBuffer {
        if (old.cols == cols && old.rows == rows) return old
        val n = ScreenBuffer(cols, rows)
        val keepRows = minOf(old.rows, rows)
        val keepCols = minOf(old.cols, cols)
        for (r in 0 until keepRows) {
            System.arraycopy(old.cells, r * old.cols, n.cells, r * cols, keepCols)
        }
        n.cursorRow = old.cursorRow.coerceIn(0, rows - 1)
        n.cursorCol = old.cursorCol.coerceIn(0, cols - 1)
        n.saved = old.saved
        return n
    }

    // ══════════════════════════ Parser: driver ═════════════════════════════

    /** @return true if [b] was rejected and must be re-dispatched in the new state. */
    private fun step(b: Int): Boolean {
        when (state) {
            St.GROUND -> {
                when {
                    b == 0x1B -> enterEscape()
                    b < 0x20 -> control(b)
                    b == 0x7F -> Unit // DEL is discarded
                    // 8-bit C1 controls. Only meaningful when no UTF-8 sequence
                    // is half-open, otherwise these are continuation bytes.
                    b in 0x80..0x9F && utf8Need == 0 && c1(b) -> Unit
                    else -> return feedUtf8(b)
                }
            }

            St.ESCAPE -> {
                when {
                    b == 0x1B -> enterEscape()
                    // String introducers. These are *not* ordinary ESC finals:
                    // they switch the parser into a string-consuming state.
                    b == 0x5B -> enterCsi() // CSI  ESC [
                    b == 0x5D -> enterOsc() // OSC  ESC ]
                    b == 0x50 -> enterString() // DCS  ESC P
                    b == 0x58 -> enterString() // SOS  ESC X
                    b == 0x5E -> enterString() // PM   ESC ^
                    b == 0x5F -> enterString() // APC  ESC _
                    b in 0x20..0x2F -> { // intermediate, e.g. `ESC (` `ESC #`
                        if (escInter < 0) escInter = b
                        escInterCount++
                    }
                    b in 0x30..0x7E -> {
                        dispatchEsc(b)
                        state = St.GROUND
                    }
                    b in 0x18..0x1F -> state = St.GROUND // CAN/SUB abort
                    b == 0x7F -> Unit
                    b == 0x9B -> enterCsi()
                    else -> state = St.GROUND
                }
            }

            St.CSI -> {
                when {
                    b in 0x30..0x39 -> params.digit(b - 0x30)
                    b == 0x3B -> params.separator(false)
                    b == 0x3A -> params.separator(true) // sub-parameter
                    b in 0x3C..0x3F -> { // private markers ? > < =
                        if (params.count == 0) privateMarker = b else return true.also { state = St.GROUND }
                    }
                    b in 0x20..0x2F -> { if (csiInter.length < 4) csiInter.append(b.toChar()) }
                    b in 0x40..0x7E -> {
                        dispatchCsi(b)
                        state = St.GROUND
                    }
                    b == 0x1B -> enterEscape()
                    b in 0x18..0x1F -> state = St.GROUND
                    b == 0x7F -> Unit
                    b == 0x9C -> state = St.GROUND // stray ST
                    else -> Unit // 0x80.. are padding inside a CSI; ignore
                }
            }

            St.OSC -> {
                when {
                    b == 0x07 -> { // BEL terminator
                        dispatchOsc()
                        state = St.GROUND
                    }
                    b == 0x9C -> {
                        dispatchOsc()
                        state = St.GROUND
                    }
                    b == 0x1B -> state = St.OSC_ESC
                    else -> if (oscBuf.length < MAX_OSC) oscBuf.append(b.toChar())
                }
            }

            St.OSC_ESC -> {
                when {
                    b == 0x5C -> { // ST
                        dispatchOsc()
                        state = St.GROUND
                    }
                    b == 0x1B -> Unit
                    else -> { // Not ST: the OSC was terminated early; re-dispatch.
                        oscBuf.setLength(0)
                        state = St.ESCAPE
                        return true
                    }
                }
            }

            St.STR -> {
                when {
                    b == 0x1B -> state = St.STR_ESC
                    b == 0x9C -> state = St.GROUND // ST
                    else -> Unit // payload discarded
                }
            }

            St.STR_ESC -> {
                when {
                    b == 0x5C -> state = St.GROUND // ST
                    b == 0x1B -> Unit
                    else -> { // Aborted: re-dispatch as a fresh escape sequence.
                        state = St.ESCAPE
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun enterEscape() {
        state = St.ESCAPE
        escInter = -1
        escInterCount = 0
    }

    private fun enterCsi() {
        state = St.CSI
        params.clear()
        privateMarker = 0
        csiInter.setLength(0)
    }

    private fun enterOsc() {
        state = St.OSC
        oscBuf.setLength(0)
    }

    private fun enterString() {
        state = St.STR
    }

    /**
     * Handles an 8-bit C1 control.
     *
     * @return true if [b] was a C1 control this emulator acts on. Codes it does
     *   not implement return false so the caller can fall through to the UTF-8
     *   decoder, which renders a lone 0x80..0x9F byte as U+FFFD rather than
     *   silently dropping it.
     */
    private fun c1(b: Int): Boolean {
        when (b) {
            0x84 -> { index(); markDirty(); return true }
            0x85 -> { carriageReturn(); index(); markDirty(); return true }
            0x90, 0x98, 0x9E, 0x9F -> { enterString(); return true } // DCS/SOS/PM/APC
            0x9B -> { enterCsi(); return true }
            0x9D -> { enterOsc(); return true }
            0x9C -> return true // ST: a no-op
            else -> return false
        }
    }

    // ══════════════════════════ Parser: UTF-8 ══════════════════════════════

    /**
     * Consumes one byte of a UTF-8 sequence. Returns true if the byte was an
     * invalid continuation — a U+FFFD has already been emitted and the caller
     * should re-dispatch the byte as the start of a new sequence.
     */
    private fun feedUtf8(b: Int): Boolean {
        if (utf8Need == 0) {
            when {
                b < 0x80 -> {
                    emitCodePoint(b)
                    return false
                }
                b < 0xC2 -> { // stray continuation byte, or an overlong C0/C1 lead
                    emitCodePoint(REPLACEMENT)
                    return false
                }
                b < 0xE0 -> startUtf8(1, b and 0x1F, 0x80)
                b < 0xF0 -> startUtf8(2, b and 0x0F, 0x800)
                b < 0xF5 -> startUtf8(3, b and 0x07, 0x10000)
                else -> { // 0xF5..0xFF can never start a scalar value
                    emitCodePoint(REPLACEMENT)
                    return false
                }
            }
            return false
        }
        if (b and 0xC0 != 0x80) {
            utf8Need = 0
            emitCodePoint(REPLACEMENT)
            return true // caller re-dispatches b
        }
        utf8Acc = (utf8Acc shl 6) or (b and 0x3F)
        if (--utf8Need == 0) {
            val cp = utf8Acc
            // Reject overlong forms, surrogates and out-of-range values.
            val ok = cp >= utf8Min && cp <= MAX_CODE_POINT && cp !in 0xD800..0xDFFF
            emitCodePoint(if (ok) cp else REPLACEMENT)
        }
        return false
    }

    private fun startUtf8(need: Int, acc: Int, min: Int) {
        utf8Need = need
        utf8Acc = acc
        utf8Min = min
    }

    /** Combines UTF-16 surrogate pairs so emoji get a single width decision. */
    private fun emitCodePoint(cp: Int) {
        if (highSurrogate != 0) {
            if (cp in 0xDC00..0xDFFF) {
                val full = 0x10000 + ((highSurrogate - 0xD800) shl 10) + (cp - 0xDC00)
                highSurrogate = 0
                putCodePoint(full)
                return
            }
            putCodePoint(REPLACEMENT)
            highSurrogate = 0
        }
        when (cp) {
            in 0xD800..0xDBFF -> highSurrogate = cp
            in 0xDC00..0xDFFF -> putCodePoint(REPLACEMENT)
            else -> putCodePoint(cp)
        }
    }

    // ══════════════════════════ Parser: C0 ════════════════════════════════

    private fun control(b: Int) {
        when (b) {
            0x07 -> Unit // BEL — ignored
            0x08 -> backspace()
            0x09 -> tab()
            0x0A, 0x0B, 0x0C -> lineFeed() // LF, VT, FF
            0x0D -> carriageReturn()
            else -> Unit // remaining C0 codes are ignored
        }
    }

    private fun backspace() {
        wrapPending = false
        val s = screen
        if (s.cursorCol > 0) s.cursorCol--
        markDirty()
    }

    private fun tab() {
        wrapPending = false
        val s = screen
        s.cursorCol = minOf(nextTabStop(s.cursorCol), cols - 1)
        markDirty()
    }

    /** Tab stops every 8 columns (no HTS support; matches the common default). */
    private fun nextTabStop(col: Int): Int = (col / TAB_WIDTH + 1) * TAB_WIDTH

    private fun carriageReturn() {
        wrapPending = false
        screen.cursorCol = 0
        markDirty()
    }

    private fun lineFeed() {
        wrapPending = false
        if (newLineMode) carriageReturn() // LNM
        index()
    }

    /** IND / LNM-aware newline. */
    private fun index() {
        val s = screen
        if (s.cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (s.cursorRow < rows - 1) {
            s.cursorRow++
        }
        markDirty()
    }

    private fun reverseIndex() {
        wrapPending = false
        val s = screen
        if (s.cursorRow == scrollTop) scrollDown(1)
        else if (s.cursorRow > 0) s.cursorRow--
        markDirty()
    }

    // ══════════════════════════ Printing ══════════════════════════════════

    private fun putCodePoint(cp: Int) {
        if (isZeroWidthCodePoint(cp)) {
            appendCombining(cp)
            return
        }
        if (cp > 0xFFFF) {
            // Astral code point: store the high surrogate in `char` and the low
            // one in `combining`, so `Cell.text` reassembles the pair.
            val v = cp - 0x10000
            put((0xD800 + (v shr 10)).toChar(), 2, (0xDC00 + (v and 0x3FF)).toChar().toString())
        } else {
            put(cp.toChar(), if (isWideCodePoint(cp)) 2 else 1, null)
        }
    }

    /**
     * Writes one grapheme. [lead] is its first UTF-16 unit and [trail] holds any
     * extra units that belong to the same cell (the low surrogate of an astral
     * code point). [width] is the number of columns consumed.
     */
    private fun put(lead: Char, width: Int, trail: String?) {
        val s = screen
        if (wrapPending) {
            s.cursorCol = 0
            index()
            wrapPending = false
        }
        var col = s.cursorCol
        // A double-width character that does not fit wraps to the next line
        // first, so the pair is never split across lines.
        if (width == 2 && col + 2 > cols) {
            if (!autowrap) return
            col = 0
            index()
        }
        val row = s.cursorRow
        val c = cells
        val i = row * cols + col

        if (insertMode) {
            val move = minOf(width, cols - col)
            System.arraycopy(c, i, c, i + move, cols - col - move)
            val blank = BLANK_CELL
            for (k in 0 until move) c[i + k] = blank
        }

        // Overwriting half of an existing wide character blanks the orphan.
        if (c[i].wide == WideState.Continuation && col > 0) c[i - 1] = pen.eraseCell()
        if (width == 1 && col + 1 < cols && c[i + 1].wide == WideState.Continuation) {
            c[i + 1] = pen.eraseCell()
        }

        val head = pen.applyTo(Cell(lead, combining = trail))
        c[i] = head
        if (width == 2) {
            c[i + 1] = pen.applyTo(Cell(' ', wide = WideState.Continuation))
        }
        lastChar = lead
        lastWidth = width
        wrapPending = false

        col += width
        if (col >= cols) {
            col = cols - 1
            wrapPending = autowrap // deferred wrap: stay on the last column
        }
        s.cursorCol = col
        markDirty()
    }

    /**
     * Attaches a zero-width mark to the cell the cursor just left, so it
     * composes in place instead of advancing the cursor.
     */
    private fun appendCombining(cp: Int) {
        val s = screen
        var r = s.cursorRow
        var col = s.cursorCol - 1
        if (col < 0) {
            if (!wrapPending) return
            r--
            col = cols - 1
        }
        if (r < 0 || r >= rows) return
        val c = cells
        if (col > 0 && c[r * cols + col].wide == WideState.Continuation) col--
        val i = r * cols + col
        val cur = c[i]
        c[i] = cur.copy(combining = (cur.combining ?: "") + cp.toChar())
        markDirty()
    }

    private fun repeatLast(n: Int) {
        if (n < 1) return
        repeat(n) { put(lastChar, lastWidth, null) }
    }

    // ══════════════════════════ Scrolling ══════════════════════════════════

    private fun scrollUp(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        if (count <= 0) return
        val c = cells
        val fullRegion = scrollTop == 0 && scrollBottom == rows - 1
        if (fullRegion && !onAlt) {
            for (i in 0 until count) pushScrollback(c, (scrollTop + i) * cols)
        }
        val copyLen = (scrollBottom - scrollTop + 1 - count) * cols
        if (copyLen > 0) {
            System.arraycopy(c, (scrollTop + count) * cols, c, scrollTop * cols, copyLen)
        }
        blankRows(scrollBottom - count + 1, count)
        markDirty()
    }

    private fun scrollDown(n: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        if (count <= 0) return
        val c = cells
        val copyLen = (scrollBottom - scrollTop + 1 - count) * cols
        if (copyLen > 0) {
            System.arraycopy(c, scrollTop * cols, c, (scrollTop + count) * cols, copyLen)
        }
        blankRows(scrollTop, count)
        markDirty()
    }

    private fun blankRows(from: Int, count: Int) {
        val c = cells
        val blank = pen.eraseCell()
        for (r in from until from + count) {
            for (x in 0 until cols) c[r * cols + x] = blank
        }
    }

    /** Copies a screen line into the ring buffer, recycling the evicted array. */
    private fun pushScrollback(c: Array<Cell>, src: Int) {
        if (maxScrollback <= 0) return
        val recycled = if (scrollback.size >= maxScrollback) scrollback.removeFirst() else null
        val buf = if (recycled != null && recycled.size == cols) recycled else Array(cols) { BLANK_CELL }
        System.arraycopy(c, src, buf, 0, cols)
        scrollback.addLast(buf)
    }

    // ══════════════════════════ Erasing ════════════════════════════════════

    private fun eraseRange(row: Int, from: Int, toInclusive: Int) {
        if (toInclusive < from) return
        val c = cells
        val blank = pen.eraseCell()
        val base = row * cols
        for (x in from..toInclusive) {
            // Keep wide pairs from being half-erased.
            if (c[base + x].wide == WideState.Continuation && x > 0) c[base + x - 1] = blank
            if (x + 1 < cols && c[base + x + 1].wide == WideState.Continuation) c[base + x + 1] = blank
            c[base + x] = blank
        }
    }

    private fun eraseInDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> {
                eraseRange(s.cursorRow, s.cursorCol, cols - 1)
                for (r in s.cursorRow + 1 until rows) eraseRange(r, 0, cols - 1)
            }
            1 -> {
                for (r in 0 until s.cursorRow) eraseRange(r, 0, cols - 1)
                eraseRange(s.cursorRow, 0, s.cursorCol)
            }
            2 -> for (r in 0 until rows) eraseRange(r, 0, cols - 1)
            3 -> scrollback.clear()
            else -> return
        }
        markDirty()
    }

    private fun eraseInLine(mode: Int) {
        val s = screen
        when (mode) {
            0 -> eraseRange(s.cursorRow, s.cursorCol, cols - 1)
            1 -> eraseRange(s.cursorRow, 0, s.cursorCol)
            2 -> eraseRange(s.cursorRow, 0, cols - 1)
            else -> return
        }
        markDirty()
    }

    // ══════════════════════════ Line editing ══════════════════════════════

    private fun insertLines(n: Int) {
        if (screen.cursorRow < scrollTop || screen.cursorRow > scrollBottom) return
        val count = minOf(n, scrollBottom - screen.cursorRow + 1)
        val c = cells
        val base = screen.cursorRow * cols
        val moveRows = scrollBottom - screen.cursorRow + 1 - count
        if (moveRows > 0) System.arraycopy(c, base, c, base + count * cols, moveRows * cols)
        blankRows(screen.cursorRow, count)
        screen.cursorCol = 0
        markDirty()
    }

    private fun deleteLines(n: Int) {
        if (screen.cursorRow < scrollTop || screen.cursorRow > scrollBottom) return
        val count = minOf(n, scrollBottom - screen.cursorRow + 1)
        val c = cells
        val base = screen.cursorRow * cols
        val moveRows = scrollBottom - screen.cursorRow + 1 - count
        if (moveRows > 0) System.arraycopy(c, base + count * cols, c, base, moveRows * cols)
        blankRows(scrollBottom - count + 1, count)
        screen.cursorCol = 0
        markDirty()
    }

    private fun insertChars(n: Int) {
        val s = screen
        val count = minOf(n, cols - s.cursorCol)
        if (count <= 0) return
        val c = cells
        val i = s.cursorRow * cols + s.cursorCol
        System.arraycopy(c, i, c, i + count, cols - s.cursorCol - count)
        for (k in 0 until count) c[i + k] = BLANK_CELL
        markDirty()
    }

    private fun deleteChars(n: Int) {
        val s = screen
        val count = minOf(n, cols - s.cursorCol)
        if (count <= 0) return
        val c = cells
        val i = s.cursorRow * cols + s.cursorCol
        val keep = cols - s.cursorCol - count
        System.arraycopy(c, i + count, c, i, keep)
        for (k in keep until cols - s.cursorCol) c[i + k] = BLANK_CELL
        markDirty()
    }

    // ══════════════════════════ Cursor moves ══════════════════════════════

    private fun moveCursor(row: Int, col: Int) {
        val s = screen
        val lo = if (originMode) scrollTop else 0
        val hi = if (originMode) scrollBottom else rows - 1
        s.cursorRow = row.coerceIn(lo, hi)
        s.cursorCol = col.coerceIn(0, cols - 1)
        wrapPending = false
        markDirty()
    }

    private fun moveCursorBy(dRow: Int, dCol: Int) =
        moveCursor(screen.cursorRow + dRow, screen.cursorCol + dCol)

    // ══════════════════════════ ESC dispatch ══════════════════════════════

    private fun dispatchEsc(final: Int) {
        when {
            escInterCount == 0 -> when (final.toChar()) {
                'c' -> hardReset()
                'D' -> index()
                'M' -> reverseIndex()
                'E' -> { carriageReturn(); index() }
                '7' -> saveCursor()
                '8' -> restoreCursor()
                '=', '>' -> Unit // DEC keypad application mode: key handling
                'H' -> Unit // HTS: fixed 8-column tab stops
                else -> Unit
            }
            // ESC # 8 — DECALN, mostly a conformance-test aid but handy here.
            escInter == 0x23 && final == 0x38 -> decaln()
            else -> Unit // charset designators, DEC line attributes, ...
        }
        markDirty()
    }

    private fun decaln() {
        val c = cells
        val fill = pen.applyTo(Cell('E'))
        for (i in c.indices) c[i] = fill
        screen.cursorRow = 0
        screen.cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        markDirty()
    }

    private fun saveCursor() {
        val s = screen
        s.saved = pen.snapshot(s.cursorRow, s.cursorCol)
        markDirty()
    }

    private fun restoreCursor() {
        val s = screen
        val saved = s.saved
        if (saved != null) {
            pen.restore(saved)
            s.cursorRow = saved.row.coerceIn(0, rows - 1)
            s.cursorCol = saved.col.coerceIn(0, cols - 1)
        }
        wrapPending = false
        markDirty()
    }

    private fun hardReset() {
        normal = ScreenBuffer(cols, rows)
        alt = ScreenBuffer(cols, rows)
        onAlt = false
        altSavedCursor = null
        scrollback.clear()
        pen.reset()
        titleText = ""
        scrollTop = 0
        scrollBottom = rows - 1
        originMode = false
        autowrap = true
        insertMode = false
        newLineMode = false
        cursorVisibleFlag = true
        bracketedPaste = false
        wrapPending = false
        state = St.GROUND
        markDirty()
    }

    // ══════════════════════════ CSI dispatch ══════════════════════════════

    private fun dispatchCsi(final: Int) {
        // Any intermediate byte means a sequence we do not implement; consume it.
        if (csiInter.isNotEmpty()) return

        if (privateMarker != 0) {
            when (final.toChar()) {
                'h' -> setPrivateModes(true)
                'l' -> setPrivateModes(false)
                'c' -> if (privateMarker == '>'.code) replyDa2()
                'q' -> if (privateMarker == '>'.code) replyDa2()
                // DECRQM (`$p`) and everything else private: ignored.
                else -> Unit
            }
            return
        }

        when (final.toChar()) {
            '@' -> insertChars(arg(0))
            'A' -> moveCursorBy(-arg(0), 0)
            'B' -> moveCursorBy(arg(0), 0)
            'C' -> moveCursorBy(0, arg(0))
            'D' -> moveCursorBy(0, -arg(0))
            'E' -> { moveCursorBy(arg(0), 0); screen.cursorCol = 0 }
            'F' -> { moveCursorBy(-arg(0), 0); screen.cursorCol = 0 }
            'G' -> moveCursor(screen.cursorRow, arg(0) - 1)
            'H', 'f' -> moveCursor(arg(0) - 1, arg(1) - 1)
            'J' -> eraseInDisplay(raw(0, 0))
            'K' -> eraseInLine(raw(0, 0))
            'L' -> insertLines(arg(0))
            'M' -> deleteLines(arg(0))
            'P' -> deleteChars(arg(0))
            'S' -> scrollUp(arg(0))
            'T' -> scrollDown(arg(0))
            'X' -> eraseChars(arg(0))
            'b' -> repeatLast(arg(0))
            'c' -> replyDa1()
            'd' -> moveCursor(arg(0) - 1, screen.cursorCol)
            'h' -> setAnsiModes(true)
            'l' -> setAnsiModes(false)
            'm' -> applySgr()
            'n' -> deviceStatus(raw(0, 0))
            'r' -> setScrollRegion()
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
        markDirty()
    }

    private fun eraseChars(n: Int) {
        val s = screen
        eraseRange(s.cursorRow, s.cursorCol, minOf(cols - 1, s.cursorCol + n - 1))
    }

    /** Raw parameter [i], or [def] when the sequence supplied fewer values. */
    private fun raw(i: Int, def: Int): Int = if (i < params.count) params[i] else def

    /** Parameter [i] as a count: omitted or 0 means 1. */
    private fun arg(i: Int): Int {
        val v = raw(i, 1)
        return if (v == 0) 1 else v
    }

    // ══════════════════════════ Modes ══════════════════════════════════════

    private fun setAnsiModes(set: Boolean) {
        for (i in 0 until params.count) {
            when (params[i]) {
                4 -> insertMode = set // IRM
                20 -> newLineMode = set // LNM
            }
        }
        markDirty()
    }

    private fun setPrivateModes(set: Boolean) {
        for (i in 0 until params.count) {
            when (params[i]) {
                6 -> originMode = set // DECOM
                7 -> { autowrap = set; if (!set) wrapPending = false }
                25 -> cursorVisibleFlag = set
                47, 1047 -> useAlternateScreen(set, withCursor = false)
                1048 -> if (set) saveCursor() else restoreCursor()
                1049 -> useAlternateScreen(set, withCursor = true)
                2004 -> bracketedPaste = set
            }
        }
        markDirty()
    }

    /**
     * Switches between the normal and alternate buffers. The alternate buffer
     * never feeds the scrollback, which is what keeps vim/htop from flooding
     * it, and the normal buffer is left untouched so switching back restores
     * the previous screen exactly.
     */
    private fun useAlternateScreen(on: Boolean, withCursor: Boolean) {
        if (on == onAlt) {
            if (on && withCursor) saveCursor()
            return
        }
        if (on) {
            if (withCursor) {
                val n = normal
                altSavedCursor = intArrayOf(n.cursorRow, n.cursorCol)
            }
            val a = alt
            for (i in a.cells.indices) a.cells[i] = BLANK_CELL
            a.cursorRow = 0
            a.cursorCol = 0
            a.saved = null
            onAlt = true
        } else {
            onAlt = false
            val saved = altSavedCursor
            if (withCursor && saved != null) {
                normal.cursorRow = saved[0].coerceIn(0, rows - 1)
                normal.cursorCol = saved[1].coerceIn(0, cols - 1)
                altSavedCursor = null
            }
        }
        wrapPending = false
        markDirty()
    }

    private fun setScrollRegion() {
        val top = raw(0, 1) - 1
        val bottom = if (params.count >= 2) raw(1, rows) else rows
        if (bottom <= top || bottom > rows || top < 0) {
            // Invalid region: restore the full screen, as xterm does.
            scrollTop = 0
            scrollBottom = rows - 1
        } else {
            scrollTop = top
            scrollBottom = bottom - 1
        }
        screen.cursorRow = if (originMode) scrollTop else 0
        screen.cursorCol = 0
        wrapPending = false
    }

    // ══════════════════════════ SGR ════════════════════════════════════════

    private fun applySgr() {
        val n = params.count
        if (n == 0) {
            pen.reset()
            return
        }
        var i = 0
        while (i < n) {
            // A colon-separated group: 4:3, 38:5:n, 38:2::r:g:b, ...
            var j = i + 1
            while (j < n && params.isSub(j)) j++
            val groupLen = j - i
            val v = params[i]

            if (groupLen > 1) {
                when (v) {
                    4 -> pen.underline = params[i + 1] != 0
                    38, 48 -> applyColonColor(v, i, j - i)
                    58, 59 -> Unit // underline colour: consumed and ignored
                    else -> applySimpleSgr(v)
                }
                i = j
                continue
            }

            when (v) {
                // Extended colours, legacy `;` form: 38;5;n / 38;2;r;g;b
                38, 48, 58 -> {
                    val mode = if (i + 1 < n) params[i + 1] else -1
                    i += 1 + when (mode) {
                        5 -> {
                            if (i + 2 < n) setColorIndexed(v, params[i + 2])
                            2 // mode + palette index
                        }
                        2 -> {
                            if (i + 4 < n) setColorRgb(v, params[i + 2], params[i + 3], params[i + 4])
                            4 // mode + r + g + b
                        }
                        else -> 0
                    }
                    continue
                }
                else -> applySimpleSgr(v)
            }
            i++
        }
    }

    private fun applySimpleSgr(v: Int) {
        when (v) {
            0 -> pen.reset()
            1 -> pen.bold = true
            2 -> pen.faint = true
            3 -> pen.italic = true
            4 -> pen.underline = true
            7 -> pen.reverse = true
            9 -> pen.strike = true
            21 -> pen.bold = false
            22 -> { pen.bold = false; pen.faint = false }
            23 -> pen.italic = false
            24 -> pen.underline = false
            27 -> pen.reverse = false
            29 -> pen.strike = false
            39 -> pen.fg = DEFAULT_FG
            49 -> pen.bg = DEFAULT_BG
            in 30..37 -> pen.fg = paletteIndex(v - 30)
            in 40..47 -> pen.bg = paletteIndex(v - 40)
            in 90..97 -> pen.fg = paletteIndex(v - 90 + 8)
            in 100..107 -> pen.bg = paletteIndex(v - 100 + 8)
            // 5/6 blink, 8 conceal, 10..19 fonts, 21 handled above, 26, 50..56,
            // 59, 73/74: accepted and ignored.
            else -> Unit
        }
    }

    /** `38:5:n`, `38:2:r:g:b` and `38:2:<id>:r:g:b`. */
    private fun applyColonColor(base: Int, start: Int, len: Int) {
        if (len < 3) return
        when (params[start + 1]) {
            5 -> setColorIndexed(base, params[start + 2])
            2 -> {
                val off = if (len >= 6) start + 3 else start + 2
                if (off + 2 < start + len) {
                    setColorRgb(base, params[off], params[off + 1], params[off + 2])
                }
            }
        }
    }

    private fun setColorIndexed(base: Int, index: Int) {
        val tagged = paletteIndex(index)
        if (base == 48) pen.bg = tagged else pen.fg = tagged
    }

    private fun setColorRgb(base: Int, r: Int, g: Int, b: Int) {
        val packed = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        if (base == 48) pen.bg = packed else pen.fg = packed
    }

    // ══════════════════════════ OSC ════════════════════════════════════════

    private fun dispatchOsc() {
        if (oscBuf.isEmpty()) return
        // Copy before clearing: `oscBuf` is a mutable buffer, not a snapshot.
        val s = oscBuf.toString()
        oscBuf.setLength(0)
        val sep = s.indexOfFirst { it == ';' || it == ':' }
        if (sep <= 0) return
        val code = s.substring(0, sep).toIntOrNull() ?: return
        if (code == 0 || code == 1 || code == 2) titleText = s.substring(sep + 1)
        // Every other OSC (4, 8, 10..19, 52 clipboard, 104, ...) is consumed
        // above and deliberately discarded.
    }

    // ══════════════════════════ Device reports ═════════════════════════════

    private fun deviceStatus(mode: Int) {
        when (mode) {
            5 -> report("\u001B[0n")
            6 -> report("${cpr()}")
        }
    }

    private fun cpr(): String {
        val r = screen.cursorRow + 1
        val c = screen.cursorCol + 1
        return "\u001B[$r;${c}R"
    }

    private fun replyDa1() = report("\u001B[?1;2c")

    private fun replyDa2() = report("\u001B[>0;10;1c")

    private fun report(s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        pendingOut.write(b, 0, b.size)
    }

    // ══════════════════════════ Redraw signalling ══════════════════════════

    private fun markDirty() {
        dirty = true
    }

    private fun flushUpdate() {
        if (!dirty) return
        dirty = false
        onUpdate?.invoke()
    }

    private companion object {
        const val TAB_WIDTH = 8
        const val MAX_OSC = 8192
        const val MAX_CODE_POINT: Int = 0x10FFFF
    }
}
