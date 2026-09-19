package org.fossify.phone.helpers

import android.os.Handler
import android.os.Looper
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.getProperText
import org.fossify.commons.models.contacts.Contact

object ContactSearchHelper {
    data class IndexedContact(
        val contact: Contact,
        val rawText: String,
        val normalizedText: String,
    )

    /**
     * Name, nickname, phones, and emails only — enough for typical phone-app search
     * without scanning notes/addresses/IMs for every row at load time.
     */
    fun buildIndex(contacts: List<Contact>): List<IndexedContact> {
        val index = ArrayList<IndexedContact>(contacts.size)
        for (contact in contacts) {
            val name = contact.getNameToDisplay()
            val rawText = buildString {
                append(name)
                if (contact.nickname.isNotBlank()) {
                    append(' ')
                    append(contact.nickname)
                }
                for (phone in contact.phoneNumbers) {
                    append(' ')
                    append(phone.value)
                }
                for (email in contact.emails) {
                    append(' ')
                    append(email.value)
                }
            }
            index.add(
                IndexedContact(
                    contact = contact,
                    rawText = rawText,
                    normalizedText = getProperText(rawText, shouldNormalize = true),
                )
            )
        }
        return index
    }

    fun filter(index: List<IndexedContact>, query: String): List<Contact> {
        val fixedText = query.trim().replace("\\s+".toRegex(), " ")
        if (fixedText.isEmpty()) {
            return index.map { it.contact }
        }

        val shouldNormalize = fixedText.normalizeString() == fixedText
        val isNumericQuery = fixedText.toLongOrNull() != null
        val filtered = index.filter { entry ->
            val haystack = if (shouldNormalize) entry.normalizedText else entry.rawText
            haystack.contains(fixedText, ignoreCase = true) ||
                (isNumericQuery && entry.contact.doesContainPhoneNumber(fixedText, true))
        }

        return filtered.sortedBy { entry ->
            val nameToDisplay = entry.contact.getNameToDisplay()
            val comparable = if (shouldNormalize) getProperText(nameToDisplay, shouldNormalize = true) else nameToDisplay
            !comparable.startsWith(fixedText, ignoreCase = true) && !nameToDisplay.contains(fixedText, ignoreCase = true)
        }.map { it.contact }
    }

    fun filterFavorites(contacts: List<Contact>, query: String): List<Contact> {
        val fixedText = query.trim().replace("\\s+".toRegex(), " ")
        if (fixedText.isEmpty()) {
            return contacts
        }

        return contacts.filter {
            it.name.contains(fixedText, ignoreCase = true) ||
                (fixedText.toLongOrNull() != null && it.doesContainPhoneNumber(fixedText))
        }.sortedByDescending {
            it.name.startsWith(fixedText, ignoreCase = true)
        }
    }
}

object ContactSearchCache {
    private val lock = Any()
    private var cachedIndex: List<ContactSearchHelper.IndexedContact>? = null
    private var cachedForSize = -1

    fun build(contacts: List<Contact>): List<ContactSearchHelper.IndexedContact> {
        synchronized(lock) {
            if (cachedIndex != null && cachedForSize == contacts.size) {
                return cachedIndex!!
            }

            val index = ContactSearchHelper.buildIndex(contacts)
            cachedIndex = index
            cachedForSize = contacts.size
            return index
        }
    }

    fun peek(): List<ContactSearchHelper.IndexedContact>? {
        synchronized(lock) {
            return cachedIndex
        }
    }

    fun invalidate() {
        synchronized(lock) {
            cachedIndex = null
            cachedForSize = -1
        }
    }
}

class DebouncedSearch(
    private val debounceMs: Long = 120L,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private var pending: Runnable? = null
    private var generation = 0

    fun submit(task: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val taskGeneration = ++generation
        val runnable = Runnable {
            ensureBackgroundThread {
                if (taskGeneration == generation) {
                    task()
                }
            }
        }
        pending = runnable
        handler.postDelayed(runnable, debounceMs)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        generation++
    }
}
