package app.phantom.vpn

internal enum class VpnAction { CONNECT, DISCONNECT }

// Only this fixed vocabulary reaches the terminal. Service logs and exceptions may contain profile data.
internal enum class VpnStep(val finishes: Boolean = false) {
    VPN_PERMISSION, LOADING_PROFILE, VALIDATING_CONFIG, STARTING_XRAY, CREATING_TUN,
    STARTING_ADAPTER, CHECKING_ROUTE, WAITING_NETWORK, ROUTE_UNCONFIRMED,
    STOPPING_ADAPTER, STOPPING_XRAY, CLOSING_TUN, RESTORING_ROUTE, SAVING_COUNTERS,
    CONNECTED(true), DISCONNECTED(true), PERMISSION_DENIED(true), NO_PROFILE(true),
    CONNECT_FAILED(true), DISCONNECT_FAILED(true), COUNTERS_FAILED(true),
    CONNECT_CANCELED(true), INTERRUPTED(true),
}

internal data class TimedVpnStep(val step: VpnStep, val time: Long)

internal data class VpnOperation(
    val id: Long,
    val action: VpnAction,
    val startedAt: Long,
    val steps: List<TimedVpnStep> = emptyList(),
) {
    val pending get() = steps.lastOrNull()?.step?.finishes != true
}

internal fun availableVpnAction(phase: Phase, pending: VpnAction?) = when {
    pending == VpnAction.DISCONNECT || phase == Phase.STARTING || phase == Phase.STOPPING -> null
    phase != Phase.OFF -> VpnAction.DISCONNECT
    pending == null -> VpnAction.CONNECT
    else -> null
}
