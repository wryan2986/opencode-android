package dev.ryan.opencode.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** What the voice screen is doing right now. */
enum class VoiceState {
    Idle,
    Listening,
    Thinking,
    Speaking,
    Interrupted,
    Error,
}

data class VoiceUiState(
    val state: VoiceState = VoiceState.Idle,
    val partialTranscript: String = "",
    val finalTranscript: String = "",
    val micLevel: Float = 0f,
    val lastError: String? = null,
    val autoListen: Boolean = true,
    val bargeInEnabled: Boolean = true,
    val speechAvailable: Boolean = true,
)

/**
 * Voice I/O for the top-level Voice mode.
 *
 * Design notes, since the "make it feel like a real conversation" bar is high:
 *
 *  - **Recognition stays running across turns.** Restarting the recogniser between
 *    utterances costs a visible dead beat, so we keep a single session alive and
 *    just take the `onResults` callback as a turn boundary.
 *  - **Speech output is streamed per sentence**, not per turn. We hold a rolling
 *    buffer of the assistant's live text and enqueue each completed sentence to
 *    TTS as soon as its terminator arrives. This is the single biggest lever on
 *    perceived latency — the agent starts talking while it is still working.
 *  - **Barge-in**: `onRmsChanged` gives a live mic level even mid-utterance, so
 *    while we are speaking we can watch for the user starting to talk and cut
 *    TTS off immediately. Whether that also cancels the server-side turn is a
 *    product decision, so it is surfaced as [interruptOnBargeIn].
 */
class VoiceEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false

    /** If true, barge-in also aborts the running turn on the server. */
    var interruptOnBargeIn: Boolean = true

    /** Called with a finalised user utterance. */
    var onFinalUtterance: ((String) -> Unit)? = null

    /** Called when the user talks over the assistant. */
    var onBargeIn: (() -> Unit)? = null

    /** Called when the assistant's turn begins streaming, so the UI can show it. */
    var onAssistantTurnStarted: (() -> Unit)? = null

    private val spokenQueue = ArrayDeque<String>()
    private val accumulator = SpeechScript.SentenceAccumulator()
    private var speaking = false
    private val bargeIn = SpeechScript.BargeInDetector()

    val hasMicPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun initialise() {
        initTts()
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(speechAvailable = true)
        } else {
            _state.value = _state.value.copy(
                speechAvailable = false,
                lastError = "Speech recognition is not available on this device",
            )
        }
    }

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        drainQueue()
                    }
                    @Deprecated("required by the platform base class")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        drainQueue()
                    }
                })
            }
        }
    }

    fun setVoiceRate(rate: Float) { if (ttsReady) tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) }
    fun setVoicePitch(pitch: Float) { if (ttsReady) tts?.setPitch(pitch.coerceIn(0.5f, 2.0f)) }

    // ---- recognition ----

    fun startListening(autoRestart: Boolean = true) {
        if (!hasMicPermission) {
            _state.value = _state.value.copy(lastError = "Microphone permission is required")
            return
        }
        if (listening) return
        _state.value = _state.value.copy(state = VoiceState.Listening, autoListen = autoRestart, lastError = null)
        launchRecogniser()
    }

    fun stopListening() {
        listening = false
        runCatching { recognizer?.stopListening() }
        _state.value = _state.value.copy(state = VoiceState.Idle, partialTranscript = "")
    }

    private fun launchRecogniser() {
        val sr = recognizer ?: run {
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            created.also { recognizer = it }
        }
        listening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Tuned for conversational turn-taking: a short pause ends the turn,
            // but not so short that we cut people off mid-thought.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = _state.value.copy(state = VoiceState.Listening)
            }

            override fun onBeginningOfSpeech() {
                _state.value = _state.value.copy(state = VoiceState.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) {
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.value = _state.value.copy(micLevel = level)
                maybeBargeIn(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(state = VoiceState.Thinking)
            }

            override fun onError(error: Int) {
                // NO_MATCH / SPEECH_TIMEOUT on a hands-free loop are normal;
                // just roll straight into the next recognition window.
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    if (_state.value.autoListen && listening) relaunch()
                    return
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    _state.value = _state.value.copy(
                        state = VoiceState.Error,
                        lastError = "Microphone permission denied",
                    )
                    listening = false
                    return
                }
                if (_state.value.autoListen && listening) relaunch()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    _state.value = _state.value.copy(
                        state = VoiceState.Thinking,
                        finalTranscript = text,
                        partialTranscript = "",
                    )
                    onFinalUtterance?.invoke(text)
                }
                if (_state.value.autoListen && listening) relaunch() else stopListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                _state.value = _state.value.copy(partialTranscript = text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        runCatching { sr.startListening(intent) }
            .onFailure {
                _state.value = _state.value.copy(
                    state = VoiceState.Error,
                    lastError = it.message ?: "Could not start the microphone",
                )
            }
    }

    private fun relaunch() {
        // A small gap stops the recogniser from being restarted inside its own callback.
        scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(220)
            if (listening) runCatching { recognizer?.cancel(); launchRecogniser() }
        }
    }

    // ---- barge-in ----

    private fun maybeBargeIn(rmsDb: Float) {
        if (!_state.value.bargeInEnabled) return
        // Only listen for interruption while we are actually making noise —
        // otherwise the user's own speech during a pause would trip it.
        if (speaking || spokenQueue.isNotEmpty()) {
            if (bargeIn.onRms(rmsDb)) {
                interruptSpeech()
                onBargeIn?.invoke()
                _state.value = _state.value.copy(state = VoiceState.Interrupted)
            }
        } else {
            bargeIn.resetBargeIn()
        }
    }

    /** Stop audio immediately — this is what makes interruption feel instant. */
    fun interruptSpeech() {
        spokenQueue.clear()
        accumulator.reset()
        if (ttsReady) runCatching { tts?.stop() }
        speaking = false
    }

    // ---- speech output (streamed per sentence) ----

    /** Reset the buffer at the start of a new assistant turn. */
    fun beginAssistantTurn() {
        interruptSpeech()
        accumulator.reset()
        onAssistantTurnStarted?.invoke()
    }

    /**
     * Feed the assistant's live text. Completed sentences are enqueued for speech
     * immediately, so the first sentence is audible while the rest is still being
     * generated — the single biggest lever on perceived latency.
     */
    fun feedAssistantText(full: String) {
        if (!ttsReady) return
        accumulator.accept(full).forEach { spokenQueue.addLast(it) }
        drainQueue()
    }

    /** Speak whatever is buffered once the turn is complete. */
    fun endAssistantTurn() {
        if (!ttsReady) return
        accumulator.flush()?.let { spokenQueue.addLast(it) }
        drainQueue()
    }

    private fun drainQueue() {
        if (!ttsReady || speaking) return
        val next = spokenQueue.removeFirstOrNull() ?: return
        speaking = true
        // Start measuring the speaker's own bleed so barge-in can tell my voice
        // from the user's.
        bargeIn.onSpeechStarted()
        _state.value = _state.value.copy(state = VoiceState.Speaking)
        runCatching { tts?.speak(next, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) }
            .onFailure { speaking = false }
    }

    fun isSpeaking(): Boolean = speaking || spokenQueue.isNotEmpty()

    // ---- helpers ----

    fun release() {
        listening = false
        runCatching { recognizer?.destroy() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        recognizer = null
        tts = null
        ttsReady = false
    }
}
