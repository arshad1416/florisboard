package dev.patrickgold.florisboard.gemma

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device personal dictionary for AI corrections.
 *
 * When the user overrides an AI polish correction, the (mistake, user_preferred)
 * pair is saved here and fed back into future polish prompts as a "do not correct"
 * hint. All data stays in internal storage.
 */
class PersonalDictionary(private val context: Context) {

    @Serializable
    data class CorrectionEntry(
        val original: String,
        val corrected: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentHashMap<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(context.filesDir, "ai_personal_dictionary.json")

    /**
     * The map of wrong→correct spellings to feed into the AI prompt.
     */
    fun getCorrections(): Map<String, String> = entries.toMap()

    /**
     * Record a user-correction pair. If the user typed [userText] after the AI
     * suggested [aiText] for [rawText], we remember that [rawText] should map
     * to [userText] instead of [aiText].
     *
     * If [userText] equals [rawText] (user reverted the AI change entirely),
     * we save rawText→rawText to prevent future "correction" of that word.
     */
    fun recordCorrection(rawText: String, aiText: String, userText: String) {
        if (rawText.isBlank() || userText.isBlank()) return
        if (rawText.equals(userText, ignoreCase = true)) {
            // User reverted the correction — remember their spelling
            entries[rawText.lowercase()] = userText
        } else {
            // User typed something else — remember the final result
            entries[rawText.lowercase()] = userText
        }
        persist()
    }

    fun load() {
        try {
            if (!file.exists()) return
            val saved = json.decodeFromString<List<CorrectionEntry>>(file.readText())
            entries.clear()
            for (entry in saved) {
                entries[entry.original.lowercase()] = entry.corrected
            }
            Log.i(TAG, "Loaded ${entries.size} personal dictionary entries")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load personal dictionary", e)
        }
    }

    /** Clear all entries and delete the backing file. */
    fun clearAll() {
        entries.clear()
        try { file.delete() } catch (_: Exception) {}
        Log.i(TAG, "Personal dictionary cleared")
    }

    private fun persist() {
        try {
            val list = entries.entries.map { (k, v) -> CorrectionEntry(k, v) }
            file.writeText(json.encodeToString(ListSerializer(CorrectionEntry.serializer()), list))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist personal dictionary", e)
        }
    }

    companion object {
        private const val TAG = "PersonalDictionary"
    }
}
