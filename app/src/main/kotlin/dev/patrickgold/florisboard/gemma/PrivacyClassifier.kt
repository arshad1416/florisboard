package dev.patrickgold.florisboard.gemma

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Privacy mode for the current input field, derived from EditorInfo.
 *
 * - Open:       normal text field — full features (streaming ASR + Gemma polish)
 * - NoLearning: app set IME_FLAG_NO_PERSONALIZED_LEARNING — streaming ASR allowed,
 *               but polish is skipped (polish = passing text to the on-device LLM)
 * - Sensitive:  password / OTP / similar — voice typing fully disabled
 *
 * Detection is based on EditorInfo.inputType and EditorInfo.imeOptions only.
 */
enum class PrivacyMode { Open, NoLearning, Sensitive }

object PrivacyClassifier {

    fun classify(info: EditorInfo?): PrivacyMode {
        if (info == null) return PrivacyMode.Open
        if (isPasswordType(info.inputType)) return PrivacyMode.Sensitive
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) {
            return PrivacyMode.NoLearning
        }
        return PrivacyMode.Open
    }

    private fun isPasswordType(inputType: Int): Boolean {
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
