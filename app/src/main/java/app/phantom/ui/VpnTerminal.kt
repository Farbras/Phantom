package app.phantom.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.phantom.vpn.*
import kotlinx.coroutines.delay

private val shellText=Color(0xFFF3E9F4)
private val shellMint=Color(0xFFA6E3A1)
private val shellMuted=Color(0xFFAE9EB7)
private val shellLine=Color(0xFF47324B)
private val shellWarning=Color(0xFFE9C58C)
private val shellError=Color(0xFFFFADB6)
private val shellStyle=TextStyle(
    fontFamily=FontFamily.Monospace,
    fontSize=12.sp,
    lineHeight=20.sp,
    lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None),
)
private const val LINE_DELAY_MS=245L

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VpnTerminal(
    phase: Phase,
    routeUnconfirmed: Boolean,
    uptime: String?,
    enabled: Boolean,
    animate: Boolean,
    characterDelay: Long,
    transcriptMaxHeight: Dp,
    onPower: (VpnAction) -> Unit,
) {
    val history by VpnRuntime.operations.collectAsStateWithLifecycle()
    val latest=history.lastOrNull()
    val pending=latest?.takeIf { it.pending }?.action
    val action=availableVpnAction(phase,pending)
    val (status,statusColor)=terminalStatus(phase,routeUnconfirmed,latest)
    Surface(
        modifier=Modifier.widthIn(max=490.dp).fillMaxWidth(),
        color=Color(0xFF1A111D),
        contentColor=shellText,
        shape=RoundedCornerShape(15.dp),
        border=BorderStroke(1.dp,Color(0xFF4B3853)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2D1E31)).padding(horizontal=12.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(9.dp),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                Surface(shape=RoundedCornerShape(4.dp),color=Color(0xFF1B121E),border=BorderStroke(1.dp,Color(0xFF644D68))) {
                    Text(">_",Modifier.padding(horizontal=4.dp),style=shellStyle,color=shellMint)
                }
                Text(buildAnnotatedString {
                    append("Phantom VPN")
                    withStyle(SpanStyle(color=shellMuted)) { append(" / ") }
                    append("bash")
                },style=shellStyle,fontWeight=FontWeight.Medium)
            }
            HorizontalDivider(color=shellLine)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                TerminalTranscript(history,animate,characterDelay,transcriptMaxHeight)
            }
            HorizontalDivider(color=shellLine)
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(10.dp,Alignment.End),
                verticalArrangement=Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.weight(1f).widthIn(min=104.dp).align(Alignment.CenterVertically),
                    horizontalArrangement=Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.padding(top=6.dp).size(6.dp).background(statusColor,CircleShape))
                    Column(Modifier.semantics { liveRegion=LiveRegionMode.Polite }) {
                        Text(status,color=statusColor,fontSize=12.sp,lineHeight=16.sp)
                        if(uptime!=null) Text(uptime,color=shellMuted,style=shellStyle)
                    }
                }
                Button(
                    onClick={ if(action!=null) onPower(action) },
                    enabled=enabled && action!=null,
                    modifier=Modifier.heightIn(min=48.dp).widthIn(min=115.dp).align(Alignment.CenterVertically),
                    shape=RoundedCornerShape(7.dp),
                    border=BorderStroke(1.dp,if(action==VpnAction.CONNECT && enabled) Color(0xFFCDB0EF) else Color(0xFF654D70)),
                    colors=ButtonDefaults.buttonColors(
                        containerColor=if(action==VpnAction.CONNECT) Color(0xFFD9BFF7) else Color(0xFF322036),
                        contentColor=if(action==VpnAction.CONNECT) Color(0xFF23122C) else Color(0xFFECD9F7),
                        disabledContainerColor=Color(0xFF322036),
                        disabledContentColor=Color(0xFFECD9F7),
                    ),
                    contentPadding=PaddingValues(horizontal=11.dp,vertical=7.dp),
                ) {
                    Text(when(action) {
                        VpnAction.CONNECT -> "Подключить"
                        VpnAction.DISCONNECT -> "Отключить"
                        null -> "Выполняется…"
                    },fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TerminalTranscript(history: List<VpnOperation>, animate: Boolean, characterDelay: Long, maxHeight: Dp) {
    val scroll=rememberScrollState()
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { if(it!=Int.MAX_VALUE) scroll.scrollTo(it) }
    }
    val latest=history.lastOrNull()
    val frame=latest?.let { terminalFrame(it,animate,characterDelay) }
    val showPrompt=latest==null || (!latest.pending && frame?.visibleSteps==latest.steps.size)
    var idleCursor by remember { mutableStateOf(true) }
    LaunchedEffect(showPrompt,animate) {
        idleCursor=true
        if(showPrompt && animate) repeat(3) {
            delay(485)
            idleCursor=false
            delay(485)
            idleCursor=true
        }
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min=minOf(300.dp,maxHeight),max=maxHeight).background(Color(0xFF1B0F1E))
            .verticalScroll(scroll).padding(horizontal=12.dp,vertical=14.dp),
    ) {
        history.forEachIndexed { index, operation ->
            if(index>0) Spacer(Modifier.height(4.dp))
            val command=operation.action.command
            val current=if(operation.id==latest?.id) frame else null
            val characters=current?.characters ?: command.length
            ShellPrompt(command.take(characters),cursor=characters<command.length)
            operation.steps.take(current?.visibleSteps ?: operation.steps.size).forEach { entry ->
                Text(entry.step.label,style=shellStyle,color=entry.step.color)
            }
        }
        if(showPrompt) ShellPrompt("",cursor=!animate || idleCursor)
    }
}

private data class TerminalFrame(val characters: Int,val visibleSteps: Int)

@Composable
private fun terminalFrame(operation: VpnOperation, animate: Boolean, characterDelay: Long): TerminalFrame {
    val characters=operation.action.command.length
    if(!animate) return TerminalFrame(characters,operation.steps.size)

    val commandEnd=operation.startedAt + characters * characterDelay
    val revealTimes=remember(operation,commandEnd) {
        var next=commandEnd
        operation.steps.map { entry ->
            val reveal=maxOf(next,entry.time)
            next=reveal + LINE_DELAY_MS
            reveal
        }
    }
    val tick by produceState(SystemClock.elapsedRealtime(),operation,characterDelay) {
        while(true) {
            val now=SystemClock.elapsedRealtime()
            value=now
            val next=if(now<commandEnd) {
                val typed=(now-operation.startedAt)/characterDelay
                operation.startedAt + (typed+1)*characterDelay
            } else revealTimes.firstOrNull { it>now } ?: break
            delay((next-now).coerceAtLeast(1L))
        }
    }
    val now=maxOf(tick,SystemClock.elapsedRealtime())
    return TerminalFrame(
        ((now-operation.startedAt)/characterDelay).coerceIn(0L,characters.toLong()).toInt(),
        revealTimes.count { it<=now },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShellPrompt(command: String,cursor: Boolean) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color=shellMint)) { append("phantom@linux") }
            append(':')
            withStyle(SpanStyle(color=Color(0xFFD0B3F0))) { append('~') }
            append('$')
        },style=shellStyle,color=shellText)
        if(command.isNotEmpty() || cursor) Text(buildAnnotatedString {
            append(command)
            if(cursor) withStyle(SpanStyle(color=Color(0xFFDAC9E4))) { append('▉') }
        },style=shellStyle,color=shellText)
    }
}

private val VpnAction.command get() = when(this) {
    VpnAction.CONNECT -> "phantom connect"
    VpnAction.DISCONNECT -> "phantom disconnect"
}

private val VpnStep.label get() = when(this) {
    VpnStep.VPN_PERMISSION -> "Waiting for Android permissions..."
    VpnStep.LOADING_PROFILE -> "Loading VPN profile..."
    VpnStep.TESTING_SERVERS -> "Checking server latency..."
    VpnStep.VALIDATING_CONFIG -> "Validating Xray config..."
    VpnStep.STARTING_XRAY -> "Starting Xray core..."
    VpnStep.CREATING_TUN -> "Creating TUN interface..."
    VpnStep.STARTING_ADAPTER -> "Starting tunnel adapter..."
    VpnStep.CHECKING_ROUTE -> "Checking VPN route..."
    VpnStep.WAITING_NETWORK -> "Waiting for Wi-Fi or mobile data..."
    VpnStep.ROUTE_UNCONFIRMED -> "[ WARN ] VPN route not confirmed"
    VpnStep.STOPPING_ADAPTER -> "Stopping tunnel adapter..."
    VpnStep.STOPPING_XRAY -> "Stopping Xray core..."
    VpnStep.CLOSING_TUN -> "Closing TUN interface..."
    VpnStep.RESTORING_ROUTE -> "Restoring default route..."
    VpnStep.SAVING_COUNTERS -> "Saving traffic counters..."
    VpnStep.CONNECTED -> "[ OK ] VPN connected"
    VpnStep.DISCONNECTED -> "[ OK ] VPN disconnected"
    VpnStep.PERMISSION_DENIED -> "[ ERROR ] VPN permission denied"
    VpnStep.NO_PROFILE -> "[ ERROR ] Select a VPN profile"
    VpnStep.NO_AUTO_SERVER -> "[ ERROR ] No server responded"
    VpnStep.CONNECT_FAILED -> "[ ERROR ] VPN connection failed"
    VpnStep.DISCONNECT_FAILED -> "[ ERROR ] VPN disconnection failed"
    VpnStep.COUNTERS_FAILED -> "[ ERROR ] Traffic counters could not be saved"
    VpnStep.CONNECT_CANCELED -> "[ WARN ] VPN connection canceled"
    VpnStep.INTERRUPTED -> "[ ERROR ] VPN service stopped unexpectedly"
}

private val VpnStep.color get() = when(this) {
    VpnStep.CONNECTED,VpnStep.DISCONNECTED -> shellMint
    VpnStep.VPN_PERMISSION,VpnStep.WAITING_NETWORK,VpnStep.ROUTE_UNCONFIRMED,VpnStep.CONNECT_CANCELED -> shellWarning
    else -> if(finishes) shellError else shellMuted
}

private fun terminalStatus(phase: Phase,routeUnconfirmed: Boolean,latest: VpnOperation?): Pair<String,Color> {
    val step=latest?.steps?.lastOrNull()?.step
    if(latest?.pending==true && latest.action==VpnAction.DISCONNECT) return "Отключение…" to shellWarning
    return when(phase) {
        Phase.OFF -> when {
            latest?.pending==true -> if(step==VpnStep.VPN_PERMISSION) "Ожидание разрешений Android" to shellWarning else "Запуск VPN…" to shellWarning
            step==VpnStep.PERMISSION_DENIED -> "Разрешение на VPN отклонено" to shellError
            step==VpnStep.CONNECT_FAILED -> "Не удалось запустить VPN" to shellError
            step==VpnStep.DISCONNECT_FAILED -> "Не удалось отключить VPN" to shellError
            step==VpnStep.INTERRUPTED -> "Служба VPN остановлена" to shellError
            else -> "VPN отключён" to shellMuted
        }
        Phase.STARTING -> (if (step == VpnStep.TESTING_SERVERS) "Проверка задержки…" else "Подключение…") to shellWarning
        Phase.TUNNEL -> if(routeUnconfirmed) "Маршрут через VPN не подтверждён" to shellWarning else "Проверка маршрута…" to shellWarning
        Phase.ONLINE -> "VPN подключён" to shellMint
        Phase.WAITING -> "Ожидание Wi-Fi или мобильной сети" to shellWarning
        Phase.ERROR -> when(step) {
            VpnStep.NO_PROFILE -> "Выберите профиль VPN" to shellError
            VpnStep.PERMISSION_DENIED -> "Разрешение на VPN отклонено" to shellError
            VpnStep.DISCONNECT_FAILED -> "Не удалось отключить VPN" to shellError
            else -> "Ошибка подключения VPN" to shellError
        }
        Phase.STOPPING -> "Отключение…" to shellWarning
    }
}
