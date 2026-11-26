package app.phantom

import android.app.Application
import app.phantom.core.Prober
import app.phantom.data.Subscriptions
import app.phantom.data.Vault
import kotlinx.coroutines.*
import java.io.File

class PhantomApp: Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var vault: Vault
    lateinit var subscriptions: Subscriptions
    lateinit var prober: Prober
    val ready=CompletableDeferred<Unit>()
    var startupError: String?=null
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return // Probe processes never open the vault.
        vault=Vault(this);subscriptions=Subscriptions(this,vault)
        prober = Prober(vault, this)
        scope.launch {
            try {
                vault.load()
                app.phantom.vpn.VpnRuntime.logLevel=vault.state.value.setting("logLevel")
                for(name in listOf("geoip.dat","geosite.dat")) {
                    val target=File(filesDir,name)
                    if(!target.exists()) assets.open(name).use { input->target.outputStream().use(input::copyTo) }
                }
                subscriptions.schedule()
                ready.complete(Unit)
                launch { prober.checkUnmeasuredProfiles() }
            } catch(e: Exception) { startupError="Не удалось открыть защищённое хранилище. Данные не перезаписаны.";ready.completeExceptionally(e) }
        }
    }
}
