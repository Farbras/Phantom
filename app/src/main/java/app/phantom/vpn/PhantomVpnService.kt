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
import hev.htproxy.TProxyService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import libXray.DialerController
import libXray.LibXray
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PhantomVpnService: VpnService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val commands=Channel<String>(Channel.UNLIMITED)
    private val app get()=application as PhantomApp
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var tun: ParcelFileDescriptor?=null
    private var adapterFd: ParcelFileDescriptor?=null
    private var statsJob: Job?=null
    private var healthJob: Job?=null
    private var networkJob: Job?=null
    @Volatile private var active=false
    private var cleanStop=false
    private var physical: Network?=null
    private var previousTx=0L;private var previousRx=0L
    private var accumulatedTx=0L;private var accumulatedRx=0L
    private var savedTx=0L;private var savedRx=0L
    private var startedAt=0L
    private val controller=object: DialerController {
        override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
    }
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
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            if(network==physical && active) {
                networkJob?.cancel()
                networkJob=scope.launch {delay(700);commands.send("network")}
            }
        }
        private fun changed() {
            networkJob?.cancel()
            networkJob=scope.launch {
                delay(700)
                val next=selectPhysical()
                if(next!=physical && active) commands.send("network")
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
                var operation: Long?=null
                try {
                    when(command) {
                        "stop" -> {
                            operation=VpnRuntime.serviceOperation(VpnAction.DISCONNECT) ?: continue
                            disconnect(operation)
                            break
                        }
                        "start" -> {
                            operation=VpnRuntime.serviceOperation(VpnAction.CONNECT) ?: continue
                            active=true
                            EngineLock.mutex.withLock { connect(operation) }
                        }
                        "network" -> if(active) {
                            operation=VpnRuntime.pendingConnectId()
                            val next=selectPhysical()
                            if(next==null) {
                                healthJob?.cancelAndJoin();physical=null
                                publish(Phase.WAITING,"Нет Wi-Fi или мобильной сети",operation,VpnStep.WAITING_NETWORK)
                            } else EngineLock.mutex.withLock { connect(operation) }
                        }
                    }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { fail(e,operation,if(command=="stop") VpnStep.DISCONNECT_FAILED else VpnStep.CONNECT_FAILED) }
                catch(e: LinkageError) {
                    fail(IllegalStateException("Нативное ядро не загрузилось. Проверьте архитектуру APK и версию Android."),
                        operation,if(command=="stop") VpnStep.DISCONNECT_FAILED else VpnStep.CONNECT_FAILED)
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
        val action=intent?.action
        if(action==ACTION_STOP) { active=false;commands.trySend("stop") }
        else {
            startNotification("Запуск туннеля")
            commands.trySend("start")
        }
        return START_STICKY
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
        val running=tun!=null && runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)
        VpnRuntime.updatePhase(operation,step) {
            it.copy(phase=phase,message=message,tunnel=running,online=phase==Phase.ONLINE,routeUnconfirmed=step==VpnStep.ROUTE_UNCONFIRMED)
        }
        getSystemService(NotificationManager::class.java).notify(1,notification(message))
        PhantomTile.refresh(this)
    }
    private suspend fun connect(operation: Long?) {
        if(prepare(this)!=null) {
            fail(IllegalStateException("Разрешение Android на VPN не предоставлено"),operation,VpnStep.PERMISSION_DENIED)
            return
        }
        publish(Phase.STARTING,"Подключение…",operation,VpnStep.LOADING_PROFILE)
        val c=app.vault.state.value
        val server=c.selectedServer()
        if(server==null) {
            fail(IllegalStateException("Сначала выберите поддерживаемый сервер"),operation,VpnStep.NO_PROFILE)
            return
        }
        VpnRuntime.mutable.update { it.copy(serverId=server.id,latency=null,online=false) }
        VpnRuntime.logLevel=c.setting("logLevel")
        physical=selectPhysical()
        if(physical==null) { publish(Phase.WAITING,"Ожидание Wi-Fi или мобильной сети",operation,VpnStep.WAITING_NETWORK);return }
        statsJob?.cancelAndJoin();healthJob?.cancelAndJoin()
        stopNative()
        // Keep the established TUN during a reconnect. It remains a sink until the new adapter starts.
        val config=ConfigBuilder.build(server,c,filesDir.absolutePath)
        LibXray.registerDialerController(controller)
        val bootstrap=cm.getLinkProperties(physical!!)?.dnsServers?.firstOrNull()?.hostAddress ?: "1.1.1.1"
        LibXray.setDNS(controller,if(':' in bootstrap) "[$bootstrap]:53" else "$bootstrap:53")
        VpnRuntime.step(operation,VpnStep.VALIDATING_CONFIG)
        Core.validate(config)
        VpnRuntime.step(operation,VpnStep.STARTING_XRAY)
        Core.start(config)
        VpnRuntime.step(operation,VpnStep.CREATING_TUN)
        val builder=Builder().setSession("Phantom").setMtu(1500).addAddress("10.66.0.1",30).addRoute("0.0.0.0",0)
            .addDnsServer("10.66.0.2").setBlocking(false).setMetered(false)
            .setConfigureIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE))
        // Route IPv6 into TUN in both modes. Without an IPv6 address Android blocks that family;
        // never call allowFamily(AF_INET6), which would let it bypass the VPN.
        if(c.flag("ipv6")) builder.addAddress("fd66:7068:616e::1",126).addRoute("::",0)
        val packages=c.setting("packages").lineSequence().map(String::trim).filter { it.isNotBlank() && it!=packageName }.toList()
        when(c.setting("appMode")) {
            "allow" -> { builder.addAllowedApplication(packageName);packages.forEach { builder.addAllowedApplication(it) } }
            "deny" -> packages.forEach { builder.addDisallowedApplication(it) }
        }
        setUnderlyingNetworks(arrayOf(physical!!))
        val next=builder.establish() ?: error("Android не создал TUN-интерфейс")
        tun?.close();tun=next
        VpnRuntime.step(operation,VpnStep.STARTING_ADAPTER)
        val adapter=File(noBackupFilesDir,"tun.yaml")
        adapter.writeText("""
            tunnel:
              mtu: 1500
              ipv4: 10.66.0.1
              ipv6: 'fd66:7068:616e::1'
              icmp: 'off'
            socks5:
              port: ${ConfigBuilder.TUN_PORT}
              address: 127.0.0.1
              udp: 'udp'
            misc:
              task-stack-size: 86016
              tcp-buffer-size: 65536
              connect-timeout: 10000
              log-level: error
              log-file: /dev/null
        """.trimIndent())
        adapterFd=ParcelFileDescriptor.dup(next.fileDescriptor)
        check(TProxyService.TProxyStartService(adapter.absolutePath,adapterFd!!.fd)) { "Адаптер TUN не запустился" }
        delay(250)
        check(TProxyService.TProxyIsRunning() && Core.running()) { "Сетевой адаптер или Xray завершился" }
        if(startedAt==0L) startedAt=SystemClock.elapsedRealtime()
        previousTx=0;previousRx=0
        VpnRuntime.mutable.update { it.copy(tunnel=true,connectedAt=startedAt,tx=0,rx=0,txSpeed=0,rxSpeed=0) }
        publish(Phase.TUNNEL,"Туннель запущен · проверка интернета",operation,VpnStep.CHECKING_ROUTE)
        VpnRuntime.log("TUN и Xray запущены")
        statsJob=scope.launch {
            var measuredAt=SystemClock.elapsedRealtime()
            var ticks=0
            while(isActive) {
                delay(1000)
                if(!TProxyService.TProxyIsRunning() || !Core.running()) {
                    publish(Phase.ERROR,"Ядро или адаптер остановился",operation,VpnStep.CONNECT_FAILED);commands.send("network");break
                }
                val values=TProxyService.TProxyGetStats()
                val tx=values.getOrElse(1){0};val rx=values.getOrElse(3){0}
                val dt=(tx-previousTx).coerceAtLeast(0);val dr=(rx-previousRx).coerceAtLeast(0)
                accumulatedTx+=dt;accumulatedRx+=dr
                val now=SystemClock.elapsedRealtime();val interval=(now-measuredAt).coerceAtLeast(1)
                VpnRuntime.mutable.update { it.copy(tx=accumulatedTx,rx=accumulatedRx,txSpeed=dt*1000/interval,rxSpeed=dr*1000/interval) }
                measuredAt=now
                previousTx=tx;previousRx=rx
                if(++ticks%10==0) saveTraffic()
            }
        }
        healthJob=scope.launch {
            var failures=0
            while(isActive) {
                val result=runCatching { checkTunnel(c) }
                ensureActive()
                if(result.isSuccess) {
                    failures=0
                    VpnRuntime.mutable.update { it.copy(latency=result.getOrThrow()) }
                    publish(Phase.ONLINE,"Интернет через VPN подтверждён",operation)
                } else {
                    failures++
                    publish(Phase.TUNNEL,"Туннель работает · интернет не подтверждён",operation,VpnStep.ROUTE_UNCONFIRMED)
                    if(failures>=3) { commands.send("network");break }
                }
                delay(if(failures==0) 30000 else 10000)
            }
        }
    }
    private suspend fun checkTunnel(c: Catalog): Long {
        var network: Network?=null
        repeat(20) {
            if(network==null) { network=cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true };if(network==null) delay(100) }
        }
        val vpn=network ?: error("VPN-сеть ещё не появилась")
        val url=c.setting("testUrl")
        require(url.startsWith("https://")) { "URL проверки должен использовать HTTPS" }
        val client=OkHttpClient.Builder().socketFactory(vpn.socketFactory)
            .dns(object: Dns { override fun lookup(hostname: String)=vpn.getAllByName(hostname).toList() }).callTimeout(c.number("pingTimeout",7).toLong(),TimeUnit.SECONDS)
            .followRedirects(false).build()
        val before=TProxyService.TProxyGetStats()
        val start=SystemClock.elapsedRealtime()
        try {
            client.newCall(Request.Builder().url(url).header("Cache-Control","no-cache").build()).execute().use {
                check(it.code in 200..399) { "Проверочный URL вернул ошибку" }
                it.body?.source()?.readByteArray(0)
            }
            val after=TProxyService.TProxyGetStats()
            check(after[1]>before[1] && after[3]>before[3]) { "HTTP-ответ не подтверждён счётчиками TUN" }
            return SystemClock.elapsedRealtime()-start
        } finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
    private fun stopNative(operation: Long?=null): Boolean {
        VpnRuntime.mutable.update { it.copy(tunnel=false,online=false) }
        VpnRuntime.step(operation,VpnStep.STOPPING_ADAPTER)
        val adapterStopped=runCatching { TProxyService.TProxyStopService() }.getOrDefault(false)
        // The adapter borrows the supplied fd (it does not close externally supplied descriptors).
        val fdClosed=runCatching { adapterFd?.close() }.isSuccess;adapterFd=null
        VpnRuntime.step(operation,VpnStep.STOPPING_XRAY)
        val coreStopped=runCatching { Core.stop() }.isSuccess
        runCatching { LibXray.resetDNS() }
        return adapterStopped && fdClosed && coreStopped
    }
    private suspend fun disconnect(operation: Long) {
        active=false
        statsJob?.cancelAndJoin();healthJob?.cancelAndJoin()
        publish(Phase.STOPPING,"Отключение…",operation)
        val nativeStopped=stopNative(operation)
        tun?.let { descriptor ->
            VpnRuntime.step(operation,VpnStep.CLOSING_TUN)
            descriptor.close();tun=null
            // Android removes the VPN routes when the TUN descriptor closes.
            VpnRuntime.step(operation,VpnStep.RESTORING_ROUTE)
        }
        VpnRuntime.step(operation,VpnStep.SAVING_COUNTERS)
        val saved=try { saveTraffic();true }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { VpnRuntime.error(e);false }
        check(nativeStopped) { "Не удалось остановить адаптер или Xray" }
        accumulatedTx=0;accumulatedRx=0;savedTx=0;savedRx=0;startedAt=0
        withContext(Dispatchers.Main.immediate) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            cleanStop=true
            stopSelf()
            VpnRuntime.updatePhase(operation,if(saved) null else VpnStep.COUNTERS_FAILED) { VpnSnapshot() }
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
        statsJob?.cancelAndJoin();healthJob?.cancelAndJoin();stopNative()
        val message=safeError(e)
        VpnRuntime.updatePhase(operation,step) { it.copy(phase=Phase.ERROR,message=message,tunnel=false,online=false,txSpeed=0,rxSpeed=0) }
        // Keep an existing TUN as a sink after an error; user can disconnect explicitly.
        getSystemService(NotificationManager::class.java).notify(1,notification(message))
        PhantomTile.refresh(this)
    }
    override fun onRevoke() { commands.trySend("stop");super.onRevoke() }
    override fun onDestroy() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        scope.cancel()
        if(!cleanStop) {
            stopNative();runCatching { tun?.close() };tun=null
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
