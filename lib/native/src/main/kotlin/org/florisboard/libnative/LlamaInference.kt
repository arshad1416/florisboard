package org.florisboard.libnative

class LlamaInference(modelPath: String, nThreads: Int = 2) {

    private var nativePtr: Long = 0

    init {
        nativePtr = nativeCreate(modelPath, nThreads)
        require(nativePtr != 0L) { "Failed to load model from $modelPath" }
    }

    fun polish(rawText: String, contextText: String = "", corrections: Map<String, String> = emptyMap()): String {
        val correctionsStr = if (corrections.isNotEmpty()) {
            corrections.entries.joinToString(", ") { "${it.key}→${it.value}" }
        } else ""
        return nativePolish(nativePtr, rawText, contextText, correctionsStr)
            ?: throw RuntimeException("Inference failed")
    }

    fun close() {
        if (nativePtr != 0L) {
            nativeDestroy(nativePtr)
            nativePtr = 0
        }
    }

    protected fun finalize() {
        close()
    }

    private external fun nativeCreate(modelPath: String, nThreads: Int): Long
    private external fun nativePolish(ptr: Long, rawText: String, contextText: String, correctionsJson: String): String?
    private external fun nativeDestroy(ptr: Long)
}
