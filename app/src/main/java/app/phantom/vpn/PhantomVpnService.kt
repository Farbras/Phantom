package app.phantom.vpn

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import app.phantom.MainActivity
import app.phantom.PhantomApp
import app.phantom.R
import app.phantom.core.ConfigBuilder
import app.phantom.core.Core
import app.phantom.core.EngineLock
import app.phantom.data.*
import app.phantom.core.XrayLoopback
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.withLock
import libXray.DialerController
import libXray.LibXray

class PhantomVpnService: VpnService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private enum class Action { START, STOP, NETWORK, FAILOVER }
    private data class Command(val action: Action, val generation: Long)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val generation = MutableStateFlow(0L)
    private val nativeLock = Any()
    @Volatile private var destroyed = false
    @Volatile private var coreRunning = false
    private val switchBackoff = listOf(0L, 2_000L, 5_000L, 10_000L, 15_000L)
    private var switchWindow = 0L
    private var switches = 0
    private val failedServers = mutableSetOf<String>()
    private val app get()=application as PhantomApp
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var tun: ParcelFileDescriptor?=null
    private var statsJob: Job?=null
    private var healthJob: Job?=null
    private var networkJob: Job?=null
    @Volatile private var active=false
    private var cleanStop=false
    @Volatile private var physical: Network?=null
    private var previousTx=0L;private var previousRx=0L
    private var accumulatedTx=0L;private var accumulatedRx=0L
    private var savedTx=0L;private var savedRx=0L
    private var startedAt=0L
    private val protector=Messenger(Handler(Looper.getMainLooper()) { message->
        if(message.what!=PROTECT_SOCKET) return@Handler false
        val fd=message.data.getParcelable<ParcelFileDescriptor>("fd")
        val ok=try { fd!=null && protect(fd.fd) } catch(e: Exception) { false } finally { runCatching { fd?.close() } }
        try { message.replyTo?.send(Message.obtain().apply { what=if(ok) 1 else 0 }) } catch (_: RemoteException) { }
        true
    })
    override fun onBind(intent: Intent?): IBinder? = if(intent?.action==ACTION_PROTECT) protector.binder else super.onBind(intent)
    private val callback=object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        private fun changed() {
            networkJob?.cancel()
            val request = generation.value
            networkJob=scope.launch {
                delay(1_500)
                if (active && request == generation.value && selectPhysical() != physical) {
                    commands.send(Command(Action.NETWORK, request))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn","VPN Phantom",NotificationManager.IMPORTANCE_LOW))
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback)
        scope.launch {
            try { app.ready.await() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { fail(e,VpnRuntime.pendingConnectId());return@launch }
            for(command in commands) {
                if (command.generation != generation.value) continue
                var operation: Long?=null
                try {
                    when(command.action) {
                        Action.STOP -> {
                            operation=VpnRuntime.serviceOperation(VpnAction.DISCONNECT) ?: continue
                            disconnect(operation, command.generation)
                        }
                        Action.START -> {
                            operation=VpnRuntime.serviceOperation(VpnAction.CONNECT) ?: continue
                            failedServers.clear()
                            switches = 0
                            switchWindow = 0
                            EngineLock.mutex.withLock { connect(operation, command.generation) }
                        }
                        Action.NETWORK -> if (active && selectPhysical() != physical) {
                            operation=VpnRuntime.pendingConnectId()
                            EngineLock.mutex.withLock { connect(operation, command.generation) }
                        }
                        Action.FAILOVER -> if (active) {
                            operation = VpnRuntime.pendingConnectId()
                            EngineLock.mutex.withLock { switchServer(operation, command.generation) }
                        }
                    }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    if (command.generation == generation.value) fail(e,operation,
                        if(command.action == Action.STOP) VpnStep.DISCONNECT_FAILED else VpnStep.CONNECT_FAILED)
                }
                catch(e: LinkageError) {
                    fail(IllegalStateException("Нативное ядро не загрузилось. Проверьте архитектуру APK и версию Android."),
                        operation,if(command.action == Action.STOP) VpnStep.DISCONNECT_FAILED else VpnStep.CONNECT_FAILED)
                }
            }
        }
    }
    private fun selectPhysical(): Network? = cm.allNetworks.filter { n ->
        cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }==true
    }.sortedByDescending { n->
        val cap=cm.getNetworkCapabilities(n)
        (if(cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true) 10 else 0)+(if(cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) 1 else 0)
    }.firstOrNull()

    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val action = if (intent?.action == ACTION_STOP) Action.STOP else Action.START
        active = action == Action.START
        if (active) {
            cleanStop = false
            startNotification("Запуск туннеля")
        }
        commands.trySend(Command(action, generation.updateAndGet { it + 1 }))
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val stop=PendingIntent.getService(this,1,Intent(this,PhantomVpnService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"vpn").setSmallIcon(R.drawable.ic_vpn).setContentTitle("Phantom")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_vpn,"Отключить",stop).build()
    }
    private fun startNotification(text: String) {
        if(Build.VERSION.SDK_INT>=34) startForeground(1,notification(text),ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(1,notification(text))
    }
    private fun publish(phase: Phase,message: String,operation: Long?,step: VpnStep?=null) {
        val running = coreRunning
        VpnRuntime.updatePhase(operation,step) {
            it.copy(phase=phase,message=message,tunnel=running,online=phase==Phase.ONLINE,routeUnconfirmed=step==VpnStep.ROUTE_UNCONFIRMED)
        }
        getSystemService(NotificationManager::class.java).notify(1,notification(message))
        PhantomTile.refresh(this)
    }
    private suspend fun connect(operation: Long?, request: Long) {
        if (!active || request != generation.value) return
        if (prepare(this) != null) {
            fail(IllegalStateException("Разрешение Android на VPN не предоставлено"), operation, VpnStep.PERMISSION_DENIED)
            return
        }
        publish(Phase.STARTING, "Подключение…", operation, VpnStep.LOADING_PROFILE)
        var catalog = app.vault.state.value
        if (!catalog.autoSelect && catalog.selectedServer() == null) {
            fail(IllegalStateException("Сначала выберите поддерживаемый сервер"), operation, VpnStep.NO_PROFILE)
            return
        }
        if (catalog.autoSelect && catalog.servers().none { it.issue.isBlank() && it.id !in failedServers }) {
            finishSession(operation, request, noAutoServer = true)
            return
        }
        stopPolling()
        val network = selectPhysical()
        physical = network
        if (network == null) {
            setUnderlyingNetworks(null)
            publish(Phase.WAITING, "Ожидание Wi-Fi или мобильной сети", operation, VpnStep.WAITING_NETWORK)
            return
        }

        if (catalog.autoSelect && catalog.fastestServer(failedServers) == null) {
            if (coreRunning && catalog.setting("pingMethod") == "ICMP") {
                check(stopNative()) { "Не удалось остановить Xray" }
            }
            publish(Phase.STARTING, "Проверка серверов для режима «Авто»", operation, VpnStep.TESTING_SERVERS)
            coroutineScope {
                val probing = launch { app.prober.testForAuto(failedServers) }
                val cancelOnNewCommand = launch {
                    generation.first { it != request }
                    probing.cancel()
                }
                try { probing.join() } finally { cancelOnNewCommand.cancel() }
            }
            if (!active || request != generation.value) return
            catalog = app.vault.state.value
        }
        val server = if (catalog.autoSelect) catalog.fastestServer(failedServers) else catalog.selectedServer()
        if (server == null) {
            if (catalog.autoSelect) finishSession(operation, request, noAutoServer = true)
            else fail(IllegalStateException("Сначала выберите поддерживаемый сервер"), operation, VpnStep.NO_PROFILE)
            return
        }
        VpnRuntime.logLevel = catalog.setting("logLevel")

        val control = XrayLoopback()
        VpnRuntime.step(operation, VpnStep.VALIDATING_CONFIG)
        val started = SystemClock.elapsedRealtime()
        val config = synchronized(nativeLock) {
            if (destroyed || !active || request != generation.value) return
            check(stopNative()) { "Не удалось остановить Xray" }
            VpnRuntime.step(operation, VpnStep.CREATING_TUN)
            val builder = Builder().setSession("Phantom").setMtu(ConfigBuilder.TUN_MTU)
                .addAddress(ConfigBuilder.TUN_ADDRESS, 24).addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8").setBlocking(false).setMetered(false)
                .setUnderlyingNetworks(arrayOf(network))
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            if (catalog.flag("ipv6")) builder.addAddress("fd66:7068:616e::1", 126).addRoute("::", 0)
            applyAppFilter(builder, catalog)
            val descriptor = builder.establish() ?: error("Android не создал TUN-интерфейс")
            tun = descriptor
            val config = ConfigBuilder.buildTunnel(server, catalog, filesDir.absolutePath, descriptor.fd, control)
            VpnRuntime.step(operation, VpnStep.STARTING_XRAY)
            SocketProtection.attach(this)
            LibXray.setDNS(SocketProtection, "1.1.1.1:53")
            Core.start(config.json)
            coreRunning = Core.running()
            check(coreRunning && SystemClock.elapsedRealtime() - started < 20_000) { "Не удалось подключиться к выбранному серверу" }
            config
        }
        if (!active || request != generation.value) return
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        previousTx = 0; previousRx = 0
        val protocol = config.protocolsByTag.values.toSet().singleOrNull().orEmpty()
        VpnRuntime.mutable.update {
            it.copy(serverId = server.id, serverName = server.name, serverProtocol = protocol,
                connectedAt = startedAt, latency = null, txSpeed = 0, rxSpeed = 0)
        }
        publish(Phase.TUNNEL, "Туннель запущен · проверка интернета", operation, VpnStep.CHECKING_ROUTE)
        VpnRuntime.log("TUN и Xray запущены")
        startPolling(control, catalog, server, config.protocolsByTag, operation, request)
    }

    private fun applyAppFilter(builder: Builder, catalog: Catalog) {
        val packages = catalog.setting("packages").lineSequence().map(String::trim)
            .filter { it.isNotBlank() && it != packageName }.distinct().toList()
        if (catalog.setting("appMode") == "allow" && packages.isNotEmpty()) {
            val allowed = packages.count { name ->
                try { builder.addAllowedApplication(name); true }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
            }
            if (allowed == 0) builder.addAllowedApplication(packageName)
        } else {
            if (catalog.setting("appMode") == "deny") packages.forEach { name ->
                try { builder.addDisallowedApplication(name) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
            }
            builder.addDisallowedApplication(packageName)
        }
    }

    private fun startPolling(control: XrayLoopback, catalog: Catalog, server: Server,
        protocolsByTag: Map<String, String>, operation: Long?, request: Long) {
        statsJob = scope.launch {
            var measuredAt = SystemClock.elapsedRealtime()
            var ticks = 0
            while (active && request == generation.value) {
                delay(2_000)
                try {
                    val (tx, rx, usedOutbounds) = control.counters()
                    val protocol = protocolsByTag.filterKeys { it in usedOutbounds }.values.distinct().joinToString(" / ")
                    val deltaTx = (tx - previousTx).coerceAtLeast(0)
                    val deltaRx = (rx - previousRx).coerceAtLeast(0)
                    accumulatedTx += deltaTx
                    accumulatedRx += deltaRx
                    val now = SystemClock.elapsedRealtime()
                    val interval = (now - measuredAt).coerceAtLeast(1)
                    VpnRuntime.mutable.update { it.copy(tx = accumulatedTx, rx = accumulatedRx,
                        txSpeed = deltaTx * 1000 / interval, rxSpeed = deltaRx * 1000 / interval,
                        serverProtocol = protocol.ifBlank { it.serverProtocol }) }
                    measuredAt = now
                    previousTx = tx; previousRx = rx
                    if (++ticks % 5 == 0) saveTraffic()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    VpnRuntime.mutable.update { it.copy(txSpeed = 0, rxSpeed = 0) }
                }
            }
        }
        healthJob = scope.launch {
            var failures = 0
            var interval = 60_000L
            var first = true
            while (active && request == generation.value) {
                val latency = testRoute(control, if (first) catalog.setting("testUrl") else "https://1.1.1.1")
                if (!active || request != generation.value) break
                if (latency != null) {
                    failures = 0
                    interval = 60_000
                    VpnRuntime.mutable.update { it.copy(latency = latency) }
                    publish(Phase.ONLINE, "Интернет через VPN подтверждён", operation)
                    if (first || server.id !in app.vault.state.value.lastWorking) rememberWorking(server, true)
                } else {
                    failures++
                    interval = (interval * 2).coerceAtMost(300_000)
                    VpnRuntime.mutable.update { it.copy(latency = null) }
                    publish(Phase.TUNNEL, "Туннель работает · интернет не подтверждён", operation, VpnStep.ROUTE_UNCONFIRMED)
                    if (first || failures >= 2) {
                        commands.send(Command(Action.FAILOVER, request))
                        break
                    }
                }
                delay(if (first) 90_000 else interval)
                first = false
            }
        }
    }

    private suspend fun testRoute(control: XrayLoopback, url: String): Long? {
        repeat(2) { attempt ->
            val elapsed = try {
                withTimeoutOrNull(5_000) { control.check(url, 5_000) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (elapsed != null) return elapsed
            if (attempt == 0) delay(2_000)
        }
        return null
    }

    private suspend fun rememberWorking(server: Server, works: Boolean) {
        val known = app.vault.state.value.lastWorking
        if (works && known.firstOrNull() == server.id || !works && server.id !in known) return
        try {
            app.vault.update {
                val retained = it.lastWorking - server.id
                it.copy(lastWorking = if (works) (listOf(server.id) + retained).take(30) else retained)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VpnRuntime.error(e)
        }
    }

    private suspend fun switchServer(operation: Long?, request: Long) {
        if (selectPhysical() == null) {
            connect(operation, request)
            return
        }
        val catalog = app.vault.state.value
        val currentId = VpnRuntime.state.value.serverId
        if (!catalog.autoSelect && catalog.selected != currentId) return
        val current = catalog.server(currentId) ?: return
        val now = SystemClock.elapsedRealtime()
        if (switchWindow == 0L || now - switchWindow > 600_000) {
            switchWindow = now
            switches = 0
            failedServers.clear()
        }
        failedServers.add(current.id)
        rememberWorking(current, false)
        if (catalog.autoSelect) {
            if (waitToSwitch(operation, request) && app.vault.state.value.autoSelect) connect(operation, request)
            return
        }
        val subscription = catalog.subscriptions.firstOrNull { sub -> sub.servers.any { it.id == current.id } }
        val choices = catalog.subscriptions.flatMap { it.servers }.filter { it.issue.isBlank() && it.id !in failedServers }
        val providerChoices = choices.filter { it in subscription?.servers.orEmpty() }
        val automatic = providerChoices.filter { it.isAutomaticProfile() }
        val next = bestCandidate(automatic.filter { it.locationGroup() == current.locationGroup() }, catalog)
            ?: bestCandidate(automatic, catalog)
            ?: bestCandidate(providerChoices.filter { it.locationGroup() == current.locationGroup() }, catalog)
            ?: bestCandidate(providerChoices, catalog)
            ?: bestCandidate(choices, catalog)
        if (next == null) {
            VpnRuntime.step(operation, VpnStep.CONNECT_FAILED)
            return
        }
        if (!waitToSwitch(operation, request)) return
        app.vault.update { latest ->
            if (!latest.autoSelect && latest.selected == current.id && latest.server(next.id) != null)
                latest.copy(selected = next.id)
            else latest
        }
        val latest = app.vault.state.value
        if (!latest.autoSelect && latest.selected == next.id) connect(operation, request)
    }

    private suspend fun waitToSwitch(operation: Long?, request: Long): Boolean {
        if (switches >= switchBackoff.size) {
            VpnRuntime.step(operation, VpnStep.CONNECT_FAILED)
            return false
        }
        val backoff = switchBackoff[switches++]
        if (withTimeoutOrNull(backoff) { generation.first { it != request } } != null) return false
        return active && request == generation.value
    }

    private fun bestCandidate(choices: List<Server>, catalog: Catalog): Server? =
        catalog.lastWorking.firstNotNullOfOrNull { id -> choices.firstOrNull { it.id == id } }
            ?: choices.filter { (it.latency ?: -1) >= 0 }.minByOrNull { it.latency ?: Long.MAX_VALUE }
            ?: choices.firstOrNull()

    private fun Server.locationGroup(): String {
        val flags = name.codePoints().limit(2).toArray()
        return if (flags.size == 2 && flags.all { it in 0x1F1E6..0x1F1FF }) name.substring(0, 4)
        else name.substringBefore('|').trim()
    }

    private fun Server.isAutomaticProfile(): Boolean {
        val group = locationGroup()
        val label = if (group.length == 4 && group[0].isHighSurrogate()) name.substring(4) else name
        return label.substringBefore('|').trim().uppercase() in setOf("AUTO", "АВТО")
    }

    private suspend fun stopPolling() {
        statsJob?.cancel()
        healthJob?.cancel()
        statsJob?.join()
        healthJob?.join()
        statsJob = null
        healthJob = null
    }

    private fun stopNative(operation: Long? = null): Boolean = synchronized(nativeLock) {
        coreRunning = false
        VpnRuntime.mutable.update { it.copy(tunnel = false, online = false) }
        VpnRuntime.step(operation, VpnStep.STOPPING_XRAY)
        val coreStopped = runCatching { Core.stop() }.isSuccess
        runCatching { LibXray.resetDNS() }
        SocketProtection.detach(this)
        val descriptor = tun
        tun = null
        val tunClosed = if (descriptor == null) true else {
            VpnRuntime.step(operation, VpnStep.CLOSING_TUN)
            runCatching { descriptor.close() }.isSuccess.also {
                if (it) VpnRuntime.step(operation, VpnStep.RESTORING_ROUTE)
            }
        }
        coreStopped && tunClosed
    }


    private suspend fun disconnect(operation: Long, request: Long) {
        publish(Phase.STOPPING,"Отключение…",operation)
        finishSession(operation, request)
    }

    private suspend fun finishSession(operation: Long?, request: Long, noAutoServer: Boolean = false) {
        stopPolling()
        val stopOperation = operation.takeUnless { noAutoServer }
        val nativeStopped=stopNative(stopOperation)
        VpnRuntime.step(stopOperation,VpnStep.SAVING_COUNTERS)
        val saved=try { saveTraffic();true }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { VpnRuntime.error(e);false }
        check(nativeStopped) { "Не удалось остановить адаптер или Xray" }
        accumulatedTx=0;accumulatedRx=0;savedTx=0;savedRx=0;startedAt=0
        withContext(Dispatchers.Main.immediate) {
            if (request == generation.value) {
                active=false
                stopForeground(STOP_FOREGROUND_REMOVE)
                cleanStop=true
                stopSelf()
            }
            if (!noAutoServer || request == generation.value) {
                val step = when {
                    noAutoServer -> VpnStep.NO_AUTO_SERVER
                    !saved -> VpnStep.COUNTERS_FAILED
                    else -> null
                }
                VpnRuntime.updatePhase(operation, step) {
                    if (noAutoServer) VpnSnapshot(message = NO_AUTO_SERVER_MESSAGE) else VpnSnapshot()
                }
            }
            PhantomTile.refresh(this@PhantomVpnService)
        }
    }
    private suspend fun saveTraffic() {
        val tx=accumulatedTx;val rx=accumulatedRx
        if(tx!=savedTx || rx!=savedRx) {
            app.vault.update {it.copy(totalTx=it.totalTx+(tx-savedTx).coerceAtLeast(0),totalRx=it.totalRx+(rx-savedRx).coerceAtLeast(0))}
            savedTx=tx;savedRx=rx
        }
    }
    private suspend fun fail(e: Exception,operation: Long?,step: VpnStep=VpnStep.CONNECT_FAILED) {
        VpnRuntime.error(e)
        stopPolling();stopNative()
        val message=safeError(e)
        VpnRuntime.updatePhase(operation,step) { it.copy(phase=Phase.ERROR,message=message,tunnel=false,online=false,txSpeed=0,rxSpeed=0) }
        getSystemService(NotificationManager::class.java).notify(1,notification(message))
        PhantomTile.refresh(this)
    }
    override fun onRevoke() {
        active = false
        commands.trySend(Command(Action.STOP, generation.updateAndGet { it + 1 }))
    }
    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        destroyed = true
        active = false
        scope.cancel()
        if(!cleanStop) {
            stopNative()
            val operation=VpnRuntime.operations.value.lastOrNull()?.takeIf { it.pending }
            VpnRuntime.updatePhase(operation?.id,VpnStep.INTERRUPTED) { VpnSnapshot() }
        }
        super.onDestroy()
    }
    companion object {
        const val ACTION_STOP="app.phantom.STOP"
        const val ACTION_PROTECT="app.phantom.PROTECT"
        internal const val PROTECT_SOCKET=77
        fun connect(context: android.content.Context) { context.startForegroundService(Intent(context,PhantomVpnService::class.java)) }
        fun disconnect(context: android.content.Context) { context.startService(Intent(context,PhantomVpnService::class.java).setAction(ACTION_STOP)) }
    }
}

private object SocketProtection : DialerController {
    @Volatile private var service: VpnService? = null
    private var registered = false

    @Synchronized fun attach(vpn: VpnService) {
        // Core retains the controller for the process lifetime.
        if (!registered) {
            LibXray.registerDialerController(this)
            LibXray.registerListenerController(this)
            registered = true
        }
        service = vpn
    }

    @Synchronized fun detach(vpn: VpnService) {
        if (service === vpn) service = null
    }

    override fun protectFd(fd: Long): Boolean = service?.protect(fd.toInt()) ?: false
}
