/*
 * Copyright (C) 2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.text.gestures

import android.content.Context
import android.util.Log
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.nlp.WordSuggestionCandidate
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Handles the [GlideTypingClassifier]. Basically responsible for linking [GlideTypingGesture.Detector]
 * with [GlideTypingClassifier].
 */
class GlideTypingManager(private val context: Context) : GlideTypingGesture.Listener {
    companion object {
        private const val TAG = "GlideTypingManager"
        private const val MAX_SUGGESTION_COUNT = 8
        private const val NATIVE_FAILURE_THRESHOLD = 3
    }

    private val prefs by FlorisPreferenceStore
    private val keyboardManager by context.keyboardManager()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var glideTypingClassifier: GlideTypingClassifier = createClassifier(context)
    private var lastTime = System.currentTimeMillis()
    private var nativeFailureCount = 0

    private fun createClassifier(context: Context): GlideTypingClassifier {
        return when (prefs.glide.glideEngineMode.get()) {
            GlideEngineMode.NATIVE_LEGACY -> {
                // The native .so may cause SIGSEGV later (during setLayout / ProximityInfo.nativeInit)
                // which kills the process and Kotlin catch blocks can't stop it.
                // Force statistical immediately to prevent a crash loop.
                Log.w(TAG, "Native glide engine is unstable — auto-fallback to statistical")
                forceFallbackToStatistical()
                StatisticalGlideTypingClassifier(context)
            }
            GlideEngineMode.AI_NEURAL_STAGE2 -> {
                val gemma = FlorisImeService.imsOrNull()?.gemmaManager
                if (gemma != null) {
                    dev.patrickgold.florisboard.gemma.NeuralGemmaGlideClassifier(context, gemma)
                } else {
                    StatisticalGlideTypingClassifier(context)
                }
            }
            else -> StatisticalGlideTypingClassifier(context)
        }
    }

    /**
     * Re-initializes the classifier if the engine mode has changed.
     */
    fun updateClassifierIfNecessary(context: Context) {
        val currentMode = prefs.glide.glideEngineMode.get()
        val isNative = glideTypingClassifier is NativeGlideTypingClassifier
        val isAi = glideTypingClassifier is dev.patrickgold.florisboard.gemma.NeuralGemmaGlideClassifier

        val shouldUpdate = when (currentMode) {
            GlideEngineMode.NATIVE_LEGACY -> !isNative
            GlideEngineMode.AI_NEURAL_STAGE2 -> !isAi
            else -> isNative || isAi
        }

        if (shouldUpdate) {
            glideTypingClassifier.close()
            glideTypingClassifier = createClassifier(context)
            nativeFailureCount = 0
        }
    }

    /** Synchronously reset preference to statistical so crashy native path is not re-entered. */
    private fun forceFallbackToStatistical() {
        scope.launch {
            prefs.glide.glideEngineMode.set(GlideEngineMode.STATISTICAL_FOSS)
        }
        nativeFailureCount = 0
    }

    override fun onGlideComplete(data: GlideTypingGesture.Detector.PointerData) {
        updateClassifierIfNecessary(context)
        updateSuggestionsAsync(MAX_SUGGESTION_COUNT, true) {
            glideTypingClassifier.clear()
        }
    }

    override fun onGlideCancelled() {
        glideTypingClassifier.clear()
    }

    override fun onGlideAddPoint(point: GlideTypingGesture.Detector.Position) {
        updateClassifierIfNecessary(context)
        val normalized = GlideTypingGesture.Detector.Position(point.x, point.y)

        this.glideTypingClassifier.addGesturePoint(normalized)

        val time = System.currentTimeMillis()
        if (prefs.glide.showPreview.get() && time - lastTime > prefs.glide.previewRefreshDelay.get()) {
            updateSuggestionsAsync(1, false) {}
            lastTime = time
        }
    }

    /**
     * Change the layout of the internal gesture classifier
     */
    fun setLayout(keys: List<TextKey>) {
        updateClassifierIfNecessary(context)
        if (keys.isNotEmpty()) {
            glideTypingClassifier.setLayout(keys, subtypeManager.activeSubtype)
            trackNativeReadiness()
        }
    }

    /**
     * If the native classifier fails to become ready after several layout passes,
     * permanently switch to the statistical classifier so glide typing keeps working.
     */
    private fun trackNativeReadiness() {
        if (glideTypingClassifier !is NativeGlideTypingClassifier) return
        if (glideTypingClassifier.isReady()) {
            nativeFailureCount = 0
            return
        }
        nativeFailureCount++
        Log.w(TAG, "Native classifier not ready (failure $nativeFailureCount/$NATIVE_FAILURE_THRESHOLD)")
        if (nativeFailureCount >= NATIVE_FAILURE_THRESHOLD) {
            Log.w(TAG, "Native classifier persistently failing — auto-fallback to statistical")
            glideTypingClassifier.close()
            forceFallbackToStatistical()
            glideTypingClassifier = StatisticalGlideTypingClassifier(context)
        }
    }

    /**
     * Asks gesture classifier for suggestions and then passes that on to the smartbar.
     * Also commits the most confident suggestion if [commit] is set. All happens on an async executor.
     * NB: only fetches [MAX_SUGGESTION_COUNT] suggestions.
     *
     * @param callback Called when this function completes. Takes a boolean, which indicates if suggestions
     * were successfully set.
     */
    private fun updateSuggestionsAsync(maxSuggestionsToShow: Int, commit: Boolean, callback: (Boolean) -> Unit) {
        if (!glideTypingClassifier.isReady()) {
            callback.invoke(false)
            return
        }

        scope.launch(Dispatchers.Default) {
            val suggestions = glideTypingClassifier.getSuggestions(MAX_SUGGESTION_COUNT, true)

            withContext(Dispatchers.Main) {
                val suggestionList = buildList {
                    suggestions.subList(
                        1.coerceAtMost(min(commit.compareTo(false), suggestions.size)),
                        maxSuggestionsToShow.coerceAtMost(suggestions.size)
                    ).map { keyboardManager.fixCase(it.toString()) }.forEach {
                        add(WordSuggestionCandidate(it, confidence = 1.0))
                    }
                }

                nlpManager.suggestDirectly(suggestionList)
                if (commit && suggestions.isNotEmpty()) {
                    keyboardManager.commitGesture(suggestions.first().toString())
                }
                callback.invoke(true)
            }
        }
    }
}
