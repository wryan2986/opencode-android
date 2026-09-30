package dev.ryan.opencode.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Unit tests for the pure-Kotlin terminal core. These run on a plain JVM — the
 * emulator deliberately has no Android dependencies.
 */
class TerminalEmulatorTest {

    // ── helpers ────────────────────────────────────────────────────────────

    private fun TerminalEmulator.rowText(row: Int): String =
        screenLines()[row].map { it.char }.joinToString("")

    /** [rowText] with trailing blanks removed, for "what text is on this row" checks. */
    private fun TerminalEmulator.rowTrim(row: Int): String = rowText(row).trimEnd()

    /** Like [rowText] but includes combining marks and surrogate tails. */
    private fun TerminalEmulator.rowGraphemes(row: Int): String =
        screenLines()[row].joinToString("") { if (it.isContinuation) "" else it.text }

    private fun out(e: TerminalEmulator): String = String(e.takePendingOutput(), Charsets.UTF_8)

    // ── printing / cursor ──────────────────────────────────────────────────

    @Test
    fun plainTextAdvancesCursor() {
        val e = TerminalEmulator(10, 3)
        e.write("hello")
        assertEquals(0, e.cursorRow)
        assertEquals(5, e.cursorCol)
        assertEquals("hello     ", e.rowText(0))
    }

    @Test
    fun plainTextWrapsAtRightMargin() {
        val e = TerminalEmulator(5, 3)
        e.write("abcdefghij")
        assertEquals("abcde", e.rowText(0))
        assertEquals("fghij", e.rowText(1))
        // The cursor parks on the last column with the wrap still pending.
        assertEquals(1, e.cursorRow)
        assertEquals(4, e.cursorCol)
        // The pending wrap materialises on the next printable character.
        e.write("k")
        assertEquals(2, e.cursorRow)
        assertEquals(1, e.cursorCol)
        assertEquals("k", e.rowTrim(2))
    }

    @Test
    fun carriageReturnLineFeedReturnsToColumnZeroAndNextLine() {
        val e = TerminalEmulator(10, 3)
        e.write("ab\r\ncd")
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
        assertEquals("ab        ", e.rowText(0))
        assertEquals("cd        ", e.rowText(1))
    }

    @Test
    fun backspaceAndTabUseEightColumnTabStops() {
        val e = TerminalEmulator(20, 2)
        e.write("ab\b")
        assertEquals(1, e.cursorCol)
        e.write("\t")
        assertEquals(8, e.cursorCol)
        e.write("\t")
        assertEquals(16, e.cursorCol)
        // Past the last tab stop we stop on the final column.
        e.write("\t")
        assertEquals(19, e.cursorCol)
    }

    @Test
    fun scrollAtBottomPushesIntoScrollback() {
        val e = TerminalEmulator(6, 2, maxScrollback = 10)
        e.write("1\r\n2\r\n3\r\n4")
        assertEquals(2, e.scrollbackSize())
        assertEquals(
            listOf("1", "2"),
            e.scrollbackLines().map { it.map { c -> c.char }.joinToString("").trim() },
        )
        assertEquals("3", e.rowTrim(0))
        assertEquals("4", e.rowTrim(1))
    }

    @Test
    fun screenRowsAreAlwaysExactlyColsWide() {
        val e = TerminalEmulator(7, 4)
        e.write("x".repeat(200))
        val lines = e.screenLines()
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.size == 7 })
    }

    @Test
    fun repeatCommandRepeatsTheLastGrapheme() {
        val e = TerminalEmulator(10, 2)
        e.write("A\u001B[3b")
        assertEquals("AAAA", e.rowTrim(0))
        assertEquals(4, e.cursorCol)
    }

    // ── CSI: cursor movement ───────────────────────────────────────────────

    @Test
    fun cupPositionsTheCursorOneBased() {
        val e = TerminalEmulator(20, 10)
        e.write("\u001B[5;10H")
        assertEquals(4, e.cursorRow)
        assertEquals(9, e.cursorCol)
    }

    @Test
    fun cupClampsOutOfRangeValues() {
        val e = TerminalEmulator(20, 10)
        e.write("\u001B[99;99H")
        assertEquals(9, e.cursorRow)
        assertEquals(19, e.cursorCol)
        e.write("\u001B[0;0H")
        assertEquals(0, e.cursorRow)
        assertEquals(0, e.cursorCol)
    }

    @Test
    fun cursorRelativeMovement() {
        val e = TerminalEmulator(20, 10)
        e.write("\u001B[5;5H")
        e.write("\u001B[2A") // up 2
        assertEquals(2, e.cursorRow)
        e.write("\u001B[3B") // down 3
        assertEquals(5, e.cursorRow)
        e.write("\u001B[4C") // right 4
        assertEquals(8, e.cursorCol)
        e.write("\u001B[2D") // left 2
        assertEquals(6, e.cursorCol)
        e.write("\u001B[1E") // next line: down 1, column 0
        assertEquals(6, e.cursorRow)
        assertEquals(0, e.cursorCol)
        e.write("\u001B[1F") // previous line
        assertEquals(5, e.cursorRow)
        assertEquals(0, e.cursorCol)
        e.write("\u001B[7G") // CHA
        assertEquals(6, e.cursorCol)
        e.write("\u001B[3d") // VPA
        assertEquals(2, e.cursorRow)
        assertEquals(6, e.cursorCol)
    }

    @Test
    fun cursorSequenceSplitAcrossWritesIsReassembled() {
        val e = TerminalEmulator(20, 10)
        e.write("\u001B[")
        e.write("5;")
        e.write("10")
        e.write("H")
        assertEquals(4, e.cursorRow)
        assertEquals(9, e.cursorCol)
    }

    // ── CSI: erasing ───────────────────────────────────────────────────────

    @Test
    fun eraseInLineModes() {
        val e = TerminalEmulator(8, 2)
        e.write("\u001B[1;1Habcdefgh")
        e.write("\u001B[1;3H\u001B[K")
        assertEquals("ab      ", e.rowText(0))

        e.write("\u001B[2J\u001B[1;1Habcdefgh")
        e.write("\u001B[1;3H\u001B[1K")
        assertEquals("   defgh", e.rowText(0))

        e.write("\u001B[2J\u001B[1;1Habcdefgh")
        e.write("\u001B[1;3H\u001B[2K")
        assertEquals("        ", e.rowText(0))
    }

    @Test
    fun eraseInDisplayClearsScreenButPreservesScrollback() {
        val e = TerminalEmulator(6, 2, maxScrollback = 10)
        e.write("1\r\n2\r\n3\r\n4")
        assertEquals(2, e.scrollbackSize())
        val scrollbackBefore = e.scrollbackLines()

        e.write("\u001B[2J")
        assertEquals(2, e.scrollbackSize())
        assertEquals(scrollbackBefore, e.scrollbackLines())
        assertEquals("      ", e.rowText(0))
        assertEquals("      ", e.rowText(1))
    }

    @Test
    fun eraseInDisplayModeThreeClearsScrollbackOnly() {
        val e = TerminalEmulator(6, 2, maxScrollback = 10)
        e.write("1\r\n2\r\n3\r\n4")
        assertTrue(e.scrollbackSize() > 0)
        e.write("\u001B[3J")
        // Unlike mode 2, mode 3 leaves the visible screen alone.
        assertEquals(0, e.scrollbackSize())
        assertEquals("3", e.rowTrim(0))
        assertEquals("4", e.rowTrim(1))
    }

    @Test
    fun eraseInDisplayToEndAndToStart() {
        val e = TerminalEmulator(6, 3)
        e.write("aaaaaa\r\nbbbbbb\r\ncccccc")
        e.write("\u001B[2;3H\u001B[0J")
        assertEquals("aaaaaa", e.rowTrim(0))
        assertEquals("bb", e.rowTrim(1))
        assertEquals("", e.rowTrim(2))

        e.write("\u001B[2J\u001B[1;1Haaaaaa\r\nbbbbbb\r\ncccccc")
        e.write("\u001B[2;3H\u001B[1J")
        assertEquals("", e.rowTrim(0))
        // Erase-to-start is inclusive of the cursor cell, so columns 0..2 go.
        assertEquals("   bbb", e.rowTrim(1))
        assertEquals("cccccc", e.rowTrim(2))
    }

    // ── CSI: line / character editing ──────────────────────────────────────

    @Test
    fun insertAndDeleteLinesShiftContent() {
        val e = TerminalEmulator(4, 4)
        e.write("\u001B[1;1HA\u001B[2;1HB\u001B[3;1HC\u001B[4;1HD")
        assertEquals(listOf("A", "B", "C", "D"), (0..3).map { e.rowTrim(it) })

        e.write("\u001B[2;1H\u001B[L")
        assertEquals(listOf("A", "", "B", "C"), (0..3).map { e.rowTrim(it) })

        e.write("\u001B[2;1H\u001B[M")
        assertEquals(listOf("A", "B", "C", ""), (0..3).map { e.rowTrim(it) })
    }

    @Test
    fun insertAndDeleteLinesWithCount() {
        val e = TerminalEmulator(4, 4)
        e.write("\u001B[1;1HA\u001B[2;1HB\u001B[3;1HC\u001B[4;1HD")
        e.write("\u001B[1;1H\u001B[2L")
        assertEquals(listOf("", "", "A", "B"), (0..3).map { e.rowTrim(it) })
        e.write("\u001B[1;1H\u001B[2M")
        assertEquals(listOf("A", "B", "", ""), (0..3).map { e.rowTrim(it) })
    }

    @Test
    fun insertAndDeleteCharacters() {
        val e = TerminalEmulator(8, 2)
        e.write("abcdefgh")
        e.write("\u001B[1;3H\u001B[2@")
        assertEquals("ab  cdef", e.rowText(0))

        e.write("\u001B[2J\u001B[1;1Habcdefgh")
        e.write("\u001B[1;3H\u001B[2P")
        assertEquals("abefgh  ", e.rowText(0))

        // A count wider than the remaining line is clamped, never overrun.
        e.write("\u001B[2J\u001B[1;1Habcdefgh")
        e.write("\u001B[1;3H\u001B[99@")
        assertEquals("ab      ", e.rowText(0))
    }

    @Test
    fun eraseCharacters() {
        val e = TerminalEmulator(8, 2)
        e.write("abcdefgh")
        e.write("\u001B[1;2H\u001B[2X")
        assertEquals("a  defgh", e.rowText(0))
    }

    @Test
    fun insertModeShiftsCharactersRight() {
        val e = TerminalEmulator(6, 2)
        e.write("abcdef")
        e.write("\u001B[1;1H\u001B[4hXY") // IRM set: characters push right
        assertEquals("XYabcd", e.rowText(0))
        e.write("\u001B[4l") // IRM reset: characters overwrite
        e.write("\u001B[1;1HZ")
        assertEquals("ZYabcd", e.rowText(0))
    }

    // ── SGR ────────────────────────────────────────────────────────────────

    @Test
    fun sgrColourBoldAndReset() {
        val e = TerminalEmulator(10, 2)
        e.write("\u001B[1;31;44mX\u001B[0mY")

        val x = e.cellAt(0, 0)
        assertEquals('X', x.char)
        assertTrue(x.bold)
        assertFalse(x.faint)
        assertEquals(paletteIndex(1), x.fg)
        assertEquals(paletteIndex(4), x.bg)

        val y = e.cellAt(0, 1)
        assertEquals('Y', y.char)
        // Every attribute is back to its default.
        assertEquals(Cell('Y'), y)
    }

    @Test
    fun sgrExtendedAttributesAndIndividualTurnOff() {
        val e = TerminalEmulator(10, 2)
        e.write("\u001B[1;2;3;4;7;9mA")
        val a = e.cellAt(0, 0)
        assertTrue(a.bold && a.faint && a.italic && a.underline && a.reverse && a.strike)

        e.write("\u001B[22;23;24;27;29mB")
        assertEquals(Cell('B'), e.cellAt(0, 1))

        e.write("\u001B[1;4mC\u001B[21mD")
        assertFalse(e.cellAt(0, 3).bold)
        assertTrue(e.cellAt(0, 3).underline)
    }

    @Test
    fun sgrBrightAnd256AndTrueColour() {
        val e = TerminalEmulator(20, 2)
        e.write("\u001B[91;102mA")
        assertEquals(paletteIndex(9), e.cellAt(0, 0).fg)
        assertEquals(paletteIndex(10), e.cellAt(0, 0).bg)

        e.write("\u001B[38;5;208;48;5;17mB")
        assertEquals(paletteIndex(208), e.cellAt(0, 1).fg)
        assertEquals(paletteIndex(17), e.cellAt(0, 1).bg)

        e.write("\u001B[38;2;18;52;86mC")
        assertEquals(0x123456, e.cellAt(0, 2).fg)
        assertFalse(isPaletteIndex(e.cellAt(0, 2).fg))

        e.write("\u001B[38:2::10:20:30;48:5:99mD")
        assertEquals(0x0A141E, e.cellAt(0, 3).fg)
        assertEquals(paletteIndex(99), e.cellAt(0, 3).bg)

        e.write("\u001B[38:5:120mE")
        assertEquals(paletteIndex(120), e.cellAt(0, 4).fg)

        e.write("\u001B[39;49mF")
        assertEquals(Cell('F'), e.cellAt(0, 5))
    }

    @Test
    fun paletteLookupAndEncodingDoNotCollide() {
        // 0..7 classic VT100/VGA, 8..15 the bright variants.
        assertEquals(0x000000, Xterm256.rgb(0))
        assertEquals(0x800000, Xterm256.rgb(1))
        assertEquals(0xC0C0C0, Xterm256.rgb(7))
        assertEquals(0xFF0000, Xterm256.rgb(9))
        assertEquals(0x00FFFF, Xterm256.rgb(14))
        assertEquals(0xFFFFFF, Xterm256.rgb(15))
        // 6x6x6 cube: 16 is black, 231 white, and the primaries sit at 196/46/21.
        assertEquals(0x000000, Xterm256.rgb(16))
        assertEquals(0xFF0000, Xterm256.rgb(196))
        assertEquals(0x00FF00, Xterm256.rgb(46))
        assertEquals(0x0000FF, Xterm256.rgb(21))
        assertEquals(0xFFFFFF, Xterm256.rgb(231))
        assertEquals(0xFF0000, Xterm256.cubeRgb(5, 0, 0))
        // Then the greyscale ramp.
        assertEquals(0x080808, Xterm256.rgb(232))
        assertEquals(0xEEEEEE, Xterm256.rgb(255))
        // The tagged palette encoding can never be mistaken for packed RGB.
        assertTrue(isPaletteIndex(paletteIndex(200)))
        assertFalse(isPaletteIndex(0x123456))
        assertFalse(isPaletteIndex(DEFAULT_FG))
        assertFalse(isPaletteIndex(DEFAULT_BG))
        assertEquals(200, paletteIndex(200) and PALETTE_INDEX_MASK)
    }

    // ── Scrolling ──────────────────────────────────────────────────────────

    @Test
    fun scrollRegionLimitsLineFeedScrolling() {
        val e = TerminalEmulator(6, 5, maxScrollback = 50)
        e.write("\u001B[2;5r") // region = rows 1..4 (0-based)
        e.write("\u001B[1;1HA")
        e.write("\u001B[5;1HB")
        e.write("\n") // at the region bottom => the region scrolls, not the screen

        assertEquals(0, e.scrollbackSize())
        assertEquals('A', e.cellAt(0, 0).char) // row 0 is outside the region
        assertEquals('B', e.cellAt(3, 0).char) // row 4 content moved up to row 3
        assertEquals(' ', e.cellAt(4, 0).char) // last region row is blank
    }

    @Test
    fun scrollRegionRejectsInvalidBounds() {
        val e = TerminalEmulator(6, 4)
        e.write("\u001B[3;2r") // top > bottom: ignored, region stays full screen
        e.write("\u001B[1;1HA\u001B[4;1HB")
        e.write("\n")
        assertEquals(1, e.scrollbackSize())
    }

    @Test
    fun scrollUpAndScrollDownCommands() {
        val e = TerminalEmulator(4, 3, maxScrollback = 50)
        e.write("A\r\nB\r\nC")
        assertEquals(listOf("A", "B", "C"), (0..2).map { e.rowTrim(it) })

        e.write("\u001B[1S") // content moves up, the top line is discarded
        assertEquals(listOf("B", "C", ""), (0..2).map { e.rowTrim(it) })
        assertEquals(listOf("A"), e.scrollbackLines().map { it[0].char.toString() })

        e.write("\u001B[1T") // content moves down, the bottom line is discarded
        assertEquals(listOf("", "B", "C"), (0..2).map { e.rowTrim(it) })

        e.write("\u001B[2S")
        assertEquals(listOf("C", "", ""), (0..2).map { e.rowTrim(it) })

        // Clamped: scrolling past the region height blanks the screen.
        e.write("\u001B[99S")
        assertEquals(listOf("", "", ""), (0..2).map { e.rowTrim(it) })
    }

    // ── Alternate screen ───────────────────────────────────────────────────

    @Test
    fun alternateScreenDoesNotPolluteScrollback() {
        val e = TerminalEmulator(10, 2, maxScrollback = 100)
        e.write("normal")
        assertEquals(0, e.scrollbackSize())

        e.write("\u001B[?1049h")
        assertTrue(e.isAlternateScreen)
        // Far more lines than the screen can hold: on the normal screen these
        // would all land in the scrollback.
        repeat(20) { e.write("alt$it\r\n") }

        assertEquals(0, e.scrollbackSize())
        // The alt buffer scrolled internally and kept only its last line.
        assertEquals("alt19", e.rowTrim(0))
        assertEquals("", e.rowTrim(1))
    }

    @Test
    fun alternateScreenRestoresPreviousScreenAndCursor() {
        val e = TerminalEmulator(10, 3, maxScrollback = 100)
        e.write("first\r\nsecond")
        assertEquals(1, e.cursorRow)
        assertEquals(6, e.cursorCol)
        val before = e.screenLines()

        e.write("\u001B[?1049h")
        repeat(30) { e.write("junk$it\r\n") }
        e.write("\u001B[?1049l")

        assertFalse(e.isAlternateScreen)
        assertEquals(before, e.screenLines())
        assertEquals("first     ", e.rowText(0))
        assertEquals("second    ", e.rowText(1))
        assertEquals(1, e.cursorRow)
        assertEquals(6, e.cursorCol)
        assertEquals(0, e.scrollbackSize())
    }

    // ── Modes ──────────────────────────────────────────────────────────────

    @Test
    fun cursorVisibilityMode() {
        val e = TerminalEmulator(10, 3)
        assertTrue(e.cursorVisible)
        e.write("\u001B[?25l")
        assertFalse(e.cursorVisible)
        e.write("\u001B[?25h")
        assertTrue(e.cursorVisible)
    }

    @Test
    fun bracketedPasteModeIsRecorded() {
        val e = TerminalEmulator(10, 3)
        assertFalse(e.bracketedPaste)
        e.write("\u001B[?2004h")
        assertTrue(e.bracketedPaste)
        e.write("\u001B[?2004l")
        assertFalse(e.bracketedPaste)
    }

    @Test
    fun autowrapModeCanBeDisabled() {
        val e = TerminalEmulator(5, 2)
        e.write("\u001B[?7l")
        e.write("abcdefghi")
        // Every character after the fourth overwrites the last column.
        assertEquals("abcdi", e.rowTrim(0))
        assertEquals(0, e.cursorRow)
        assertEquals(4, e.cursorCol)
    }

    // ── Device reports ─────────────────────────────────────────────────────

    @Test
    fun deviceStatusReportsAreSentBackToThePty() {
        val e = TerminalEmulator(80, 24)
        e.takePendingOutput()

        e.write("\u001B[6n")
        assertEquals("\u001B[1;1R", out(e))
        assertEquals("", out(e)) // drained

        e.write("\u001B[5;10H\u001B[6n")
        assertEquals("\u001B[5;10R", out(e))

        e.write("\u001B[5n")
        assertEquals("\u001B[0n", out(e))
    }

    @Test
    fun deviceAttributeReports() {
        val e = TerminalEmulator(80, 24)
        e.takePendingOutput()
        e.write("\u001B[c")
        assertEquals("\u001B[?1;2c", out(e))
        e.write("\u001B[0c")
        assertEquals("\u001B[?1;2c", out(e))
        e.write("\u001B[>q")
        assertEquals("\u001B[>0;10;1c", out(e))
        e.write("\u001B[>0q")
        assertEquals("\u001B[>0;10;1c", out(e))
    }

    @Test
    fun reportsAccumulateUntilDrained() {
        val e = TerminalEmulator(80, 24)
        e.takePendingOutput()
        e.write("\u001B[5n\u001B[6n")
        assertEquals("\u001B[0n\u001B[1;1R", out(e))
    }

    // ── OSC / DCS / string consumption ─────────────────────────────────────

    @Test
    fun oscTitleIsParsedFromBelAndStTerminators() {
        val e = TerminalEmulator(20, 3)
        e.write("\u001B]0;my title\u0007hi")
        assertEquals("my title", e.title())
        assertEquals("hi", e.rowTrim(0))
        assertEquals(2, e.cursorCol) // the title text did not consume cells

        e.write("\u001B]2;second\u001B\\")
        assertEquals("second", e.title())
        assertEquals("hi", e.rowTrim(0))

        e.write("\u001B]1;icon name\u0007")
        assertEquals("icon name", e.title())
    }

    @Test
    fun osc52ClipboardIsFullyConsumed() {
        val e = TerminalEmulator(20, 3)
        e.write("\u001B]52;c;SGVsbG8hIHdvcmxk\u0007ok")
        assertEquals("ok", e.rowTrim(0))
        assertEquals(2, e.cursorCol)
        assertEquals("", e.title())
    }

    @Test
    fun unknownDcsApcPmAndSosAreFullyConsumed() {
        val e = TerminalEmulator(20, 4)
        e.write("\u001B[1;1H\u001BP1${'$'}r1m\u001B\\dcs-done")
        assertEquals("dcs-done", e.rowTrim(0))

        e.write("\u001B[2;1H\u001B_Gf=100,a=T;AAAA\u001B\\apc-done")
        assertEquals("apc-done", e.rowTrim(1))

        e.write("\u001B[3;1H\u001B^pm payload\u001B\\pm-done")
        assertEquals("pm-done", e.rowTrim(2))

        // C1 8-bit ST (0x9C) also terminates.
        e.write("\u001B[4;1H\u001BP+q544e\u009Csos-done")
        assertEquals("sos-done", e.rowTrim(3))
    }

    @Test
    fun stringSequencesSplitAcrossWritesStillConsumeCorrectly() {
        val e = TerminalEmulator(20, 3)
        e.write("\u001B]")
        e.write("52;c;AAAA")
        e.write("\u0007")
        e.write("ok")
        assertEquals("ok", e.rowTrim(0))

        e.write("\u001B[2;1H\u001BP1;2|")
        e.write("mod=1;")
        e.write("\u001B")
        e.write("\\")
        e.write("dcs-ok")
        assertEquals("dcs-ok", e.rowTrim(1))
    }

    @Test
    fun unknownCsiIsConsumedAndNeverLeaksIntoTheGrid() {
        val e = TerminalEmulator(30, 3)
        e.write("\u001B[1;1H\u001B[>4;2;9mOK")
        assertEquals("OK", e.rowTrim(0))
        assertEquals(2, e.cursorCol)

        e.write("\u001B[2;1H\u001B[1${'$'}pmore")
        assertEquals("more", e.rowTrim(1))

        e.write("\u001B[3;1H\u001B[999;999;1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;17;18;19;20Zy")
        assertEquals("y", e.rowTrim(2))
    }

    // ── ESC sequences ──────────────────────────────────────────────────────

    @Test
    fun decalnFillsScreenWithE() {
        val e = TerminalEmulator(4, 2)
        e.write("\u001B[5;2H\u001B#8")
        assertEquals("EEEE", e.rowText(0))
        assertEquals("EEEE", e.rowText(1))
        assertEquals(0, e.cursorRow)
        assertEquals(0, e.cursorCol)
    }

    @Test
    fun escapeSequencesForCursorAndScrolling() {
        val e = TerminalEmulator(6, 3, maxScrollback = 20)
        e.write("ab\u001BE") // NEL: carriage return + index
        assertEquals(1, e.cursorRow)
        assertEquals(0, e.cursorCol)

        // IND at the bottom line scrolls the region up.
        e.write("\u001B[3;1HX\u001B[3;2HY")
        e.write("\u001B[3;1H\u001BD")
        assertEquals(2, e.cursorRow)
        assertEquals('X', e.cellAt(1, 0).char)
        assertEquals('Y', e.cellAt(1, 1).char)
        assertEquals(' ', e.cellAt(2, 0).char) // bottom line freed by the scroll
        assertEquals(1, e.scrollbackSize())

        // RI at the top line scrolls the region down.
        val f = TerminalEmulator(6, 3)
        f.write("\u001B[1;1HP\u001B[2;1HQ\u001B[1;1H\u001BM")
        assertEquals('P', f.cellAt(1, 0).char)
        assertEquals('Q', f.cellAt(2, 0).char)
        assertEquals(' ', f.cellAt(0, 0).char)
        assertEquals(0, f.scrollbackSize())
    }

    @Test
    fun saveAndRestoreCursor() {
        val e = TerminalEmulator(20, 10)
        e.write("\u001B[3;4H\u001B7")
        e.write("\u001B[1;1H\u001B8")
        assertEquals(2, e.cursorRow)
        assertEquals(3, e.cursorCol)
    }

    @Test
    fun charsetDesignatorsAndKeypadModesAreIgnored() {
        val e = TerminalEmulator(20, 3)
        e.write("\u001B(0\u001B(B\u001B)0\u001B*q\u001B+\u001B=\u001B>OK")
        assertEquals("OK", e.rowTrim(0))
        assertEquals(2, e.cursorCol)
    }

    @Test
    fun hardResetClearsScreenState() {
        val e = TerminalEmulator(6, 2, maxScrollback = 20)
        e.write("\u001B]0;title\u0007hello\r\nworld\r\nmore")
        e.write("\u001B[?25l\u001B[?2004h")
        assertTrue(e.scrollbackSize() > 0)
        assertFalse(e.cursorVisible)

        e.write("\u001Bc")
        assertEquals("", e.title())
        assertTrue(e.cursorVisible)
        assertFalse(e.bracketedPaste)
        assertEquals(0, e.cursorRow)
        assertEquals(0, e.cursorCol)
        assertEquals("      ", e.rowText(0))
        assertEquals("      ", e.rowText(1))
    }

    // ── UTF-8 ──────────────────────────────────────────────────────────────

    @Test
    fun utf8SequenceSplitAcrossWritesIsOneCharacter() {
        val e = TerminalEmulator(10, 2)
        val bytes = "é".toByteArray(Charsets.UTF_8)
        assertEquals(2, bytes.size)

        e.write(bytes, 1) // only the leading 0xC3
        assertEquals(' ', e.cellAt(0, 0).char)
        assertEquals(0, e.cursorCol) // nothing printed yet

        e.write(bytes.copyOfRange(1, 2)) // the trailing 0xA9
        assertEquals('é', e.cellAt(0, 0).char)
        assertEquals(1, e.cursorCol)
    }

    @Test
    fun multiByteUtf8AcrossManyChunkBoundaries() {
        val e = TerminalEmulator(20, 2)
        val text = "aé€b😀c"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (b in bytes) e.write(byteArrayOf(b)) // one byte per write
        assertEquals(text, e.rowGraphemes(0).trimEnd())
        // a(1) + é(1) + €(1) + b(1) + 😀(2) + c(1) = 7 columns
        assertEquals(7, e.cursorCol)
        assertEquals("aé€b", e.rowGraphemes(0).substring(0, 4))
        // The emoji is stored as a surrogate pair and occupies two columns.
        val emoji = e.cellAt(0, 4)
        assertEquals("\uD83D", emoji.text[0].toString())
        assertEquals(2, emoji.text.length)
        assertEquals(WideState.Continuation, e.cellAt(0, 5).wide)
    }

    @Test
    fun invalidUtf8IsReplacedNotRendered() {
        val e = TerminalEmulator(10, 2)
        e.write(byteArrayOf('a'.code.toByte(), 0x80.toByte(), 'b'.code.toByte()))
        assertEquals('a', e.cellAt(0, 0).char)
        assertEquals(0xFFFD.toChar(), e.cellAt(0, 1).char)
        assertEquals('b', e.cellAt(0, 2).char)
        assertEquals(3, e.cursorCol)
    }

    @Test
    fun combiningMarkAttachesToThePreviousCell() {
        val e = TerminalEmulator(6, 2)
        e.write("e\u0301") // e + U+0301 COMBINING ACUTE ACCENT
        assertEquals(1, e.cursorCol) // did not advance
        assertEquals("e\u0301", e.cellAt(0, 0).text)
        assertEquals('e', e.cellAt(0, 0).char)
        assertEquals("\u0301", e.cellAt(0, 0).combining)
    }

    // ── Wide characters ────────────────────────────────────────────────────

    @Test
    fun wideCharacterOccupiesTwoCellsWithContinuationFlag() {
        val e = TerminalEmulator(10, 2)
        e.write("你好")
        assertEquals('你', e.cellAt(0, 0).char)
        assertEquals(WideState.Normal, e.cellAt(0, 0).wide)
        assertEquals(WideState.Continuation, e.cellAt(0, 1).wide)
        assertTrue(e.cellAt(0, 1).isContinuation)
        assertEquals('好', e.cellAt(0, 2).char)
        assertEquals(WideState.Continuation, e.cellAt(0, 3).wide)
        assertEquals(4, e.cursorCol)
    }

    @Test
    fun astralEmojiIsWideToo() {
        val e = TerminalEmulator(10, 2)
        e.write("😀x") // U+1F600 via a surrogate pair
        assertEquals(WideState.Continuation, e.cellAt(0, 1).wide)
        assertEquals('x', e.cellAt(0, 2).char)
        assertEquals(3, e.cursorCol)
    }

    @Test
    fun wideCharacterWrapsRatherThanSplittingAcrossLines() {
        val e = TerminalEmulator(3, 3)
        e.write("a你")
        // 'a' then a 2-wide char fits in columns 1..2.
        assertEquals('a', e.cellAt(0, 0).char)
        assertEquals('你', e.cellAt(0, 1).char)
        assertEquals(WideState.Continuation, e.cellAt(0, 2).wide)

        val e2 = TerminalEmulator(2, 3)
        e2.write("a你")
        // Only one column left, so the pair moves to the next line intact.
        assertEquals('a', e2.cellAt(0, 0).char)
        assertEquals(' ', e2.cellAt(0, 1).char)
        assertEquals('你', e2.cellAt(1, 0).char)
        assertEquals(WideState.Continuation, e2.cellAt(1, 1).wide)
    }

    @Test
    fun overwritingHalfOfAWideCharacterBlanksTheOrphan() {
        val e = TerminalEmulator(6, 2)
        e.write("你")
        assertEquals(WideState.Continuation, e.cellAt(0, 1).wide)
        e.write("\u001B[1;2HX") // clobber the continuation cell
        // The head is blanked too, so no dangling half of a wide pair survives.
        assertEquals(' ', e.cellAt(0, 0).char)
        assertEquals(WideState.Normal, e.cellAt(0, 0).wide)
        assertEquals('X', e.cellAt(0, 1).char)
        assertEquals(WideState.Normal, e.cellAt(0, 1).wide)
    }

    // ── Resize ─────────────────────────────────────────────────────────────

    @Test
    fun resizePreservesOverlappingContent() {
        val e = TerminalEmulator(4, 2)
        e.write("ab\r\ncd")
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)

        e.resize(6, 3)
        assertEquals(6, e.cols)
        assertEquals(3, e.rows)
        assertEquals("ab    ", e.rowText(0))
        assertEquals("cd    ", e.rowText(1))
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
        assertTrue(e.screenLines().all { it.size == 6 })
    }

    @Test
    fun resizeClampsCursorAndKeepsWriting() {
        val e = TerminalEmulator(10, 10)
        e.write("\u001B[9;9H")
        e.resize(4, 3)
        assertEquals(2, e.cursorRow)
        assertEquals(3, e.cursorCol)
        e.write("z")
        assertEquals("z", e.rowText(2).trim())
    }

    // ── Redraw signalling ──────────────────────────────────────────────────

    @Test
    fun onUpdateFiresOncePerWriteNotPerByte() {
        val e = TerminalEmulator(20, 5)
        var updates = 0
        e.onUpdate = { updates++ }

        e.write("hello world")
        assertEquals(1, updates)

        // A no-op write must not ask for a redraw.
        e.write("")
        assertEquals(1, updates)

        e.write("\u001B[2J")
        assertEquals(2, updates)
    }

    @Test
    fun onUpdateIsInvokedByResize() {
        val e = TerminalEmulator(10, 5)
        var updates = 0
        e.onUpdate = { updates++ }
        e.resize(12, 6)
        assertEquals(1, updates)
    }

    // ── Performance / scrollback bound ─────────────────────────────────────

    @Test
    fun oneMegabyteBurstIsFastAndScrollbackStaysCapped() {
        val maxScroll = 1000
        val e = TerminalEmulator(80, 24, maxScrollback = maxScroll)

        val line = "The quick brown fox jumps over the lazy dog 0123456789\r\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8)
        val burst = ByteArray(1024 * 1024)
        var n = 0
        while (n < burst.size) {
            val chunk = minOf(lineBytes.size, burst.size - n)
            System.arraycopy(lineBytes, 0, burst, n, chunk)
            n += chunk
        }

        var updates = 0
        e.onUpdate = { updates++ }

        val elapsed = measureTimeMillis { e.write(burst) }

        assertTrue("1 MB took $elapsed ms", elapsed < 3000)
        assertEquals("scrollback exceeded its cap", maxScroll, e.scrollbackSize())
        assertEquals("redraws were not coalesced", 1, updates)
        assertTrue(e.screenLines().all { it.size == 80 })

        // The ring really does drop the oldest lines.
        e.write("\u001B[3J")
        assertEquals(0, e.scrollbackSize())
    }

    @Test
    fun scrollbackRingKeepsTheNewestLines() {
        val e = TerminalEmulator(4, 1, maxScrollback = 3)
        e.write("a\r\nb\r\nc\r\nd\r\ne")
        assertEquals(3, e.scrollbackSize())
        assertEquals(listOf("b", "c", "d"), e.scrollbackLines().map { it[0].char.toString() })
        assertEquals("e", e.rowTrim(0))
    }

    @Test
    fun interleavedEscapeAndTextStreamStaysSynchronised() {
        val e = TerminalEmulator(20, 4, maxScrollback = 50)
        val script = buildString {
            append("\u001B]0;title 0\u0007")
            for (i in 1..20) {
                append("\u001B[1;3${i % 3}m")
                append("row$i\u001B[0m\r\n")
                append("\u001B]0;title $i\u0007")
                append("\u001B[?25${if (i % 2 == 0) "h" else "l"}")
            }
        }
        e.write(script.toByteArray(Charsets.UTF_8))
        // A 20-line stream into a 4-row screen leaves the last 3 lines visible
        // (the cursor is parked on the empty 4th) and 17 in the scrollback.
        assertEquals(listOf("row18", "row19", "row20", ""), (0..3).map { e.rowTrim(it) })
        assertEquals(17, e.scrollbackSize())
        assertEquals("title 20", e.title())
        assertTrue(e.cursorVisible)
    }
}
