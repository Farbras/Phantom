package app.phantom.core

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.phantom.data.Catalog
import app.phantom.data.safeError
import app.phantom.vpn.PhantomVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libXray.DialerController
import libXray.LibXray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

private const val REQUEST = 1
private const val SUCCESS = 1
private const val TIMEOUT = 2
private const val ERROR = 3
private val probeLock = Mutex()

/** Isolated Go runtime: probing never replaces the core that owns the active tunnel. */
class ProbeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != REQUEST) return@Handler false
        val reply = message.replyTo ?: return@Handler true
        val data = message.data
        scope.launch {
            val result = try {
                val delay = probeLock.withLock { measure(data) }
                Message.obtain().apply {
                    what = SUCCESS
                    this.data = Bundle().apply { putLong("delay", delay) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Message.obtain().apply {
                    what = if (e.isProbeTimeout()) TIMEOUT else ERROR
                    this.data = Bundle().apply { putString("error", safeError(e)) }
                }
            }
            try { reply.send(result) } catch (_: RemoteException) { }
        }
        true
    })

    private suspend fun measure(data: Bundle): Long {
        val protectConnection = if (data.getBoolean("active")) {
            bindMessenger(this, Intent(this, PhantomVpnService::class.java).setAction(PhantomVpnService.ACTION_PROTECT), 0)
        } else null
        try {
            ProbeSockets.protector = protectConnection?.first
            ProbeSockets.ensureRegistered()
            val cm = getSystemService(ConnectivityManager::class.java)
            val dns = cm.probeNetwork()?.let { cm.getLinkProperties(it)?.dnsServers?.firstOrNull()?.hostAddress } ?: "1.1.1.1"
            LibXray.setDNS(ProbeSockets, if (':' in dns) "[$dns]:53" else "$dns:53")
            val settings = JSONObject(data.getString("settings")!!)
            val catalog = Catalog(settings = settings.keys().asSequence().associateWith { settings.getString(it) })
            return Prober.proxyTestJson(data.getString("config")!!, catalog, catalog.number("pingTimeout", 7).coerceIn(2, 60))
        } finally {
            LibXray.resetDNS()
            ProbeSockets.protector = null
            protectConnection?.let { unbindService(it.second) }
        }
    }

    override fun onBind(intent: Intent?) = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private object ProbeSockets : DialerController {
    @Volatile var protector: Messenger? = null
    private var registered = false

    fun ensureRegistered() {
        // Xray appends each new controller. Reuse one controller for the probe process.
        if (registered) return
        LibXray.registerDialerController(this)
        registered = true
    }

    override fun protectFd(fd: Long): Boolean {
        val service = protector ?: return true
        val latch = CountDownLatch(1)
        val socketProtected = AtomicBoolean(false)
        return try {
            ParcelFileDescriptor.fromFd(fd.toInt()).use { copy ->
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    socketProtected.set(message.what == SUCCESS)
                    latch.countDown()
                    true
                })
                service.send(Message.obtain().apply {
                    what = PhantomVpnService.PROTECT_SOCKET
                    replyTo = reply
                    data = Bundle().apply { putParcelable("fd", copy) }
                })
                latch.await(5, TimeUnit.SECONDS) && socketProtected.get()
            }
        } catch (_: Exception) { false }
    }
}

private suspend fun bindMessenger(context: Context, intent: Intent, flags: Int): Pair<Messenger, ServiceConnection> =
    withTimeoutOrNull(6000) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (continuation.isActive) continuation.resume(Messenger(binder) to this)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        continuation.cancel(IllegalStateException("Служба проверки отключилась"))
                    }

                    override fun onNullBinding(name: ComponentName) {
                        continuation.cancel(IllegalStateException("Служба проверки отклонила подключение"))
                    }
                }
                check(context.bindService(intent, connection, flags)) { "Не удалось запустить службу проверки" }
                continuation.invokeOnCancellation { context.unbindService(connection) }
            }
        }
    } ?: throw ProbeTimeout()

object RemoteProbeClient {
    suspend fun probe(context: Context, config: String, catalog: Catalog, active: Boolean): Long {
        val (messenger, connection) = bindMessenger(context, Intent(context, ProbeService::class.java), Context.BIND_AUTO_CREATE)
        try {
            val timeout = catalog.number("pingTimeout", 7).coerceIn(2, 60)
            val samples = if (catalog.setting("pingMode") in listOf("double", "keepalive")) 2 else 1
            val result = CompletableDeferred<Long>()
            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    SUCCESS -> result.complete(message.data.getLong("delay"))
                    TIMEOUT -> result.completeExceptionally(ProbeTimeout())
                    else -> result.completeExceptionally(IllegalStateException(message.data.getString("error", "Проверка не выполнена")))
                }
                true
            })
            val death = IBinder.DeathRecipient { result.completeExceptionally(IllegalStateException("Служба проверки отключилась")) }
            messenger.binder.linkToDeath(death, 0)
            try {
                val settings = catalog.settings.filterKeys { it in listOf("testUrl", "pingMethod", "pingMode", "pingTimeout") }
                messenger.send(Message.obtain().apply {
                    what = REQUEST
                    replyTo = reply
                    data = Bundle().apply {
                        putString("config", config)
                        putString("settings", JSONObject(settings).toString())
                        putBoolean("active", active)
                    }
                })
                return withTimeoutOrNull((timeout * samples + 15L) * 1000) { result.await() } ?: throw ProbeTimeout()
            } finally {
                messenger.binder.unlinkToDeath(death, 0)
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
