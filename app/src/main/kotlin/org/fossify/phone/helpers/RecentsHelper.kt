package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import android.telephony.PhoneNumberUtils
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.RecentCall
import org.fossify.phone.models.SIMAccount

class RecentsHelper(private val context: Context) {
    companion object {
        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        const val QUERY_LIMIT = 100
        /** Cap for the background “load all” pass — full history is too slow on large call logs. */
        const val QUERY_LIMIT_MAX = 500

        private fun comparableNumberKey(number: String): String {
            val normalized = number.normalizePhoneNumber().orEmpty().ifEmpty { number }
            return if (normalized.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                normalized.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)
            } else {
                normalized
            }
        }
    }

    private val contentUri = Calls.CONTENT_URI
    private var queryLimit = QUERY_LIMIT

    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }

        // Do not load the full contacts database here — with large address books (10k+)
        // that dominates startup. CallLog already provides CACHED_NAME / CACHED_PHOTO_URI.
        ensureBackgroundThread {
            this.queryLimit = queryLimit
            val recentCalls = if (previousRecents.isNotEmpty()) {
                val previousRecentCalls = previousRecents
                    .flatMap { it.groupedCalls ?: listOf(it) }
                    .map { it.copy(groupedCalls = null) }

                val newerRecents = getRecents(
                    contacts = emptyList(),
                    selection = "${Calls.DATE} > ?",
                    selectionParams = arrayOf("${previousRecentCalls.first().startTS}")
                )

                val olderRecents = getRecents(
                    contacts = emptyList(),
                    selection = "${Calls.DATE} < ?",
                    selectionParams = arrayOf("${previousRecentCalls.last().startTS}")
                )

                newerRecents + previousRecentCalls + olderRecents
            } else {
                getRecents(contacts = emptyList())
            }

            callback(
                processRecentCalls(recentCalls)
            )
        }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        getRecentCalls(previousRecents, queryLimit) { recentCalls ->
            callback(
                groupSubsequentCalls(calls = recentCalls)
            )
        }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        val differentSim = callA.simID != callB.simID
        val differentDay = callA.dayCode != callB.dayCode
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                    callA.name != callA.phoneNumber &&
                    callB.name != callB.phoneNumber

        if (differentSim || differentDay || namesAreBothRealAndDifferent) return false

        @Suppress("DEPRECATION")
        return PhoneNumberUtils.compare(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        val result = mutableListOf<RecentCall>()
        if (calls.isEmpty()) return result

        var currentCall = calls[0]
        for (i in 1 until calls.size) {
            val nextCall = calls[i]
            if (shouldGroupCalls(currentCall, nextCall)) {
                if (currentCall.groupedCalls.isNullOrEmpty()) {
                    currentCall = currentCall.copy(groupedCalls = mutableListOf(currentCall))
                }

                currentCall.groupedCalls?.add(nextCall)
            } else {
                result += currentCall
                currentCall = nextCall
            }
        }

        result.add(currentCall)
        return result
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: List<Contact>,
        selection: String? = null,
        selectionParams: Array<String>? = null,
    ): List<RecentCall> {
        val recentCalls = mutableListOf<RecentCall>()
        var previousStartTS = 0L
        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()

        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.CACHED_PHOTO_URI,
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NUMBER_PRESENTATION
        )

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        val cursor = if (isNougatPlus()) {
            // https://issuetracker.google.com/issues/175198972?pli=1#comment6
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, queryLimit.toString())
                .build()
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)
        } else {
            val sortOrder = "${Calls.DATE} DESC LIMIT $queryLimit"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        }

        val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
        val numbersToContactIDMap = HashMap<String, Int>()
        contactsWithMultipleNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                numbersToContactIDMap[phoneNumber.value] = contact.contactId
                numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
            }
        }

        // O(1) contact lookup by last digits — avoids scanning all contacts per call-log row
        val suffixToContactName = HashMap<String, String>()
        val suffixToContactPhoto = HashMap<String, String>()
        for (contact in contacts) {
            val displayName = contact.getNameToDisplay()
            for (phoneNumber in contact.phoneNumbers) {
                val key = comparableNumberKey(phoneNumber.normalizedNumber.ifEmpty { phoneNumber.value })
                if (key.isEmpty()) continue
                suffixToContactName.putIfAbsent(key, displayName)
                if (contact.photoUri.isNotEmpty()) {
                    suffixToContactPhoto.putIfAbsent(key, contact.photoUri)
                }
            }
        }

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                if (name == number && !isUnknownNumber && !number.isNullOrEmpty()) {
                    val cachedName = contactsNumbersMap[number]
                    if (cachedName != null) {
                        name = cachedName
                    } else {
                        val key = comparableNumberKey(number)
                        val resolvedName = suffixToContactName[key]
                        if (resolvedName != null) {
                            contactsNumbersMap[number] = resolvedName
                            name = resolvedName
                        }
                    }
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI) ?: ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    val cachedPhoto = contactPhotosMap[number]
                    if (cachedPhoto != null) {
                        photoUri = cachedPhoto
                    } else {
                        val key = comparableNumberKey(number)
                        val resolvedPhoto = suffixToContactPhoto[key].orEmpty()
                        contactPhotosMap[number] = resolvedPhoto
                        photoUri = resolvedPhoto
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)
                if (previousStartTS == startTS) {
                    continue
                } else {
                    previousStartTS = startTS
                }

                val duration = cursor.getIntValue(Calls.DURATION)
                val type = cursor.getIntValue(Calls.TYPE)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]
                var specificNumber = ""
                var specificType = ""

                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber =
                        contacts.firstOrNull { it.contactId == contactIdWithMultipleNumbers }?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simAccount?.id ?: -1,
                        simColor = simAccount?.color ?: -1,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber
                    )
                )
            } while (cursor.moveToNext() && recentCalls.size < queryLimit)
        }

        return recentCalls
    }

    fun removeRecentCalls(ids: List<Int>, callback: () -> Unit) {
        ensureBackgroundThread {
            ids.chunked(30).forEach { chunk ->
                val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.delete(contentUri, selection, selectionArgs)
            }
            callback()
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
            if (it) {
                ensureBackgroundThread {
                    context.contentResolver.delete(contentUri, null, null)
                    callback()
                }
            }
        }
    }

    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (granted) {
                ensureBackgroundThread {
                    val values = objects
                        .sortedBy { it.startTS }
                        .map {
                            ContentValues().apply {
                                put(Calls.NUMBER, it.phoneNumber)
                                put(Calls.TYPE, it.type)
                                put(Calls.DATE, it.startTS)
                                put(Calls.DURATION, it.duration)
                                put(Calls.CACHED_NAME, it.name)
                            }
                        }.toTypedArray()

                    context.contentResolver.bulkInsert(contentUri, values)
                    callback()
                }
            }
        }
    }

    private fun processRecentCalls(calls: List<RecentCall>): List<RecentCall> {
        val sortedCalls = calls
            .sortedByDescending { it.startTS }
            .distinctBy { it.id }

        val consolidated = consolidateCallsWithin24Hours(sortedCalls)
        return markUnreturnedMissedCalls(consolidated, sortedCalls)
    }

    /**
     * Collapse every call to the same number within a rolling 24-hour window into a single row,
     * keeping the newest call as the head and older ones in [RecentCall.groupedCalls].
     */
    private fun consolidateCallsWithin24Hours(sortedCalls: List<RecentCall>): List<RecentCall> {
        val twentyFourHoursMs = 24 * 60 * 60 * 1000L
        val mergedList = mutableListOf<RecentCall>()
        // Newest open group index per comparable number — O(n) instead of O(n²) compares
        val openGroupIndexByNumber = HashMap<String, Int>()

        for (call in sortedCalls) {
            val key = comparableNumberKey(call.phoneNumber)
            val existingIndex = openGroupIndexByNumber[key]
            if (existingIndex != null) {
                val head = mergedList[existingIndex]
                if (head.startTS - call.startTS <= twentyFourHoursMs) {
                    val currentGrouped = head.groupedCalls ?: mutableListOf(head)
                    if (call.groupedCalls.isNullOrEmpty()) {
                        currentGrouped.add(call)
                    } else {
                        currentGrouped.addAll(call.groupedCalls)
                    }
                    mergedList[existingIndex] = head.copy(
                        groupedCalls = currentGrouped
                            .distinctBy { it.id }
                            .sortedByDescending { it.startTS }
                            .toMutableList()
                    )
                    continue
                }
            }

            openGroupIndexByNumber[key] = mergedList.size
            mergedList.add(call)
        }

        return mergedList
    }

    /**
     * A missed call stays highlighted until there is a later outgoing call to that same number.
     * Recalling one number never clears the highlight for a different number.
     */
    private fun markUnreturnedMissedCalls(
        calls: List<RecentCall>,
        allCalls: List<RecentCall>,
    ): List<RecentCall> {
        val latestOutgoingByNumber = HashMap<String, Long>()
        for (call in allCalls) {
            if (call.type != Calls.OUTGOING_TYPE) continue
            val key = comparableNumberKey(call.phoneNumber)
            val existing = latestOutgoingByNumber[key]
            if (existing == null || call.startTS > existing) {
                latestOutgoingByNumber[key] = call.startTS
            }
        }

        return calls.map { call ->
            val isUnreturnedMissed = if (call.type == Calls.MISSED_TYPE) {
                val latestOutgoing = latestOutgoingByNumber[comparableNumberKey(call.phoneNumber)]
                latestOutgoing == null || latestOutgoing <= call.startTS
            } else {
                false
            }
            call.copy(isUnreturnedMissed = isUnreturnedMissed)
        }
    }
}
