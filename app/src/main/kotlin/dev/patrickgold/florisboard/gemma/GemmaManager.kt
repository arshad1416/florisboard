package dev.patrickgold.florisboard.gemma

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.florisboard.lib.android.showLongToastSync
import org.florisboard.lib.android.showShortToastSync
import org.florisboard.libnative.LlamaInference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * GemmaManager — on-device AI voice typing + polish/proofread.
 *
 * Voice-to-text uses Android's on-device SpeechRecognizer (StreamingTranscriber).
 * Polish and proofread use local llama.cpp inference with Qwen2.5 1.5B GGUF.
 * All AI features run fully offline — no internet required, no API keys.
 */
class GemmaManager(private val service: FlorisImeService) {

    private sealed interface State {
        object Idle : State
        object Listening : State
        object AwaitingPolish : State
        object Polishing : State
        object Proofreading : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    private val stateMutex = Mutex()
    private val florisSubtypeManager by service.subtypeManager()

    private var aiJob: Job? = null
    private var transcriber: StreamingTranscriber? = null

    private var lastFinalText: String = ""
    private val activeSessionId = AtomicLong(0L)
    private val contactNames by lazy { ContactNamesProvider(service) }
    private val personalDictionary = PersonalDictionary(service)

    // Local inference — loaded lazily on first polish/proofread
    private val modelManager by lazy { ModelManager(service) }
    @Volatile
    private var llamaInference: LlamaInference? = null

    // Track last polish result for user-override detection
    private var lastPolishRaw: String = ""
    private var lastPolishAi: String = ""
    @Volatile
    private var hasPendingPolishCheck = false
    @Volatile
    private var polishOnFinal = false

    var privacyMode: PrivacyMode by mutableStateOf(PrivacyMode.Open)
        private set

    val isBusy: Boolean get() = state.value != State.Idle && state.value != State.AwaitingPolish

    fun onCreate() {
        personalDictionary.clearAll()
        personalDictionary.load()
    }

    fun onStartInput(info: EditorInfo?) {
        forceStop()
        privacyMode = PrivacyClassifier.classify(info)
        Log.i(TAG, "onStartInput: privacyMode = $privacyMode")
    }

    fun onDestroy() {
        forceStop()
        llamaInference?.close()
        llamaInference = null
    }

    // ── Voice Input ──────────────────────────────────────────────────────

    fun toggleVoiceInput() {
        if (privacyMode == PrivacyMode.Sensitive) {
            service.showShortToastSync("Voice typing disabled in sensitive fields")
            return
        }
        service.lifecycleScope.launch(Dispatchers.Main) {
            stateMutex.withLock {
                when (state.value) {
                    is State.Listening -> stopListeningAndPolish()
                    is State.AwaitingPolish -> finishAndPolishInternal()
                    is State.Idle -> startListeningInternal()
                    else -> { /* Busy */ }
                }
            }
        }
    }

    private fun stopListeningAndPolish() {
        polishOnFinal = true
        transcriber?.finishListening()
    }

    private suspend fun startListeningInternal() {
        val sessionId = activeSessionId.incrementAndGet()
        aiJob?.cancel()
        transcriber?.stop()

        state.value = State.Listening
        polishOnFinal = false
        service.isVoiceTypingActive = true

        service.currentInputConnection?.let { ic ->
            val textBefore = ic.getTextBeforeCursor(1, 0)
            if (!textBefore.isNullOrEmpty() && !textBefore[0].isWhitespace()) {
                ic.commitText(" ", 1)
            }
        }

        val transcriberInstance = StreamingTranscriber(service).also { transcriber = it }
        val cb = object : StreamingTranscriber.Callback {
            private fun isSessionActive() = activeSessionId.get() == sessionId && transcriber === transcriberInstance

            override fun onReadyForSpeech() {
                if (isSessionActive()) service.showShortToastSync("Listening...")
            }

            override fun onPartial(text: String) {
                if (isSessionActive() && state.value == State.Listening) {
                    service.currentInputConnection?.setComposingText(text, 1)
                }
            }

            override fun onSegment(text: String) {
                // No-op: we handle the UI dynamically through onPartial and onFinal to avoid sync issues.
            }

            override fun onFinal(text: String) {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    stateMutex.withLock {
                        service.isVoiceTypingActive = false
                        if (!isSessionActive() || state.value != State.Listening) return@withLock
                        lastFinalText = text.trim()
                        if (lastFinalText.isNotBlank()) {
                            service.currentInputConnection?.let { ic ->
                                ic.setComposingText(lastFinalText, 1)
                                ic.finishComposingText()
                            }
                            if (privacyMode == PrivacyMode.NoLearning) {
                                state.value = State.Idle
                                service.showShortToastSync("Committed (Polish disabled in incognito)")
                            } else {
                                if (polishOnFinal) {
                                    polishOnFinal = false
                                    state.value = State.AwaitingPolish
                                    finishAndPolishInternal()
                                } else {
                                    state.value = State.AwaitingPolish
                                    service.showShortToastSync("Tap Mic to Polish")
                                }
                            }
                        } else {
                            state.value = State.Idle
                        }
                    }
                }
            }

            override fun onError(message: String) {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    stateMutex.withLock {
                        service.isVoiceTypingActive = false
                        if (!isSessionActive()) return@withLock
                        state.value = State.Idle
                        service.showLongToastSync("ASR error: $message")
                    }
                }
            }

            override fun onEndOfSpeech() {
                if (isSessionActive()) service.showShortToastSync("Processing...")
            }
        }

        val languageTag = florisSubtypeManager.activeSubtypeFlow.value.primaryLocale.languageTag()
        transcriberInstance.start(cb, languageTag)
    }

    private fun stopListeningInternal() {
        transcriber?.stop()
        transcriber = null
        aiJob?.cancel()
        polishOnFinal = false
        state.value = State.Idle
        service.isVoiceTypingActive = false
        activeSessionId.incrementAndGet()
    }

    // ── Polish (Voice → AI) ──────────────────────────────────────────────

    private suspend fun finishAndPolishInternal() {
        if (privacyMode == PrivacyMode.NoLearning) {
            state.value = State.Idle
            service.isVoiceTypingActive = false
            return
        }
        val raw = lastFinalText.takeIf { it.isNotBlank() } ?: run {
            state.value = State.Idle
            service.isVoiceTypingActive = false
            return
        }

        state.value = State.Polishing
        aiJob = service.lifecycleScope.launch(Dispatchers.Main) {
            service.showShortToastSync("Polishing...")
            try {
                val ctx = withContext(Dispatchers.Main) {
                    service.currentInputConnection?.getTextBeforeCursor(raw.length + 50, 0)?.toString()?.let { full ->
                        if (full.endsWith(raw)) full.substring(0, full.length - raw.length).trim() else full.trim()
                    } ?: ""
                }

                val corrections = mergedCorrections()
                val polished = withContext(Dispatchers.IO) { localPolish(raw, ctx, corrections) }

                Log.i(TAG, "Polish raw=[$raw]")
                Log.i(TAG, "Polish result=[$polished]")
                Log.i(TAG, "Polish changed=${polished != raw} blank=${polished.isBlank()}")

                if (isActive && polished.isNotBlank() && polished != raw) {
                    service.currentInputConnection?.let { ic ->
                        val et = ic.getExtractedText(ExtractedTextRequest(), 0)
                        val selectionEnd = et?.selectionEnd ?: 0
                        if (ic.getTextBeforeCursor(raw.length, 0)?.toString() == raw) {
                            ic.setComposingRegion(max(0, selectionEnd - raw.length), selectionEnd)
                            ic.commitText(polished, 1)
                        } else {
                            ic.commitText(" $polished", 1)
                        }
                        ic.finishComposingText()
                        lastPolishRaw = raw
                        lastPolishAi = polished
                        hasPendingPolishCheck = true
                    }
                    service.showShortToastSync("Polished!")
                } else if (polished == raw) {
                    service.showShortToastSync("No changes needed")
                } else {
                    service.showShortToastSync("Done")
                }
            } catch (e: CancellationException) {
                // Swallow — job was cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Polish failed", e)
                service.showLongToastSync("Polish failed: ${e.message}")
            } finally {
                stateMutex.withLock {
                    lastFinalText = ""
                    state.value = State.Idle
                    service.isVoiceTypingActive = false
                }
            }
        }
    }

    // ── Proofread (full text field) ──────────────────────────────────────

    fun proofread() {
        if (privacyMode == PrivacyMode.Sensitive) {
            service.showShortToastSync("Proofreading disabled in sensitive fields")
            return
        }
        if (privacyMode == PrivacyMode.NoLearning) {
            service.showShortToastSync("Proofreading disabled in incognito fields")
            return
        }
        service.lifecycleScope.launch(Dispatchers.Main) {
            val canProceed = stateMutex.withLock {
                if (state.value != State.Idle) false else { state.value = State.Proofreading; true }
            }
            if (!canProceed) return@launch

            try {
                val ic = service.currentInputConnection ?: return@launch
                ic.finishComposingText()
                val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return@launch
                val fullText = et.text?.toString() ?: ""
                if (fullText.isBlank()) return@launch

                service.showShortToastSync("AI Proofreading...")
                val corrections = mergedCorrections()
                val polished = withContext(Dispatchers.IO) { localPolish(fullText, corrections = corrections) }

                Log.i(TAG, "Proofread raw=[$fullText]")
                Log.i(TAG, "Proofread result=[$polished]")

                if (isActive && polished.isNotBlank() && polished != fullText) {
                    ic.setComposingRegion(0, fullText.length)
                    ic.commitText(polished, 1)
                    ic.finishComposingText()
                    lastPolishRaw = fullText
                    lastPolishAi = polished
                    hasPendingPolishCheck = true
                    service.showShortToastSync("Fixed typos & grammar")
                } else if (polished == fullText) {
                    service.showShortToastSync("No changes needed")
                } else {
                    service.showShortToastSync("Looks good!")
                }
            } catch (e: CancellationException) {
                // Swallow
            } catch (e: Exception) {
                Log.e(TAG, "Proofread failed", e)
                service.showLongToastSync("Proofread failed: ${e.message}")
            } finally {
                stateMutex.withLock { state.value = State.Idle }
            }
        }
    }

    fun forceStop() {
        service.isVoiceTypingActive = false
        service.lifecycleScope.launch(Dispatchers.Main) {
            stateMutex.withLock {
                transcriber?.stop()
                transcriber = null
                aiJob?.cancel()
                polishOnFinal = false
                state.value = State.Idle
                activeSessionId.incrementAndGet()
            }
        }
    }

    // ── User Override Detection ──────────────────────────────────────────

    /**
     * Called from [FlorisImeService.onUpdateSelection] when text changes after a polish.
     * Detects if the user reverted or changed the AI correction and saves the preference.
     */
    fun checkUserOverride(textBeforeCursor: CharSequence?) {
        if (!hasPendingPolishCheck) return
        val text = textBeforeCursor?.toString() ?: return

        if (lastPolishRaw.isBlank() || lastPolishAi.isBlank()) {
            hasPendingPolishCheck = false
            return
        }

        if (text.endsWith(lastPolishAi)) {
            hasPendingPolishCheck = false
            return
        }

        if (text.endsWith(lastPolishRaw)) {
            personalDictionary.recordCorrection(lastPolishRaw, lastPolishAi, lastPolishRaw)
            Log.i(TAG, "User reverted AI correction: '$lastPolishRaw' -> kept original")
        } else {
            val userOverride = extractUserOverride(text)
            if (userOverride != null && userOverride != lastPolishAi) {
                personalDictionary.recordCorrection(lastPolishRaw, lastPolishAi, userOverride)
                Log.i(TAG, "User override: '$lastPolishRaw' -> '$userOverride'")
            }
        }

        hasPendingPolishCheck = false
    }

    private fun extractUserOverride(currentText: String): String? {
        val len = lastPolishAi.length.coerceAtMost(currentText.length)
        if (len <= 0) return null
        val tail = currentText.substring(currentText.length - len)
        return if (tail != lastPolishAi) tail.trim() else null
    }

    // ── Local Inference (llama.cpp + Qwen2.5 1.5B) ────────────────────────

    private suspend fun ensureModelReady(): LlamaInference? {
        llamaInference?.let { return it }

        // Check memory
        if (!modelManager.hasEnoughMemory()) {
            withContext(Dispatchers.Main) {
                service.showLongToastSync("Not enough free RAM for AI model")
            }
            return null
        }

        // Download model if needed
        val result = modelManager.ensureModelAvailable(
            onProgress = { /* TODO: wire up download progress UI */ }
        )
        if (result.isFailure) {
            withContext(Dispatchers.Main) {
                service.showLongToastSync("Model download failed: ${result.exceptionOrNull()?.message}")
            }
            return null
        }

        Log.i(TAG, "Loading model from ${modelManager.modelPath()} (${modelManager.modelFileSize() / 1024 / 1024}MB)")
        return try {
            LlamaInference(modelManager.modelPath(), nThreads = 2).also {
                llamaInference = it
                Log.i(TAG, "Model loaded successfully")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load model", e)
            withContext(Dispatchers.Main) {
                service.showLongToastSync("Failed to load AI model: ${e.message}")
            }
            null
        }
    }

    /**
     * Runs local inference for grammar/spelling correction.
     * Uses the Qwen2.5 1.5B model via llama.cpp — fully offline.
     */
    private suspend fun localPolish(
        rawText: String,
        contextText: String = "",
        corrections: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext rawText

        val inf = ensureModelReady() ?: throw IllegalStateException("AI model not available — check RAM and download")

        inf.polish(rawText, contextText, corrections)
    }

    private fun mergedCorrections(): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        merged.putAll(contactNames.getCorrections())
        merged.putAll(personalDictionary.getCorrections())
        return merged
    }

    companion object {
        private const val TAG = "GemmaManager"
    }
}
