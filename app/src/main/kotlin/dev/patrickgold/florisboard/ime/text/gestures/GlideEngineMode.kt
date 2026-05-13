package dev.patrickgold.florisboard.ime.text.gestures

/**
 * Available engines for decoding glide gestures into words.
 */
enum class GlideEngineMode {
    /** Uses the official proprietary Google binary (libjni_latinimegoogle.so). */
    NATIVE_LEGACY,

    /** Uses the open-source statistical matcher (FlorisBoard default). */
    STATISTICAL_FOSS,

    /** Uses the Two-Stage AI engine. */
    AI_NEURAL_STAGE2;
}
