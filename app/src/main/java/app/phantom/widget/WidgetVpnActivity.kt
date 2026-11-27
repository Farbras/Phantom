package app.phantom.widget

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import app.phantom.PhantomApp
import app.phantom.vpn.PhantomVpnService
import app.phantom.vpn.VpnAction
import app.phantom.vpn.VpnRuntime
import app.phantom.vpn.VpnStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class WidgetVpnActivity : ComponentActivity() {
    private var operation: Long? = null
    private var awaitingConsent = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingConsent = false
        if (result.resultCode == RESULT_OK) lifecycleScope.launch { withResumed(::startVpn) }
        else complete(VpnStep.PERMISSION_DENIED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PhantomApp
        val action = when (intent.action) {
            DISCONNECT -> VpnAction.DISCONNECT
            CONNECT -> VpnAction.CONNECT
            else -> return finish()
        }
        val saved = savedInstanceState?.getLong("operation")
        operation = if (saved == VpnRuntime.pendingConnectId() && saved != null) saved
            else VpnRuntime.requestFromWidget(action)
        if (operation == null) return finish()
        awaitingConsent = savedInstanceState?.getBoolean("awaitingConsent") == true
        if (awaitingConsent) return

        lifecycleScope.launch {
            try {
                if (action == VpnAction.DISCONNECT) {
                    withResumed { PhantomVpnService.disconnect(this@WidgetVpnActivity) }
                    complete()
                    return@launch
                }
                app.ready.await()
                val catalog = app.vault.state.value
                if (!catalog.autoSelect && catalog.selectedServer()?.issue != "") {
                    complete(VpnStep.NO_PROFILE)
                    return@launch
                }
                withResumed {
                    if (operation != VpnRuntime.pendingConnectId()) complete()
                    else {
                        val consent = VpnService.prepare(this@WidgetVpnActivity)
                        if (consent == null) startVpn()
                        else {
                            VpnRuntime.step(operation, VpnStep.VPN_PERMISSION)
                            awaitingConsent = true
                            permission.launch(consent)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VpnRuntime.error(e)
                complete(if (action == VpnAction.DISCONNECT) VpnStep.DISCONNECT_FAILED else VpnStep.CONNECT_FAILED)
            }
        }
    }

    private fun startVpn() {
        try {
            if (operation == VpnRuntime.pendingConnectId() && operation != null) PhantomVpnService.connect(this)
            complete()
        } catch (e: Exception) {
            VpnRuntime.error(e)
            complete(VpnStep.CONNECT_FAILED)
        }
    }

    private fun complete(step: VpnStep? = null) {
        if (step != null) VpnRuntime.step(operation, step)
        operation = null
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        operation?.let { outState.putLong("operation", it) }
        outState.putBoolean("awaitingConsent", awaitingConsent)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) VpnRuntime.step(operation, VpnStep.INTERRUPTED)
        super.onDestroy()
    }

    companion object {
        internal const val CONNECT = "app.phantom.widget.CONNECT"
        internal const val DISCONNECT = "app.phantom.widget.DISCONNECT"
    }
}
