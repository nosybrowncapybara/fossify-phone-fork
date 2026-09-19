package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import org.fossify.commons.extensions.normalizePhoneNumber
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.models.RecentCall

/**
 * Updates recent-call display names after contacts are saved or deleted.
 * Uses the already-loaded contacts cache — never scan the full call log during normal scrolling.
 */
object RecentCallContactResolver {
    private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9

    fun applyContactChanges(
        context: Context,
        calls: List<RecentCall>,
        cachedContacts: List<Contact>,
    ): List<RecentCall> {
        if (calls.isEmpty()) {
            return calls
        }

        val lookupByPhone = buildContactLookup(cachedContacts)
        return calls.map { call ->
            if (call.isUnknownNumber) {
                call
            } else {
                val phoneKey = comparableNumberKey(call.phoneNumber)
                val resolved = lookupByPhone[phoneKey] ?: lookupViaPhoneLookup(context, call.phoneNumber)
                when {
                    resolved != null -> withUpdatedName(call, resolved.name, resolved.photoUri)
                    hasResolvedName(call) -> withUpdatedName(call, call.phoneNumber, "")
                    else -> call
                }
            }
        }
    }

    private fun buildContactLookup(contacts: List<Contact>): Map<String, ResolvedContact> {
        val lookup = HashMap<String, ResolvedContact>()
        for (contact in contacts) {
            val resolved = ResolvedContact(
                name = contact.getNameToDisplay(),
                photoUri = contact.photoUri,
            )
            for (phoneNumber in contact.phoneNumbers) {
                lookup[comparableNumberKey(phoneNumber.value)] = resolved
            }
        }
        return lookup
    }

    private fun lookupViaPhoneLookup(context: Context, number: String): ResolvedContact? {
        val lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            PhoneLookup.DISPLAY_NAME,
            PhoneLookup.PHOTO_URI,
        )

        return try {
            context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }

                val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return null
                ResolvedContact(name = name, photoUri = cursor.getString(1).orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hasResolvedName(call: RecentCall): Boolean {
        return comparableNumberKey(call.name) != comparableNumberKey(call.phoneNumber)
    }

    private fun comparableNumberKey(number: String): String {
        val normalized = number.normalizePhoneNumber().orEmpty().ifEmpty { number }
        return if (normalized.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
            normalized.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)
        } else {
            normalized
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String, photoUri: String): RecentCall {
        val updatedPhotoUri = when {
            photoUri.isNotEmpty() -> photoUri
            name == call.phoneNumber -> ""
            else -> call.photoUri
        }

        return call.copy(
            name = name,
            photoUri = updatedPhotoUri,
            groupedCalls = call.groupedCalls
                ?.map { groupedCall ->
                    groupedCall.copy(name = name, photoUri = updatedPhotoUri)
                }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private data class ResolvedContact(
        val name: String,
        val photoUri: String,
    )
}
