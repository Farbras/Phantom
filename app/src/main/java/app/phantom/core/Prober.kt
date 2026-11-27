package app.phantom.core

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.phantom.data.Catalog
import app.phantom.data.Server
import app.phantom.data.Vault
import app.phantom.data.objects
import app.phantom.data.readBounded
import app.phantom.data.safeError
import app.phantom.vpn.Phase
import app.phantom.vpn.VpnRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

object EngineLock { val mutex = Mutex() }

internal const val PROBE_TIMEOUT_LABEL = "Тайм-аут"
internal class ProbeTimeout : InterruptedIOException(PROBE_TIMEOUT_LABEL)

internal fun Exception.isProbeTimeout() = this is ProbeTimeout || this is SocketTimeoutException ||
    (this is InterruptedIOException && message == "timeout")

internal fun ConnectivityManager.probeNetwork(): Network? {
    val active = activeNetwork?.takeIf {
        getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
    }
    return active ?: allNetworks.mapNotNull { network ->
        val caps = getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) return@mapNotNull null
        val priority = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
            (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
        network to priority
    }.maxByOrNull { it.second }?.first
}

class Prober(private val vault: Vault, private val context: Context) {
    internal data class Progress(
        val pending: Set<String>,
        val completed: Int,
        val total: Int,
        val initialLatencies: Map<String, Long>,
    )

    private val batch = Mutex()
    private val activeBatch = MutableStateFlow<Progress?>(null)
    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val probeWorkers by lazy {
        if (context.getSystemService(ActivityManager::class.java).isLowRamDevice) 1 else RemoteProbeClient.workerCount
    }
    internal val progress = activeBatch.asStateFlow()

    suspend fun test(servers: List<Server>) {
        batch.withLock { measure(servers, automatic = false) }
    }

    internal suspend fun testForAuto(excluded: Set<String>) = batch.withLock {
        val catalog = vault.state.value
        if (catalog.autoSelect && catalog.fastestServer(excluded) == null) {
            measure(catalog.servers().filter { it.id !in excluded }.toList(), automatic = false)
        }
    }

    internal suspend fun checkUnmeasuredProfiles() {
        val unmeasured = vault.state.map(::unmeasuredProfiles).distinctUntilChanged()
        unmeasured.combine(networkAvailable()) { awaiting, online -> awaiting.isNotEmpty() && online }.filter { it }.collect {
            try {
                while (checkPendingBatch()) {
                    // Re-read the catalog after each batch because an import can finish during a scan.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VpnRuntime.error(e)
            }
        }
    }

    private fun networkAvailable() = callbackFlow {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(connectivity.probeNetwork() != null) }
            override fun onLost(network: Network) { trySend(connectivity.probeNetwork() != null) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(connectivity.probeNetwork() != null)
            }
        }
        connectivity.registerNetworkCallback(request, callback)
        trySend(connectivity.probeNetwork() != null)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun unmeasuredProfiles(catalog: Catalog) = catalog.servers()
        .filter { it.issue.isBlank() && it.testedAt == 0L }
        .toList()

    private suspend fun checkPendingBatch(): Boolean = batch.withLock {
        if (connectivity.probeNetwork() == null) return@withLock false
        val catalog = vault.state.value
        val targets = unmeasuredProfiles(catalog).sortedBy { it.id != catalog.selected }
        if (targets.isEmpty()) return@withLock false
        measure(targets, automatic = true)
    }

    private data class Measurement(val latency: Long, val error: String = "")

    private suspend fun measure(servers: List<Server>, automatic: Boolean): Boolean {
        val targets = servers.filter { it.issue.isBlank() }.distinctBy { it.id to it.config }
        if (targets.isEmpty()) return false
        val catalog = vault.state.value
        val timeout = catalog.number("pingTimeout", 7).coerceIn(2, 60)
        val initialLatencies = catalog.servers().distinctBy { it.id }
            .mapNotNull { server -> server.successfulLatency?.let { server.id to it } }.toMap()
        val directSlots = Semaphore(4)
        val proxySlots = Channel<Int>(probeWorkers)
        repeat(probeWorkers) { proxySlots.trySend(it) }
        val lastTargetIndex = targets.withIndex().associate { (index, server) -> server.id to index }
        var progress = Progress(targets.mapTo(mutableSetOf()) { it.id }, 0, targets.size, initialLatencies)
        var recorded = false
        try {
            activeBatch.value = progress
            for (group in targets.chunked(4)) {
                if (automatic && connectivity.probeNetwork() == null) break
                val results = coroutineScope {
                    group.map { server -> async(Dispatchers.IO) {
                        (server.id to server.config) to measureServer(server, catalog, timeout, directSlots, proxySlots)
                    } }.awaitAll().toMap()
                }
                val online = !automatic || connectivity.probeNetwork() != null
                val completed = if (online) results else results.filterValues { it.latency >= 0 }
                if (completed.isNotEmpty()) {
                    val testedAt = System.currentTimeMillis()
                    vault.update { current -> current.copy(subscriptions = current.subscriptions.map { sub ->
                        sub.copy(servers = sub.servers.map { server ->
                            val result = completed[server.id to server.config]
                            if (result == null) server else server.copy(latency = result.latency, testedAt = testedAt,
                                latencyError = result.error)
                        })
                    }) }
                    recorded = true
                }
                val count = progress.completed + group.size
                val finishedIds = group.map { it.id }.filter { lastTargetIndex.getValue(it) < count }
                progress = progress.copy(completed = count, pending = progress.pending - finishedIds.toSet())
                activeBatch.value = progress
            }
        } finally {
            proxySlots.close()
            activeBatch.value = null
        }
        return recorded
    }

    private suspend fun measureServer(
        server: Server,
        catalog: Catalog,
        timeout: Int,
        directSlots: Semaphore,
        proxySlots: Channel<Int>,
    ): Measurement = try {
        val latency = when (catalog.setting("pingMethod")) {
            "ICMP" -> directSlots.withPermit { icmpTest(requireEndpoint(ProbeEndpoint.from(server.config)), timeout) }
            "TCP" -> directSlots.withPermit {
                val endpoint = requireEndpoint(ProbeEndpoint.from(server.config))
                require(endpoint.tcp) { "Транспорт сервера не использует TCP" }
                tcpTest(endpoint, timeout)
            }
            else -> {
                val worker = proxySlots.receive()
                try {
                    val config = ConfigBuilder.build(server, catalog, context.filesDir.absolutePath, probe = true)
                    RemoteProbeClient.probe(context, config, catalog, VpnRuntime.state.value.phase != Phase.OFF, worker)
                } finally {
                    proxySlots.trySend(worker)
                }
            }
        }
        Measurement(latency)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Measurement(-1, if (e.isProbeTimeout()) PROBE_TIMEOUT_LABEL else safeError(e))
    }

    private fun requireEndpoint(endpoint: ProbeEndpoint?) = requireNotNull(endpoint) {
        "Нет единственного адреса и порта для прямой проверки. Используйте HTTP через прокси."
    }

    private fun tcpTest(endpoint: ProbeEndpoint, timeout: Int): Long {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.probeNetwork() ?: error("Нет доступной сети для прямой проверки")
        val address = InetSocketAddress(network.getByName(endpoint.host), endpoint.port)
        return network.socketFactory.createSocket().use { socket ->
            val start = System.nanoTime()
            socket.connect(address, timeout * 1000)
            elapsedMillis(start)
        }
    }

    private fun icmpTest(endpoint: ProbeEndpoint, timeout: Int): Long {
        require(!VpnRuntime.state.value.tunnel) { "ICMP-тест требует отключённого VPN" }
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeout.toString(), "--", endpoint.host)
            .redirectErrorStream(true).start()
        try {
            if (!process.waitFor(timeout + 1L, TimeUnit.SECONDS)) throw ProbeTimeout()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val delay = Regex("\\btime[=<]([0-9.]+)\\s*ms").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
            if (delay != null) return delay.roundToLong()
            if (process.exitValue() == 1) throw ProbeTimeout()
            error("ICMP недоступен или запрещён устройством")
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        private fun elapsedMillis(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        internal suspend fun proxyTestJson(json: String, catalog: Catalog, timeout: Int, port: Int): Long {
            val url = catalog.setting("testUrl")
            require(url.startsWith("https://")) { "Проверка требует HTTPS URL" }
            val observedBalancer = JSONObject(json).optJSONObject("routing")?.optJSONArray("balancers")
                ?.objects()?.any { balancer ->
                    when (balancer.optJSONObject("strategy")?.optString("type")?.lowercase()) {
                        "leastload", "leastping" -> true
                        else -> false
                    }
                } == true
            val mode = catalog.setting("pingMode")
            val method = if (catalog.setting("pingMethod") == "GET") "GET" else "HEAD"
            val request = Request.Builder().url(url).method(method, null).header("Cache-Control", "no-cache").apply {
                if (mode == "double") header("Connection", "close")
            }.build()
            val client = OkHttpClient.Builder()
                .apply {
                    // The reference probe's Go transport uses HTTP/1.1 with its custom dialer.
                    if (mode != "double" && mode != "keepalive") protocols(listOf(Protocol.HTTP_1_1))
                }
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .callTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeout.toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    // Reject error statuses here so OkHttp cannot follow up on an HTTP 503.
                    if (response.code !in 200..399) {
                        response.close()
                        error("HTTP-проверка не прошла (${response.code})")
                    }
                    response
                }
                .connectionPool(ConnectionPool(if (mode == "double") 0 else 1, 30, TimeUnit.SECONDS))
                .build()

            Core.validate(json)
            Core.start(json)
            try {
                return measureProxyLatency(client, request, timeout, mode, observedBalancer)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                Core.stop()
            }
        }

        internal suspend fun measureProxyLatency(
            client: OkHttpClient,
            request: Request,
            timeout: Int,
            mode: String,
            observedBalancer: Boolean,
        ): Long {
            val warmup = mode !in listOf("double", "keepalive")
            val tracker = if (warmup) ProbeConnectionTracker() else null
            val measuringClient = if (tracker != null) client.newBuilder().eventListener(tracker).build() else client
            var warmedConnection: Connection? = null
            val timeoutNanos = TimeUnit.SECONDS.toNanos(timeout.toLong())
            val warmupDeadline = System.nanoTime() + timeoutNanos
            var best: Long? = null
            var failure: Exception? = null
            // Allow one extra request if the peer closed the warmed connection.
            repeat(if (warmup) 3 else 2) { index ->
                val deadline = if (warmup) warmupDeadline else System.nanoTime() + timeoutNanos
                val waitForBalancer = index == 0 && observedBalancer
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) throw ProbeTimeout()
                    tracker?.connection = null
                    val call = measuringClient.newCall(request)
                    call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
                    val start = System.nanoTime()
                    try {
                        call.execute().use { response ->
                            val latency = elapsedMillis(start)
                            if (tracker != null) {
                                val connection = tracker.connection
                                if (connection != null && connection === warmedConnection) return latency
                                warmedConnection = connection
                            } else {
                                best = minOf(best ?: latency, latency)
                            }
                            if (warmup || (mode == "keepalive" && index == 0)) {
                                // Consume small bodies so OkHttp can reuse the connection for the measurement.
                                response.body?.byteStream()?.readBounded(65_537)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The first request may arrive before the observatory has selected an outbound.
                        if (waitForBalancer && e is IOException && deadline - System.nanoTime() > 250_000_000L) {
                            delay(250)
                            continue
                        }
                        if (warmup) throw e
                        if (failure?.isProbeTimeout() != true) failure = e
                    }
                    break
                }
            }
            if (warmup) error("Сервер закрывает соединение до повторного замера")
            return best ?: throw (failure ?: IllegalStateException("Проверка не выполнена"))
        }
    }
}

private class ProbeConnectionTracker : EventListener() {
    var connection: Connection? = null

    override fun connectionAcquired(call: Call, connection: Connection) {
        this.connection = connection
    }
}
