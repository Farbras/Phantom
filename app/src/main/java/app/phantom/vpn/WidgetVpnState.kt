package app.phantom.vpn

internal enum class WidgetVpnStatus { OFF, STARTING, ACTIVE, WAITING, NO_ROUTE, ERROR, STOPPING }

internal data class WidgetVpnState(
    val status: WidgetVpnStatus,
    val failure: VpnStep?,
    val serverId: String,
    val serverName: String,
    val protocol: String,
    val connectedAt: Long,
) {
    val powerAction: VpnAction
        get() = when (status) {
            WidgetVpnStatus.ACTIVE, WidgetVpnStatus.WAITING, WidgetVpnStatus.NO_ROUTE, WidgetVpnStatus.STOPPING ->
                VpnAction.DISCONNECT
            WidgetVpnStatus.STARTING -> if (connectedAt > 0) VpnAction.DISCONNECT else VpnAction.CONNECT
            WidgetVpnStatus.OFF, WidgetVpnStatus.ERROR -> VpnAction.CONNECT
        }

    fun allows(action: VpnAction): Boolean =
        status != WidgetVpnStatus.STARTING && status != WidgetVpnStatus.STOPPING && action == powerAction

    companion object {
        fun from(vpn: VpnSnapshot, operation: VpnOperation?): WidgetVpnState {
            val last = operation?.steps?.lastOrNull()?.step
            val pending = operation?.takeIf { it.pending }?.action
            val failure = when (last) {
                VpnStep.PERMISSION_DENIED, VpnStep.NO_PROFILE, VpnStep.NO_AUTO_SERVER,
                VpnStep.CONNECT_FAILED, VpnStep.DISCONNECT_FAILED, VpnStep.COUNTERS_FAILED,
                VpnStep.INTERRUPTED -> last
                else -> null
            }
            val status = when {
                pending == VpnAction.DISCONNECT || vpn.phase == Phase.STOPPING -> WidgetVpnStatus.STOPPING
                pending == VpnAction.CONNECT && last != VpnStep.WAITING_NETWORK &&
                    last != VpnStep.ROUTE_UNCONFIRMED -> WidgetVpnStatus.STARTING
                vpn.phase == Phase.STARTING -> WidgetVpnStatus.STARTING
                vpn.phase == Phase.WAITING -> WidgetVpnStatus.WAITING
                vpn.phase == Phase.ONLINE && vpn.tunnel -> WidgetVpnStatus.ACTIVE
                vpn.phase == Phase.TUNNEL && vpn.routeUnconfirmed -> WidgetVpnStatus.NO_ROUTE
                vpn.phase == Phase.TUNNEL -> WidgetVpnStatus.STARTING
                vpn.phase == Phase.ERROR || failure != null -> WidgetVpnStatus.ERROR
                else -> WidgetVpnStatus.OFF
            }
            return WidgetVpnState(
                status, failure,
                vpn.serverId.takeIf { vpn.tunnel }.orEmpty(),
                vpn.serverName.takeIf { vpn.tunnel }.orEmpty(),
                vpn.serverProtocol.takeIf { vpn.tunnel }.orEmpty(),
                vpn.connectedAt.takeIf { vpn.tunnel } ?: 0L,
            )
        }
    }
}
