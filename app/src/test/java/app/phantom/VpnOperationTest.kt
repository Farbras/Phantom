package app.phantom

import app.phantom.vpn.*
import org.junit.Assert.*
import org.junit.Test

class VpnOperationTest {
    @Test fun historyKeepsWholeOperationsAndUsesRealPhaseForSuccess() {
        val canceled=checkNotNull(VpnRuntime.request(VpnAction.CONNECT))
        assertNull(VpnRuntime.request(VpnAction.CONNECT))
        VpnRuntime.step(canceled,VpnStep.LOADING_PROFILE)
        VpnRuntime.updatePhase(canceled,VpnStep.WAITING_NETWORK) { it.copy(phase=Phase.WAITING) }
        assertTrue(VpnRuntime.operations.value.last().pending)

        val stop=checkNotNull(VpnRuntime.request(VpnAction.DISCONNECT))
        assertEquals(listOf(VpnStep.LOADING_PROFILE,VpnStep.WAITING_NETWORK,VpnStep.CONNECT_CANCELED),steps(0))
        assertNull(VpnRuntime.request(VpnAction.DISCONNECT))
        assertNull(VpnRuntime.serviceOperation(VpnAction.CONNECT))
        VpnRuntime.step(canceled,VpnStep.ROUTE_UNCONFIRMED)
        VpnRuntime.updatePhase(stop) { it.copy(phase=Phase.STOPPING) }
        VpnRuntime.step(stop,VpnStep.SAVING_COUNTERS)
        assertEquals(listOf(VpnStep.SAVING_COUNTERS),steps(1))
        VpnRuntime.updatePhase(stop) { VpnSnapshot() }
        assertEquals(listOf(VpnStep.SAVING_COUNTERS,VpnStep.DISCONNECTED),steps(1))

        val connect=checkNotNull(VpnRuntime.request(VpnAction.CONNECT))
        assertEquals(listOf(stop,connect),VpnRuntime.operations.value.map { it.id })
        assertEquals(listOf(VpnStep.SAVING_COUNTERS,VpnStep.DISCONNECTED),steps(0))
        VpnRuntime.updatePhase(connect,VpnStep.CHECKING_ROUTE) { it.copy(phase=Phase.TUNNEL) }
        VpnRuntime.updatePhase(connect,VpnStep.ROUTE_UNCONFIRMED) { it }
        assertTrue(VpnRuntime.operations.value.last().pending)
        VpnRuntime.updatePhase(connect) { it.copy(phase=Phase.ONLINE) }
        assertEquals(listOf(VpnStep.CHECKING_ROUTE,VpnStep.ROUTE_UNCONFIRMED,VpnStep.CONNECTED),steps(1))
        VpnRuntime.updatePhase(null) { VpnSnapshot() }
    }

    private fun steps(index: Int) = VpnRuntime.operations.value[index].steps.map { it.step }
}
