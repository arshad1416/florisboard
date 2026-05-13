package dev.patrickgold.florisboard.gemma

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads contact display names from the on-device ContactsContract provider
 * and builds a spelling-correction map for the AI polish prompt.
 *
 * All queries are local — no network, no sync.
 */
class ContactNamesProvider(private val context: Context) {

    private val correctionMap = ConcurrentHashMap<String, String>()
    @Volatile
    private var loaded = false

    /**
     * Returns a copy of the current name-correction map.
     * Lazily loads contacts on first call.
     */
    fun getCorrections(): Map<String, String> {
        if (!loaded) loadContacts()
        return correctionMap.toMap()
    }

    /**
     * Reload contacts (e.g. after a permission grant or content change).
     */
    fun reload() {
        correctionMap.clear()
        loaded = false
        loadContacts()
    }

    private fun loadContacts() {
        if (loaded) return
        loaded = true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted; skipping contact name loading")
            return
        }

        val names = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )

        val cursor: Cursor? = runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC",
            )
        }.getOrElse {
            Log.w(TAG, "Failed to query contacts", it)
            return
        }

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (it.moveToNext()) {
                val displayName = it.getString(nameIdx)?.trim() ?: continue
                if (displayName.isBlank() || displayName.length < 2) continue
                names.add(displayName)
            }
        }

        for (name in names) {
            val parts = name.split("\\s+".toRegex())
            correctionMap[name.lowercase()] = name

            if (parts.size >= 2) {
                // Map just the first name so "arshad" → "Arshad"
                val first = parts.first()
                if (first.length >= 2) {
                    correctionMap[first.lowercase()] = first
                }
                // Map just the last name
                val last = parts.last()
                if (last.length >= 2) {
                    correctionMap[last.lowercase()] = last
                }
            }
        }

        Log.i(TAG, "Loaded ${correctionMap.size} name corrections from ${names.size} contacts")
    }

    companion object {
        private const val TAG = "ContactNamesProvider"
    }
}
