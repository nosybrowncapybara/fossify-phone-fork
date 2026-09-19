package org.fossify.phone.fragments

import android.content.Context
import android.util.AttributeSet
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.MainActivity
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.adapters.RecentCallsAdapter
import org.fossify.phone.databinding.FragmentRecentsBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.runAfterAnimations
import org.fossify.phone.extensions.startAddContactIntent
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.extensions.startContactDetailsIntent
import org.fossify.phone.helpers.DebouncedSearch
import org.fossify.phone.helpers.RecentCallContactResolver
import org.fossify.phone.helpers.RecentsHelper
import org.fossify.phone.interfaces.RefreshItemsListener
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)
    private val debouncedSearch = DebouncedSearch()
    private val recentsLock = Any()

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            showNoCallLogPermissionState()
        }
    }

    private fun showNoCallLogPermissionState() {
        binding.progressIndicator.hide()
        binding.recentsList.beGone()
        showOrHidePlaceholder(true)
        binding.recentsPlaceholder2.beVisible()
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(properPrimaryColor)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        if (invalidate) {
            allRecentCalls = emptyList()
        }

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            showNoCallLogPermissionState()
            callback?.invoke()
            return
        }

        binding.progressIndicator.show()
        binding.recentsPlaceholder.beGone()
        binding.recentsPlaceholder2.beGone()

        refreshCallLog(loadAll = false) {
            binding.recentsList.runAfterAnimations {
                refreshCallLog(loadAll = true) {
                    callback?.invoke()
                }
            }
        }
    }

    fun refreshAfterContactsChanged(cachedContacts: List<Contact>) {
        ensureBackgroundThread {
            val snapshot = synchronized(recentsLock) { allRecentCalls.toList() }
            if (snapshot.none { it is RecentCall }) {
                return@ensureBackgroundThread
            }

            val recentCalls = snapshot.filterIsInstance<RecentCall>()
            val updatedCalls = RecentCallContactResolver.applyContactChanges(context, recentCalls, cachedContacts)
            applyUpdatedCalls(snapshot, updatedCalls)
        }
    }

    private fun applyUpdatedCalls(snapshot: List<CallLogItem>, updatedCalls: List<RecentCall>) {
        val updatedCallsById = updatedCalls.associateBy { it.getItemId() }
        val updatedList = snapshot.map { item ->
            if (item is RecentCall) {
                updatedCallsById[item.getItemId()] ?: item
            } else {
                item
            }
        }

        post {
            if (!isAttachedToWindow) {
                return@post
            }

            synchronized(recentsLock) {
                allRecentCalls = updatedList
            }

            if (searchQuery.isNullOrEmpty()) {
                recentsAdapter?.updateItems(updatedList)
            } else {
                val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
                updateSearchResult(fixedText)
            }
        }
    }

    override fun onSearchClosed() {
        debouncedSearch.cancel()
        searchQuery = null
        showOrHidePlaceholder(allRecentCalls.isEmpty())
        recentsAdapter?.updateItems(allRecentCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        val fixedText = text.trim().replace("\\s+".toRegex(), " ")
        if (fixedText.isEmpty()) {
            debouncedSearch.cancel()
            showOrHidePlaceholder(allRecentCalls.isEmpty())
            recentsAdapter?.updateItems(allRecentCalls)
            return
        }

        debouncedSearch.submit {
            updateSearchResult(fixedText)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult(fixedText: String) {
        val snapshot = synchronized(recentsLock) { allRecentCalls.toList() }
        val recentCalls = snapshot
            .filterIsInstance<RecentCall>()
            .filter {
                it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
            }
            .sortedWith(
                compareByDescending<RecentCall> { it.dayCode }
                    .thenByDescending { it.name.startsWith(fixedText, true) }
                    .thenByDescending { it.startTS }
            )

        post {
            if (!isAttachedToWindow) {
                return@post
            }

            showOrHidePlaceholder(recentCalls.isEmpty())
            recentsAdapter?.updateItems(groupCallsByDate(recentCalls), fixedText)
        }
    }

    fun requestCallLogPermission() {
        (activity as? MainActivity)?.prepareManualCallLogPermissionRequest()
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                refreshItems(invalidate = true)
            } else {
                showNoCallLogPermissionState()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show && !binding.progressIndicator.isVisible()) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        binding.progressIndicator.hide()
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startContactDetailsIntent(contact)
                        } else {
                            activity?.startAddContactIntent(recentCall.phoneNumber)
                        }
                    }
                )

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            synchronized(recentsLock) {
                allRecentCalls = it
            }
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(it) }
            } else {
                val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
                ensureBackgroundThread {
                    updateSearchResult(fixedText)
                }
            }

            callback?.invoke()
        }
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<CallLogItem>) -> Unit) {
        val queryCount = if (loadAll) RecentsHelper.QUERY_LIMIT_MAX else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()

        with(recentsHelper) {
            if (context.config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount) {
                    prepareCallLog(it, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<CallLogItem>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ensureBackgroundThread {
            val filteredCalls = if (SMT_PRIVATE in context.baseConfig.ignoredContactSources) {
                maybeFilterPrivateCalls(calls, getPrivateContacts())
            } else {
                calls
            }
            callback(groupCallsByDate(filteredCalls))
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = context.baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): MutableList<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return (activity as MainActivity).cachedContacts.find { it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
