package org.fossify.phone.helpers

import android.content.Context
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.models.contacts.Contact

/**
 * Loads the device address book once and shares it across Contacts, Favorites, Dialpad, etc.
 * With large address books (10k+), repeated ContactsHelper.getContacts() calls dominate UI latency.
 */
object ContactsCache {
    private val lock = Any()

    @Volatile
    private var cached: ArrayList<Contact>? = null

    private var inFlight = false
    private val waiters = mutableListOf<(ArrayList<Contact>) -> Unit>()

    fun get(
        context: Context,
        forceReload: Boolean = false,
        callback: (ArrayList<Contact>) -> Unit,
    ) {
        if (forceReload) {
            invalidate()
        }

        if (!forceReload) {
            cached?.let {
                callback(ArrayList(it))
                return
            }
        }

        synchronized(lock) {
            if (!forceReload) {
                cached?.let {
                    callback(ArrayList(it))
                    return
                }
            }
            waiters.add(callback)
            if (inFlight) return
            inFlight = true
        }

        val appContext = context.applicationContext
        val privateCursor = appContext.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsHelper(appContext).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            if (SMT_PRIVATE !in appContext.baseConfig.ignoredContactSources) {
                val privateContacts = MyContactsContentProvider.getContacts(appContext, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                    contacts.sort()
                }
            }

            val result = ArrayList(contacts)
            val toNotify: List<(ArrayList<Contact>) -> Unit>
            synchronized(lock) {
                cached = result
                inFlight = false
                toNotify = waiters.toList()
                waiters.clear()
            }
            toNotify.forEach { it(ArrayList(result)) }
        }
    }

    fun peek(): List<Contact>? = cached

    fun invalidate() {
        synchronized(lock) {
            cached = null
        }
    }
}
