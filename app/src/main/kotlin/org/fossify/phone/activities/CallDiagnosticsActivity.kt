package org.fossify.phone.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityCallDiagnosticsBinding
import org.fossify.phone.helpers.CallDiagnosticsHelper
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallManagerListener

class CallDiagnosticsActivity : SimpleActivity(), CallManagerListener {
    private val binding by viewBinding(ActivityCallDiagnosticsBinding::inflate)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshReport()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(callDiagnosticsScrollview))
            setupMaterialScrollListener(callDiagnosticsScrollview, callDiagnosticsAppbar)
        }

        handlePermission(PERMISSION_READ_PHONE_STATE) {
            refreshReport()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.callDiagnosticsAppbar, NavigationIcon.Arrow)
        CallManager.addListener(this)
        refreshHandler.post(refreshRunnable)
        updateTextColors(binding.callDiagnosticsScrollview)
    }

    override fun onPause() {
        super.onPause()
        CallManager.removeListener(this)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_call_diagnostics, menu)
        updateMenuItemColors(menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.copy_report -> {
                copyToClipboard(binding.callDiagnosticsReport.text.toString())
                toast(org.fossify.commons.R.string.value_copied_to_clipboard)
                true
            }

            R.id.refresh_report -> {
                refreshReport()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStateChanged() {
        refreshReport()
    }

    override fun onAudioStateChanged(audioState: org.fossify.phone.models.AudioRoute) = Unit

    override fun onPrimaryCallChanged(call: android.telecom.Call) {
        refreshReport()
    }

    private fun refreshReport() {
        binding.callDiagnosticsReport.text = CallDiagnosticsHelper.buildReport(
            context = this,
            activeCall = CallManager.getPrimaryCall(),
        )
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2_000L
    }
}
