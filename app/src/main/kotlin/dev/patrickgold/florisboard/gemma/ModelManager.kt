package dev.patrickgold.florisboard.gemma

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manages the on-device Qwen2.5 1.5B GGUF model.
 *
 * Handles one-time download, SHA-256 verification, and storage
 * in the app's internal files directory. All data stays on-device.
 */
class ModelManager(private val context: Context) {

    private val modelDir get() = File(context.filesDir, "models")
    private val modelFile get() = File(modelDir, MODEL_FILENAME)

    val isModelAvailable: Boolean get() = modelFile.exists() && modelFile.length() > MIN_FILE_SIZE

    /**
     * Downloads the model file with progress callbacks.
     * Returns true if the model is ready (already exists or downloaded successfully).
     */
    suspend fun ensureModelAvailable(
        onProgress: (Float) -> Unit = {},
        wifiOnly: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (isModelAvailable) {
            Log.i(TAG, "Model already available at ${modelFile.absolutePath}")
            return@withContext Result.success(true)
        }

        modelDir.mkdirs()

        Log.i(TAG, "Downloading model from $MODEL_URL")
        val tempFile = File(modelDir, "$MODEL_FILENAME.tmp")
        tempFile.delete()

        try {
            val url = URL(MODEL_URL)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30_000
                conn.readTimeout = 300_000 // 5 min for 1GB file
                conn.requestMethod = "GET"
                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    val msg = "Download failed: HTTP ${conn.responseCode}"
                    Log.e(TAG, msg)
                    return@withContext Result.failure(IOException(msg))
                }

                val totalBytes = conn.contentLengthLong
                if (totalBytes > 0 && totalBytes < MIN_FILE_SIZE) {
                    return@withContext Result.failure(
                        IOException("Download too small: $totalBytes bytes"))
                }

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var downloadedBytes = 0L
                        var lastProgress = 0f

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            if (totalBytes > 0) {
                                val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                                // Only emit progress at 5% increments to reduce overhead
                                if (progress - lastProgress >= 0.05f || progress >= 1f) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                // Verify SHA-256 if available
                if (EXPECTED_SHA256 != null) {
                    val actual = sha256(tempFile)
                    if (!actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
                        tempFile.delete()
                        return@withContext Result.failure(
                            IOException("SHA-256 mismatch: expected $EXPECTED_SHA256, got $actual"))
                    }
                    Log.i(TAG, "SHA-256 verified: $actual")
                }

                // Rename temp to final
                if (!tempFile.renameTo(modelFile)) {
                    // Fallback: copy and delete
                    tempFile.copyTo(modelFile, overwrite = true)
                    tempFile.delete()
                }

                Log.i(TAG, "Model downloaded: ${modelFile.length()} bytes")
                Result.success(true)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Model download failed", e)
            tempFile.delete()
            Result.failure(e)
        }
    }

    /** Deletes the model file to free storage space. */
    fun deleteModel() {
        modelFile.delete()
        Log.i(TAG, "Model deleted")
    }

    /** Returns the model file path for passing to llama.cpp. */
    fun modelPath(): String = modelFile.absolutePath

    /** Returns the model file size in bytes, or 0 if not present. */
    fun modelFileSize(): Long = if (modelFile.exists()) modelFile.length() else 0L

    /** Checks if the device has enough RAM for inference (>= 2.5GB free). */
    fun hasEnoughMemory(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return true // can't check, assume yes
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMemMB = memInfo.availMem / (1024 * 1024)
        Log.i(TAG, "hasEnoughMemory: availMem = $availMemMB MB (required >= 1200 MB)")
        return availMemMB >= 1200
    }

    companion object {
        private const val TAG = "ModelManager"

        /** Qwen2.5 1.5B Instruct, Q4_K_M quantization (~994MB). */
        private const val MODEL_FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MIN_FILE_SIZE = 500_000_000L // 500MB minimum

        // Hugging Face direct download URL (bartowski GGUF mirror)
        private const val MODEL_URL = (
            "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF" +
            "/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        )

        // SHA-256 of the GGUF file. Set null to skip verification.
        // TODO: update with the actual hash after first successful download
        private val EXPECTED_SHA256: String? = null
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
