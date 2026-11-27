package app.phantom.vpn

import android.os.SystemClock
import app.phantom.data.safeError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val NO_AUTO_SERVER_MESSAGE = "Ни один сервер не ответил. Выберите сервер вручную."

enum class Phase { OFF, STARTING, TUNNEL, ONLINE, WAITING, ERROR, STOPPING }
data class VpnSnapshot(
    val phase: Phase=Phase.OFF, val message: String="Отключено", val serverId: String="",
    val tunnel: Boolean=false, val online: Boolean=false, val connectedAt: Long=0,
    val tx: Long=0, val rx: Long=0, val txSpeed: Long=0, val rxSpeed: Long=0, val latency: Long?=null,
    val routeUnconfirmed: Boolean=false, val serverName: String="",
    val serverProtocol: String="",
)
object VpnRuntime {
    internal val mutable=MutableStateFlow(VpnSnapshot())
    val state=mutable.asStateFlow()
    private val transcript=MutableStateFlow<List<VpnOperation>>(emptyList())
    internal val operations=transcript.asStateFlow()
    private var nextOperationId=0L

    private fun pendingOperation() = transcript.value.lastOrNull()?.takeIf { it.pending }

    internal fun request(action: VpnAction): Long? = synchronized(transcript) {
        if (availableVpnAction(state.value.phase, pendingOperation()?.action) != action) null
        else beginOperation(action)
    }

    internal fun widgetState() = synchronized(transcript) {
        WidgetVpnState.from(state.value, transcript.value.lastOrNull())
    }

    internal fun requestFromWidget(action: VpnAction): Long? = synchronized(transcript) {
        if (widgetState().allows(action)) beginOperation(action) else null
    }

    internal fun serviceOperation(action: VpnAction): Long? = synchronized(transcript) {
        val pending=pendingOperation()
        when {
            pending?.action==action -> pending.id
            pending?.action==VpnAction.DISCONNECT -> null
            else -> beginOperation(action)
        }
    }

    private fun beginOperation(action: VpnAction): Long {
        pendingOperation()?.let { step(it.id, VpnStep.CONNECT_CANCELED) }
        val operation=VpnOperation(++nextOperationId, action, SystemClock.elapsedRealtime())
        transcript.value=(transcript.value + operation).takeLast(2)
        return operation.id
    }

    internal fun step(id: Long?, step: VpnStep) = synchronized(transcript) {
        val operation=pendingOperation() ?: return@synchronized
        if (operation.id != id || operation.steps.any { it.step == step }) return@synchronized
        transcript.value=transcript.value.dropLast(1) + operation.copy(
            steps=operation.steps + TimedVpnStep(step, SystemClock.elapsedRealtime()),
        )
    }

    internal fun updatePhase(id: Long?, event: VpnStep? = null, update: (VpnSnapshot) -> VpnSnapshot) = synchronized(transcript) {
        mutable.update(update)
        val operation=pendingOperation()?.takeIf { it.id == id } ?: return@synchronized
        val result=event ?: when {
            state.value.phase == Phase.ONLINE && operation.action == VpnAction.CONNECT -> VpnStep.CONNECTED
            state.value.phase == Phase.OFF && operation.action == VpnAction.DISCONNECT -> VpnStep.DISCONNECTED
            else -> null
        }
        if (result != null) step(id, result)
    }

    internal fun pendingConnectId(): Long? = synchronized(transcript) {
        pendingOperation()?.takeIf { it.action == VpnAction.CONNECT }?.id
    }

    private val lines=MutableStateFlow<List<String>>(emptyList())
    val logs=lines.asStateFlow()
    @Volatile var logLevel="warning"
    fun log(message: String, level: String="info") {
        val rank=mapOf("debug" to 0,"info" to 1,"warning" to 2,"error" to 3,"none" to 4)
        if((rank[level] ?: 1)<(rank[logLevel] ?: 2)) return
        val time=SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(Date())
        synchronized(lines) { lines.value=(lines.value+"$time · $message").takeLast(300) }
    }
    fun error(e: Throwable) { log(safeError(e),"error") }
    fun clearLogs() { lines.value=emptyList() }
}
