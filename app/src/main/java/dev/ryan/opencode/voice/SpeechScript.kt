package dev.ryan.opencode.voice

/**
 * Splits streaming assistant text into speakable sentences and strips markdown.
 *
 * Extracted from [VoiceEngine] (which owns the platform TextToSpeech) so the
 * interesting part — deciding *what* to say and *when* to say it — is testable
 * on a plain JVM. That matters because this is the code path that makes voice
 * output feel responsive, and it is easy to get subtly wrong.
 *
 * ## Why sentence-level, not turn-level
 *
 * Waiting for a whole turn to finish before speaking makes the assistant feel
 * slow, even when the model is fast. Enqueuing each completed sentence as it
 * arrives means the first sentence is audible while the rest is still being
 * generated. This class exists purely to feed that queue promptly and safely.
 *
 * ## Why markdown is stripped
 *
 * Reading a fenced code block aloud verbatim is the fastest way to make a voice
 * UI feel broken — `function foo(bar: string) { return baz }` is unusable as
 * speech. Code is replaced with a short spoken placeholder plus a count.
 */
object SpeechScript {

    /**
     * Holds the tail of the streamed text that is not yet a complete sentence.
     *
     * Feed it the *cumulative* text on every delta; it returns only the new
     * sentences that have become speakable.
     */
    class SentenceAccumulator {
        /** How much of `previous` we have already processed. */
        private var consumedLen = 0
        /** Spoken + pending text, so we can detect rewinds and repeats. */
        private var previous = ""
        /** Trailing text with no sentence terminator yet. */
        private var buffer = ""

        /**
         * Newly completed sentences, ready to enqueue for speech.
         *
         * `cumulative` is the assistant's full text so far. Three cases matter:
         * normal growth (emit new sentences), an identical redelivery (say
         * nothing), and a rewind after a resync (drop the stale prefix rather
         * than re-speaking what the user already heard).
         */
        fun accept(cumulative: String): List<String> {
            if (cumulative == previous) return emptyList()

            val fresh: String
            if (cumulative.startsWith(previous)) {
                fresh = cumulative.substring(previous.length)
            } else if (previous.startsWith(cumulative)) {
                // Rewind. Forget what we had; the shorter text will re-arrive.
                consumedLen = 0
                buffer = ""
                previous = cumulative
                return emptyList()
            } else {
                // Diverged (resync rewrote history): restart cleanly.
                consumedLen = 0
                buffer = ""
                fresh = cumulative
            }
            previous = cumulative

            val out = mutableListOf<String>()
            var pending = buffer + fresh
            var consumedInPending = 0
            var i = 0
            while (i < pending.length) {
                val end = sentenceEndAt(pending, i) ?: break
                val chunk = pending.substring(consumedInPending, end + 1)
                consumedInPending = end + 1
                val spoken = toSpeech(chunk)
                if (spoken.isNotBlank()) out += spoken
                i = end + 1
            }
            buffer = if (consumedInPending > 0) pending.substring(consumedInPending) else pending
            consumedLen += fresh.length
            return out
        }

        /** Whatever is buffered once the turn is over, for the final flush. */
        fun flush(): String? {
            val tail = toSpeech(buffer)
            buffer = ""
            previous = ""
            consumedLen = 0
            return tail.takeIf { it.isNotBlank() }
        }

        fun reset() {
            buffer = ""
            previous = ""
            consumedLen = 0
        }
    }

    /**
     * Index of the sentence terminator at or after [from], or null.
     *
     * Deliberately conservative: only breaks on `. ! ?` and newline, and skips
     * decimals, ellipses and common abbreviations so "3.14" and "e.g." do not
     * produce a stutter.
     */
    internal fun sentenceEndAt(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                // Collapse a run of blank lines rather than speaking nothing.
                if (text.substring(i).isBlank()) return null
                return i
            }
            if (c == '.' || c == '!' || c == '?') {
                // Ellipsis: consume the whole run of repeated dots, then keep
                // looking for a real terminator.
                if (c == '.' && i + 1 < text.length && text[i + 1] == '.') {
                    var j = i
                    while (j < text.length && text[j] == '.') j++
                    i = j
                    continue
                }
                // Decimal point between digits: not a sentence end.
                if (c == '.' && i > 0 && i + 1 < text.length &&
                    text[i - 1].isDigit() && text[i + 1].isDigit()
                ) { i++; continue }
                // Abbreviation: single letter before the dot (initials, "e.g").
                if (c == '.' && i > 0 && text[i - 1].isLetter() &&
                    (i < 2 || !text[i - 2].isLetter())
                ) { i++; continue }
                // Require whitespace/end after the terminator so "foo.txt" and
                // URLs never split.
                val next = text.getOrNull(i + 1)
                if (next != null && !next.isWhitespace()) { i++; continue }
                return i
            }
            i++
        }
        return null
    }

    // ---- barge-in turn detection ----

    /**
     * Decides whether a burst of loud mic frames means the user started talking.
     *
     * The problem this solves: a phone speaker playing the assistant's own voice
     * will itself register as "loud" on the microphone, so naive thresholding
     * makes the assistant interrupt itself in a loop. Three guards against that:
     *
     *  - frames must be **consecutive** — real speech is sustained, while speaker
     *    bleed tends to arrive in ragged bursts with gaps;
     *  - the threshold is **relative to the noise floor** measured since the
     *    assistant began speaking, not an absolute dBFS value, so it adapts to a
     *    quiet room and a loud one alike;
     *  - it fires **once** per utterance, then disarms, so a single barge-in
     *    cannot re-trigger repeatedly.
     */
    class BargeInDetector(
        private val triggerAfter: Int = 3,
        /** dB above the reference level that counts as an interjection. */
        private val threshold: Float = 6f,
        /** Frames averaged (median) to establish the speaker's own level. */
        private val calibrationFrames: Int = 6,
    ) {
        private var run = 0
        private val calibration = ArrayDeque<Float>()
        private var reference = Float.NaN
        private var armed = false

        /** Call when the assistant starts speaking, to begin measuring speaker bleed. */
        fun onSpeechStarted() {
            armed = true
            run = 0
            reference = Float.NaN
            calibration.clear()
        }

        fun resetBargeIn() {
            armed = false
            run = 0
            reference = Float.NaN
            calibration.clear()
        }

        /**
         * Feed one RMS frame in dBFS.
         * Returns true on the single frame that triggers, then disarms.
         */
        fun onRms(rmsDb: Float): Boolean {
            if (!armed) return false

            // Calibrate first: a single frame is a poor estimate of how loud the
            // assistant sounds at the mic, and anchoring on a momentary dip makes
            // ordinary speech look like an interruption.
            if (reference.isNaN()) {
                calibration.addLast(rmsDb)
                if (calibration.size < calibrationFrames) return false
                val sorted = calibration.sorted()
                reference = sorted[sorted.size / 2]   // median
                calibration.clear()
                return false
            }

            val delta = rmsDb - reference
            if (delta >= threshold) {
                run++
                if (run >= triggerAfter) {
                    resetBargeIn()
                    return true
                }
            } else {
                // Below threshold, or a gap in the streak.
                run = 0
                // Creep the reference up toward sustained loud audio. This is what
                // stops the assistant interrupting itself: its own continuous
                // speech quickly becomes the new "normal", while the user's
                // interjection is a sudden jump above it.
                if (delta > 0f) reference += (rmsDb - reference) * 0.25f
            }
            return false
        }
    }

    /**
     * Make one chunk of markdown speakable.
     *
     * Returns empty for content that carries no speech value (a lone code fence,
     * an image link) so the caller does not enqueue a useless utterance.
     */
    fun toSpeech(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
        // Count *complete* fenced blocks. Counting bare ``` would also count the
        // closing fence, doubling the number we announce.
        val codeBlock = Regex("```[a-zA-Z0-9+#._-]*[\\s\\S]*?```")
        val codeBlocks = codeBlock.findAll(text).count()
        text = codeBlock.replace(text, " ")

        // Inline code: keep the identifier, it is often the point.
        text = text.replace(Regex("`([^`\n]+)`"), "$1")
        // Headings, emphasis, blockquotes, list bullets.
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        text = text.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
        text = text.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        text = text.replace(Regex("__([^_]+)__"), "$1")
        text = text.replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
        // Single-underscore italics. Requires a non-space just inside the
        // delimiters, otherwise `snake_case_name` would be mangled.
        text = text.replace(Regex("(?<![A-Za-z0-9_])_([^_\\n]+)_(?![A-Za-z0-9_])"), "$1")
        text = text.replace(Regex("~~([^~]+)~~"), "$1")
        // Links: speak the label, not the URL.
        text = text.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        // Bare URLs.
        text = text.replace(Regex("https?://\\S+"), " link ")
        // Tables and rules are noise.
        text = text.replace(Regex("(?m)^\\s*\\|.*$"), " ")
        text = text.replace(Regex("(?m)^\\s*[-=*_]{3,}\\s*$"), " ")

        text = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{2,}"), ". ").trim()

        if (codeBlocks > 0) {
            val n = codeBlocks
            val note = "$n code block${if (n > 1) "s" else ""} omitted"
            // A response that is *only* a code block has nothing worth saying;
            // announcing "1 code block omitted" and nothing else is just noise.
            text = when {
                text.isEmpty() -> ""
                else -> (text.trimEnd('.', ' ') + ". $note").trim()
            }
        }
        return text
    }
}
