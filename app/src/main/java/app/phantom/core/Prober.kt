package app.phantom.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
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
    private val batch = Mutex()
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val checking = pending.asStateFlow()

    suspend fun test(servers: List<Server>) {
        if (!batch.tryLock()) return
        try {
            val targets = servers.filter { it.issue.isBlank() }.distinctBy { it.id }
            val catalog = vault.state.value
            val timeout = catalog.number("pingTimeout", 7).coerceIn(2, 60)
            pending.value = targets.mapTo(mutableSetOf()) { it.id }
            val directSlots = Semaphore(4)
            // The isolated Xray runtime and its local SOCKS listener serve one config at a time.
            val proxySlot = Semaphore(1)
            coroutineScope {
                for (server in targets) launch(Dispatchers.IO) {
                    var error = ""
                    val latency = try {
                        val method = catalog.setting("pingMethod")
                        val endpoint = ProbeEndpoint.from(server.config)
                        when {
                            method == "ICMP" -> directSlots.withPermit { icmpTest(requireEndpoint(endpoint), timeout) }
                            method == "TCP" || (method == "auto" && endpoint?.tcp == true) -> directSlots.withPermit {
                                val target = requireEndpoint(endpoint)
                                require(target.tcp) { "Транспорт сервера не использует TCP" }
                                tcpTest(target, timeout)
                            }
                            else -> proxySlot.withPermit {
                                val config = ConfigBuilder.build(server, catalog, context.filesDir.absolutePath, probe = true)
                                RemoteProbeClient.probe(context, config, catalog, VpnRuntime.state.value.phase != Phase.OFF)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = if (e.isProbeTimeout()) PROBE_TIMEOUT_LABEL else safeError(e)
                        -1L
                    }
                    vault.update { current ->
                        val testedAt = System.currentTimeMillis()
                        current.copy(subscriptions = current.subscriptions.map { sub ->
                            sub.copy(servers = sub.servers.map {
                                if (it.id == server.id && it.config == server.config) {
                                    it.copy(latency = latency, testedAt = testedAt, latencyError = error)
                                } else it
                            })
                        })
                    }
                    pending.update { it - server.id }
                }
            }
        } finally {
            pending.value = emptySet()
            batch.unlock()
        }
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
        require(VpnRuntime.state.value.phase == Phase.OFF) { "ICMP-тест требует отключённого VPN" }
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

        internal suspend fun proxyTestJson(json: String, catalog: Catalog, timeout: Int): Long {
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
            val method = if (catalog.setting("pingMethod") == "HEAD") "HEAD" else "GET"
            val request = Request.Builder().url(url).method(method, null).header("Cache-Control", "no-cache").apply {
                if (mode == "double") header("Connection", "close")
            }.build()
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ConfigBuilder.PROBE_PORT)))
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
                .connectionPool(ConnectionPool(if (mode == "keepalive") 2 else 0, 30, TimeUnit.SECONDS))
                .build()

            Core.validate(json)
            Core.start(json)
            try {
                var best: Long? = null
                var failure: Exception? = null
                val samples = if (mode in listOf("double", "keepalive")) 2 else 1
                repeat(samples) { index ->
                    val waitForBalancer = index == 0 && observedBalancer
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.toLong())
                    val retryInterval = TimeUnit.MILLISECONDS.toNanos(250)
                    while (true) {
                        val call = client.newCall(request)
                        val start = System.nanoTime()
                        if (waitForBalancer) {
                            call.timeout().timeout((deadline - start).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                        }
                        try {
                            call.execute().use { response ->
                                val latency = elapsedMillis(start)
                                best = minOf(best ?: latency, latency)
                                if (mode == "keepalive" && index == 0) {
                                    response.body?.byteStream()?.use { it.readBounded(65537) }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The observatory starts asynchronously. Until it has results, routing can
                            // send a request to the fallback (often blackhole) and close the socket.
                            if (waitForBalancer && best == null && e is IOException &&
                                deadline - System.nanoTime() > retryInterval
                            ) {
                                delay(250)
                                continue
                            }
                            if (failure?.isProbeTimeout() != true) failure = e
                        }
                        break
                    }
                }
                return best ?: throw (failure ?: IllegalStateException("Проверка не выполнена"))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                Core.stop()
            }
        }
    }
}
