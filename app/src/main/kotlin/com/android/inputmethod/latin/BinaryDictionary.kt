package com.android.inputmethod.latin

/**
 * JNI bridge to LatinIME's native BinaryDictionary.
 * Provides fast prefix-lookup and word-frequency queries from a compiled .dict file.
 */
class BinaryDictionary(dictPath: String) {

    private var nativePtr: Long = 0

    init {
        nativeInit(dictPath)
    }

    /**
     * Returns word suggestions for a given input word/prefix.
     * @param inputWord the raw input characters as code points
     * @param maxSuggestions maximum number of suggestions to return
     * @return array of suggestion strings, most confident first
     */
    external fun getSuggestions(inputWord: IntArray, maxSuggestions: Int): Array<String>

    /**
     * Returns whether the dictionary contains [word].
     */
    external fun isValidWord(word: String): Boolean

    /**
     * Returns a frequency score for [word] (higher = more common).
     */
    external fun getFrequency(word: String): Int

    /**
     * Closes the native dictionary. Must be called to release memory.
     */
    external fun close()

    private external fun nativeInit(dictPath: String)
    private external fun nativeFinalize()

    @Suppress("DEPRECATION")
    protected fun finalize() {
        nativeFinalize()
    }

    companion object {
        init {
            System.loadLibrary("jni_latinimegoogle")
        }
    }
}
