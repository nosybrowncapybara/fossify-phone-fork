package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isDefaultDialer
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.phone.BuildConfig
import org.fossify.phone.extensions.getStateCompat

object CallDiagnosticsHelper {

    @SuppressLint("MissingPermission")
    fun buildReport(context: Context, activeCall: Call? = null): String {
        val lines = mutableListOf<String>()
        val hasPhoneState = context.hasPermission(PERMISSION_READ_PHONE_STATE)

        lines += section("Device")
        lines += kv("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        lines += kv("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        lines += kv("App", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        lines += section("Dialer")
        lines += kv("Default phone app", context.isDefaultDialer().toYesNo())
        lines += kv("System default dialer", context.telecomManager.defaultDialerPackage ?: "unknown")
        lines += kv("In-call service active", (CallManager.inCallService != null).toYesNo())

        lines += section("Network")
        lines += describeConnectivity(context)

        if (!hasPhoneState) {
            lines += section("Phone / SIM")
            lines += "READ_PHONE_STATE permission not granted — SIM and call-route details are unavailable."
        } else {
            lines += section("Phone accounts")
            lines += describePhoneAccounts(context)

            lines += section("SIM subscriptions")
            lines += describeSimSubscriptions(context)

            lines += section("Default outgoing account")
            lines += describeDefaultOutgoingAccount(context)
        }

        lines += section("Active call")
        lines += describeActiveCall(activeCall)

        lines += section("How to read this")
        lines += "- Wi-Fi calling is controlled by Android and your carrier, not this app."
        lines += "- During a call, PROPERTY_WIFI=yes strongly suggests VoWiFi is in use."
        lines += "- Voice network type IWLAN also suggests Wi-Fi calling."
        lines += "- If stock dialer shows VoWiFi but PROPERTY_WIFI=no here, the OEM may block third-party dialers."
        lines += "- Place a test call, then refresh this screen or copy the report while the call is active."

        return lines.joinToString("\n")
    }

    @SuppressLint("MissingPermission")
    private fun describeConnectivity(context: Context): List<String> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellularConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val airplaneMode = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) != 0

        return listOf(
            kv("Airplane mode", airplaneMode.toYesNo()),
            kv("Wi-Fi connected", wifiConnected.toYesNo()),
            kv("Cellular connected", cellularConnected.toYesNo()),
            kv("Internet validated", validated.toYesNo()),
        )
    }

    @SuppressLint("MissingPermission")
    private fun describePhoneAccounts(context: Context): List<String> {
        val telecomManager = context.telecomManager
        val accounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (_: Exception) {
            emptyList()
        }

        if (accounts.isEmpty()) {
            return listOf("No call-capable phone accounts found.")
        }

        val lines = mutableListOf<String>()
        accounts.forEachIndexed { index, handle ->
            val account = try {
                telecomManager.getPhoneAccount(handle)
            } catch (_: Exception) {
                null
            }

            lines += "— Account ${index + 1} —"
            lines += kv("  Label", account?.label?.toString() ?: "unknown")
            lines += kv("  Handle", formatPhoneAccountHandle(handle))
            lines += kv("  Address", account?.address?.toString() ?: "unknown")
            lines += kv("  Enabled", (account?.isEnabled == true).toYesNo())
            lines += kv("  Capabilities", decodePhoneAccountCapabilities(account?.capabilities ?: 0))
        }
        return lines
    }

    @SuppressLint("MissingPermission")
    private fun describeSimSubscriptions(context: Context): List<String> {
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscriptions = subscriptionManager?.activeSubscriptionInfoList

        if (subscriptions.isNullOrEmpty()) {
            return listOf("No active SIM subscriptions found.")
        }

        val lines = mutableListOf<String>()
        subscriptions.forEach { info ->
            val telephony = context.getSystemService(TelephonyManager::class.java)
                .createForSubscriptionId(info.subscriptionId)
            lines += "— SIM slot ${info.simSlotIndex + 1} —"
            lines += kv("  Carrier", info.carrierName?.toString()?.ifBlank { "unknown" } ?: "unknown")
            lines += kv("  Display name", info.displayName?.toString()?.ifBlank { "unknown" } ?: "unknown")
            lines += kv("  Subscription ID", info.subscriptionId.toString())
            lines += kv("  SIM state", simStateName(telephony.simState))
            lines += kv("  Voice network", networkTypeName(telephony.voiceNetworkType))
            lines += kv("  Data network", networkTypeName(telephony.dataNetworkType))
            lines += kv("  Wi-Fi calling available", readWifiCallingAvailable(telephony))
        }
        return lines
    }

    @SuppressLint("MissingPermission")
    private fun describeDefaultOutgoingAccount(context: Context): List<String> {
        val telecomManager = context.telecomManager
        val defaultHandle = try {
            telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
        } catch (_: Exception) {
            null
        }

        if (defaultHandle == null) {
            return listOf("No default outgoing phone account for tel: URIs.")
        }

        val account = try {
            telecomManager.getPhoneAccount(defaultHandle)
        } catch (_: Exception) {
            null
        }

        return listOf(
            kv("Handle", formatPhoneAccountHandle(defaultHandle)),
            kv("Label", account?.label?.toString() ?: "unknown"),
            kv("Address", account?.address?.toString() ?: "unknown"),
        )
    }

    private fun describeActiveCall(call: Call?): List<String> {
        if (call == null) {
            return listOf("No active call detected by this app.")
        }

        val details = call.details
        val lines = mutableListOf<String>()
        lines += kv("State", callStateName(call.getStateCompat()))
        lines += kv("Direction", callDirectionName(details))
        lines += kv("Number", details.handle?.schemeSpecificPart ?: "hidden")
        lines += kv("Account handle", details.accountHandle?.let { formatPhoneAccountHandle(it) } ?: "unknown")
        lines += kv("Call capabilities", decodeCallCapabilities(details.callCapabilities))
        lines += kv("Call properties", decodeCallProperties(details.callProperties))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val onWifi = details.callProperties and Call.Details.PROPERTY_WIFI != 0
            lines += kv("PROPERTY_WIFI (VoWiFi indicator)", onWifi.toYesNo())
        }

        if (call.getStateCompat() == Call.STATE_DISCONNECTED) {
            lines += kv("Disconnect cause", disconnectCauseName(details.disconnectCause.code))
        }

        return lines
    }

    private fun decodePhoneAccountCapabilities(capabilities: Int): String {
        if (capabilities == 0) {
            return "none"
        }

        val names = mutableListOf<String>()
        if (capabilities and PhoneAccount.CAPABILITY_CALL_PROVIDER != 0) names += "CALL_PROVIDER"
        if (capabilities and PhoneAccount.CAPABILITY_CONNECTION_MANAGER != 0) names += "CONNECTION_MANAGER"
        if (capabilities and PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS != 0) names += "PLACE_EMERGENCY_CALLS"
        if (capabilities and PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION != 0) names += "SIM_SUBSCRIPTION"
        if (capabilities and PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING != 0) names += "SUPPORTS_VIDEO_CALLING"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (capabilities and PhoneAccount.CAPABILITY_SUPPORTS_TRANSACTIONAL_OPERATIONS != 0) {
                names += "SUPPORTS_TRANSACTIONAL_OPERATIONS"
            }
        }
        return if (names.isEmpty()) "0x${capabilities.toString(16)}" else names.joinToString(", ")
    }

    private fun decodeCallCapabilities(capabilities: Int): String {
        if (capabilities == 0) {
            return "none"
        }

        val names = mutableListOf<String>()
        if (capabilities and Call.Details.CAPABILITY_HOLD != 0) names += "HOLD"
        if (capabilities and Call.Details.CAPABILITY_SUPPORT_HOLD != 0) names += "SUPPORT_HOLD"
        if (capabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE != 0) names += "MERGE_CONFERENCE"
        if (capabilities and Call.Details.CAPABILITY_SWAP_CONFERENCE != 0) names += "SWAP_CONFERENCE"
        if (capabilities and Call.Details.CAPABILITY_RESPOND_VIA_TEXT != 0) names += "RESPOND_VIA_TEXT"
        if (capabilities and Call.Details.CAPABILITY_MUTE != 0) names += "MUTE"
        if (capabilities and Call.Details.CAPABILITY_MANAGE_CONFERENCE != 0) names += "MANAGE_CONFERENCE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (capabilities and Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_RX != 0) names += "VT_LOCAL_RX"
            if (capabilities and Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_TX != 0) names += "VT_LOCAL_TX"
            if (capabilities and Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_RX != 0) names += "VT_REMOTE_RX"
            if (capabilities and Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_TX != 0) names += "VT_REMOTE_TX"
        }
        return if (names.isEmpty()) "0x${capabilities.toString(16)}" else names.joinToString(", ")
    }

    private fun decodeCallProperties(properties: Int): String {
        if (properties == 0) {
            return "none"
        }

        val names = mutableListOf<String>()
        if (properties and Call.Details.PROPERTY_CONFERENCE != 0) names += "CONFERENCE"
        if (properties and Call.Details.PROPERTY_GENERIC_CONFERENCE != 0) names += "GENERIC_CONFERENCE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (properties and Call.Details.PROPERTY_WIFI != 0) names += "WIFI"
            if (properties and Call.Details.PROPERTY_CROSS_SIM != 0) names += "CROSS_SIM"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (properties and Call.Details.PROPERTY_ASSISTED_DIALING != 0) names += "ASSISTED_DIALING"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (properties and Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL != 0) {
                names += "NETWORK_IDENTIFIED_EMERGENCY_CALL"
            }
        }
        return if (names.isEmpty()) "0x${properties.toString(16)}" else names.joinToString(", ")
    }

    private fun callStateName(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        else -> "UNKNOWN ($state)"
    }

    private fun callDirectionName(details: Call.Details): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return when (details.callDirection) {
                Call.Details.DIRECTION_INCOMING -> "INCOMING"
                Call.Details.DIRECTION_OUTGOING -> "OUTGOING"
                else -> "UNKNOWN (${details.callDirection})"
            }
        }
        return "unknown (requires API 29+)"
    }

    private fun disconnectCauseName(cause: Int): String = when (cause) {
        DisconnectCause.UNKNOWN -> "UNKNOWN"
        DisconnectCause.ERROR -> "ERROR"
        DisconnectCause.LOCAL -> "LOCAL"
        DisconnectCause.REMOTE -> "REMOTE"
        DisconnectCause.CANCELED -> "CANCELED"
        DisconnectCause.MISSED -> "MISSED"
        DisconnectCause.REJECTED -> "REJECTED"
        DisconnectCause.BUSY -> "BUSY"
        DisconnectCause.ANSWERED_ELSEWHERE -> "ANSWERED_ELSEWHERE"
        else -> "CODE_$cause"
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "UNKNOWN ($state)"
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi calling)"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "TYPE_$type"
    }

    private fun readWifiCallingAvailable(telephony: TelephonyManager): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "requires API 28+"
        }

        return try {
            val method = TelephonyManager::class.java.getMethod("isWifiCallingAvailable")
            (method.invoke(telephony) as Boolean).toYesNo()
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun formatPhoneAccountHandle(handle: android.telecom.PhoneAccountHandle): String {
        return "${handle.componentName.flattenToString()}/${handle.id}"
    }

    private fun section(title: String): String = "\n[$title]"

    private fun kv(key: String, value: String): String = "$key: $value"

    private fun Boolean.toYesNo(): String = if (this) "yes" else "no"
}
