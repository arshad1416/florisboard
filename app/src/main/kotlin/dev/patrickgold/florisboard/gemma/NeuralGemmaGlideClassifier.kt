package dev.patrickgold.florisboard.gemma

import android.content.Context
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingClassifier
import dev.patrickgold.florisboard.ime.text.gestures.GlideTypingGesture
import dev.patrickgold.florisboard.ime.text.keyboard.TextKey

/**
 * Placeholder for the Two-Stage AI Glide Classifier.
 * In this "Rebirth" phase, we focus on the Voice and Proofread features first.
 * For glide, it delegates directly to the high-accuracy Statistical engine.
 */
class NeuralGemmaGlideClassifier(context: Context, private val gemmaManager: GemmaManager) : GlideTypingClassifier {
    
    private val fallback = dev.patrickgold.florisboard.ime.text.gestures.StatisticalGlideTypingClassifier(context)

    override fun isReady(): Boolean = fallback.isReady()

    override fun addGesturePoint(position: GlideTypingGesture.Detector.Position) {
        fallback.addGesturePoint(position)
    }

    override fun setLayout(keyViews: List<TextKey>, subtype: Subtype) {
        fallback.setLayout(keyViews, subtype)
    }

    override fun setWordData(subtype: Subtype) {
        fallback.setWordData(subtype)
    }

    override fun initGestureFromPointerData(pointerData: GlideTypingGesture.Detector.PointerData) {
        fallback.initGestureFromPointerData(pointerData)
    }

    override fun getSuggestions(maxSuggestionCount: Int, gestureCompleted: Boolean): List<CharSequence> {
        return fallback.getSuggestions(maxSuggestionCount, gestureCompleted)
    }

    override fun clear() {
        fallback.clear()
    }

    override fun close() {
        fallback.close()
    }
}
