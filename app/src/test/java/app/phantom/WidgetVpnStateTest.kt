package app.phantom

import app.phantom.vpn.Phase
import app.phantom.vpn.TimedVpnStep
import app.phantom.vpn.VpnAction
import app.phantom.vpn.VpnOperation
import app.phantom.vpn.VpnSnapshot
import app.phantom.vpn.VpnStep
import app.phantom.vpn.WidgetVpnState
import app.phantom.vpn.WidgetVpnStatus
import org.junit.Assert.*
import org.junit.Test

class WidgetVpnStateTest {
    @Test fun consentBlocksRepeatedTapsAndDenialAllowsRetry() {
        val connect = VpnOperation(1, VpnAction.CONNECT, 0, listOf(TimedVpnStep(VpnStep.VPN_PERMISSION, 1)))
        val asking = WidgetVpnState.from(VpnSnapshot(), connect)
        assertEquals(WidgetVpnStatus.STARTING, asking.status)
        assertFalse(asking.allows(VpnAction.CONNECT))
        assertFalse(asking.allows(VpnAction.DISCONNECT))

        val denied = WidgetVpnState.from(VpnSnapshot(), connect.copy(
            steps = connect.steps + TimedVpnStep(VpnStep.PERMISSION_DENIED, 2),
        ))
        assertEquals(WidgetVpnStatus.ERROR, denied.status)
        assertEquals(VpnStep.PERMISSION_DENIED, denied.failure)
        assertTrue(denied.allows(VpnAction.CONNECT))
    }

    @Test fun activeAndWaitingUseTheLiveTunnelAndStoppingLocksControls() {
        val online = VpnSnapshot(phase = Phase.ONLINE, tunnel = true, serverId = "server",
            serverProtocol = "VLESS", connectedAt = 100)
        val active = WidgetVpnState.from(online, null)
        assertEquals(WidgetVpnStatus.ACTIVE, active.status)
        assertEquals("VLESS", active.protocol)
        assertEquals(100L, active.connectedAt)
        assertFalse(active.allows(VpnAction.CONNECT))
        assertTrue(active.allows(VpnAction.DISCONNECT))

        val waiting = WidgetVpnState.from(online.copy(phase = Phase.WAITING), null)
        assertEquals(WidgetVpnStatus.WAITING, waiting.status)
        assertTrue(waiting.allows(VpnAction.DISCONNECT))

        val noRoute = WidgetVpnState.from(online.copy(phase = Phase.TUNNEL, routeUnconfirmed = true), null)
        assertTrue(noRoute.allows(VpnAction.DISCONNECT))
        assertFalse(noRoute.allows(VpnAction.CONNECT))

        val stopping = WidgetVpnState.from(online, VpnOperation(2, VpnAction.DISCONNECT, 110))
        assertEquals(WidgetVpnStatus.STOPPING, stopping.status)
        assertFalse(stopping.allows(VpnAction.CONNECT))
        assertFalse(stopping.allows(VpnAction.DISCONNECT))
    }
}
