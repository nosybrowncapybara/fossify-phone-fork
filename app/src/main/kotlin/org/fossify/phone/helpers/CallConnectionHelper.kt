package org.fossify.phone.helpers

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telephony.TelephonyManager
import org.fossify.phone.R
import org.fossify.phone.extensions.getStateCompat

enum class CallConnectionType {
    WIFI,
    MOBILE,
    UNKNOWN,
}

object CallConnectionHelper {

    fun getConnectionType(context: Context, call: Call?): CallConnectionType {
        if (call == null) {
            return CallConnectionType.UNKNOWN
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (call.details.callProperties and Call.Details.PROPERTY_WIFI != 0) {
                return CallConnectionType.WIFI
            }
        }

        val state = call.getStateCompat()
        if (state != Call.STATE_ACTIVE && state != Call.STATE_HOLDING) {
            return CallConnectionType.UNKNOWN
        }

        return try {
            val voiceNetworkType = context.getSystemService(TelephonyManager::class.java).voiceNetworkType
            when (voiceNetworkType) {
                TelephonyManager.NETWORK_TYPE_IWLAN -> CallConnectionType.WIFI
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> CallConnectionType.UNKNOWN
                else -> CallConnectionType.MOBILE
            }
        } catch (_: Exception) {
            CallConnectionType.UNKNOWN
        }
    }

    fun getLabelResId(type: CallConnectionType): Int? = when (type) {
        CallConnectionType.WIFI -> R.string.wifi_call_active
        CallConnectionType.MOBILE -> R.string.mobile_call_active
        CallConnectionType.UNKNOWN -> null
    }
}
