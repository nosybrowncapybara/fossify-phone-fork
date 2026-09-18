package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.telecom.Call
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isConference
import org.fossify.phone.models.CallContact

fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    ensureBackgroundThread {
        val callContact = CallContact("", "", "", "")
        val handle = try {
            call?.details?.handle?.toString()
        } catch (_: NullPointerException) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (!uri.startsWith("tel:")) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val number = uri.substringAfter("tel:")
        callContact.number = if (context.config.formatPhoneNumbers) {
            number.formatPhoneNumber()
        } else {
            number
        }

        // Instant path from Telecom when the system already resolved the contact
        val telecomName = call?.details?.callerDisplayName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                call?.details?.contactDisplayName?.toString()?.takeIf { it.isNotBlank() }
            } else {
                null
            }

        // Indexed single-number lookup — never load the full address book for one caller
        if (lookupViaPhoneLookup(context, number, callContact) ||
            lookupViaPrivateContacts(context, number, callContact)
        ) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        callContact.name = telecomName ?: callContact.number
        callback(callContact)
    }
}

private fun lookupViaPhoneLookup(context: Context, number: String, callContact: CallContact): Boolean {
    val lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    val projection = arrayOf(
        PhoneLookup.DISPLAY_NAME,
        PhoneLookup.PHOTO_URI,
        PhoneLookup.TYPE,
        PhoneLookup.LABEL
    )

    return try {
        context.contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return false

            val name = cursor.getString(0)
            val photoUri = cursor.getString(1)
            val type = cursor.getInt(2)
            val label = cursor.getString(3)

            callContact.name = name?.takeIf { it.isNotBlank() } ?: callContact.number
            callContact.photoUri = photoUri.orEmpty()
            if (name != null) {
                callContact.numberLabel = context.getPhoneNumberTypeText(type, label.orEmpty())
            }
            true
        } ?: false
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
}

private fun lookupViaPrivateContacts(context: Context, number: String, callContact: CallContact): Boolean {
    return try {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
        val contact = privateContacts.firstOrNull { it.doesHavePhoneNumber(number) } ?: return false
        callContact.name = contact.getNameToDisplay()
        callContact.photoUri = contact.photoUri
        if (contact.phoneNumbers.size > 1) {
            val specific = contact.phoneNumbers.firstOrNull { it.value == number }
            if (specific != null) {
                callContact.numberLabel = context.getPhoneNumberTypeText(specific.type, specific.label)
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
