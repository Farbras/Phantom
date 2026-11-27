package app.phantom.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.phantom.R
import app.phantom.core.Importer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class Subscriptions(private val context: Context, private val vault: Vault) {
    suspend fun importText(text: String, title: String = "Импорт", depth: Int = 0): Unit = withContext(Dispatchers.IO) {
        require(depth<3 && text.toByteArray().size<=Importer.MAX_BYTES) { "Слишком большой или вложенный импорт" }
        val value=text.trim()
        val uri=runCatching { android.net.Uri.parse(value).normalizeScheme() }.getOrNull()
        when {
            uri?.scheme=="phantom" -> {
                require(uri.host=="import") { "Поддерживается phantom://import?url=…" }
                importText(uri.getQueryParameter("url") ?: uri.getQueryParameter("text") ?: error("В ссылке нет url или text"),title,depth+1)
            }
            uri?.scheme == "https" && '\n' !in value -> addUrl(uri.toString(),title)
            value.startsWith("happ://") -> error("Ссылка управления Happ не является конфигурацией. Нужен исходный URL или Xray JSON")
            else -> {
                val servers=Importer.parse(value)
                vault.update { c ->
                    val sub=Subscription(name=title,servers=servers,updated=System.currentTimeMillis())
                    c.copy(subscriptions=c.subscriptions+sub,selected=c.selected.ifBlank { servers.firstOrNull { it.issue.isBlank() }?.id.orEmpty() })
                }
            }
        }
    }
    private suspend fun addUrl(url: String,title: String) {
        val uri=android.net.Uri.parse(url).normalizeScheme()
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null) { "Для подписки нужен HTTPS URL без логина в адресе" }
        val c=vault.state.value
        val duplicate=c.subscriptions.firstOrNull { it.url==url }
        if(duplicate!=null && c.flag("deduplicate")) { refresh(duplicate.id);return }
        val sub=Subscription(name=title.ifBlank { "Подписка" },url=url)
        val fetched=fetch(sub)
        vault.update { it.copy(subscriptions=it.subscriptions+fetched,selected=it.selected.ifBlank { fetched.servers.firstOrNull { s->s.issue.isBlank() }?.id.orEmpty() }) }
        app.phantom.vpn.VpnRuntime.log("Подписка добавлена: ${fetched.servers.size} записей")
    }
    private fun client(timeout: Int)=OkHttpClient.Builder().connectTimeout(timeout.toLong(),TimeUnit.SECONDS)
        .readTimeout(timeout.toLong(),TimeUnit.SECONDS).callTimeout(timeout.toLong(),TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    private fun fetchSync(sub: Subscription, c: Catalog, ua: String): Pair<String,Map<String,String>> {
        val req=Request.Builder().url(sub.url).header("User-Agent",ua)
        if(c.flag("hwid")) {
            req.header("x-hwid",c.installationId).header("x-device-os","Android").header("x-ver-os",Build.VERSION.RELEASE)
                .header("x-device-model",Build.MODEL)
        }
        client(c.number("fetchTimeout",20).coerceIn(5,120)).newCall(req.build()).execute().use { response ->
            require(response.isSuccessful) { "Подписка: HTTP ${response.code}" }
            require(response.header("encrypt-tag").isNullOrBlank()) { "Содержимое зашифровано Happ AES-GCM: запросите обычный Xray JSON у провайдера" }
            val body=response.body ?: error("Пустой HTTP-ответ")
            require(body.contentLength()<=Importer.MAX_BYTES) { "Подписка больше 8 МБ" }
            val bytes=body.byteStream().use { it.readBounded(Importer.MAX_BYTES+1) }
            require(bytes.size<=Importer.MAX_BYTES) { "Подписка больше 8 МБ" }
            val allowed=listOf("profile-title","announce","subscription-userinfo","profile-update-interval","support-url","profile-web-page-url","proxy-ping-mode","ping-type","check-url-via-proxy","providerid")
            val meta=allowed.mapNotNull { k->response.header(k)?.let { k to Importer.metadata(it).take(2000) } }.toMap().toMutableMap()
            val text=String(bytes,Charsets.UTF_8)
            text.lineSequence().filter { it.startsWith("#") && ':' in it }.forEach {
                val k=it.substringAfter('#').substringBefore(':').trim().lowercase()
                if(k in allowed && k !in meta) meta[k]=Importer.metadata(it.substringAfter(':').trim()).take(2000)
            }
            return text to meta
        }
    }
    private suspend fun fetch(sub: Subscription): Subscription = withContext(Dispatchers.IO) {
        val c=vault.state.value
        val ua=sub.userAgent.ifBlank { c.setting("userAgent") }
        val encrypted = if (sub.userAgent.isBlank() && ua == Defaults.values["userAgent"] && !c.flag("hwid") &&
            OversecureSubscription.supports(sub.url)) {
            try {
                OversecureSubscription.fetch(sub.url, sub.lastFetchHost)
            } catch (_: OversecureSubscription.CryptoUnavailable) {
                null
            }
        } else null
        val (text,meta) = encrypted?.let { it.text to sub.metadata } ?: fetchSync(sub,c,ua)
        val parsed=Importer.parse(text)
        require(parsed.isNotEmpty()) { "В подписке нет серверов" }
        val nodes=if(c.flag("deduplicate")) parsed.distinctBy { it.id } else parsed
        val old = sub.servers.associateBy { it.id }
        val oldNames = sub.servers.groupBy { it.name }
        val newNames = nodes.groupingBy { it.name }.eachCount()
        val merged = nodes.map { server ->
            val previous = old[server.id] ?: oldNames[server.name]?.singleOrNull()?.takeIf {
                newNames[server.name] == 1 && it.issue.isBlank() && server.issue.isBlank()
            }
            when {
                previous == null -> server
                previous.config == server.config -> server.copy(id = previous.id, favorite = previous.favorite,
                    latency = previous.latency, testedAt = previous.testedAt, latencyError = previous.latencyError)
                else -> server.copy(id = previous.id, favorite = previous.favorite)
            }
        }
        // A failed update must not destroy a working subscription.
        require(merged.any { it.issue.isBlank() } || sub.servers.isEmpty()) { "Обновление не содержит поддерживаемых серверов; прежний список сохранён" }
        sub.copy(name=meta["profile-title"]?.ifBlank { sub.name } ?: sub.name,servers=merged,metadata=meta,
            updated=System.currentTimeMillis(),error="",lastFetchHost=encrypted?.host.orEmpty())
    }
    suspend fun refresh(subId: String) = withContext(Dispatchers.IO) {
        val sub=vault.state.value.subscriptions.find { it.id==subId } ?: return@withContext
        if(sub.url.isBlank()) return@withContext
        try {
            val fresh=fetch(sub)
            vault.update { current ->
                val latest=current.subscriptions.find { it.id==subId } ?: return@update current
                if(latest.url!=sub.url || latest.userAgent!=sub.userAgent || latest.updated!=sub.updated) return@update current
                val previous = latest.servers.associateBy { it.id }
                val nodes = fresh.servers.map { server ->
                    val saved = previous[server.id]
                    if (saved != null && saved.config == server.config) server.copy(favorite = saved.favorite, latency = saved.latency,
                        testedAt = saved.testedAt, latencyError = saved.latencyError)
                    else server.copy(favorite = saved?.favorite ?: server.favorite)
                }
                val subscriptions = current.subscriptions.map {
                    if (it.id == subId) fresh.copy(pinned = latest.pinned, expanded = latest.expanded, servers = nodes) else it
                }
                val selected = if (latest.servers.any { it.id == current.selected } &&
                    subscriptions.none { group -> group.servers.any { it.id == current.selected && it.issue.isBlank() } }) {
                    fresh.servers.firstOrNull { it.issue.isBlank() }?.id.orEmpty()
                } else current.selected
                current.copy(subscriptions = subscriptions, selected = selected)
            }
            if(vault.state.value.flag("updateNotice")) notifyUpdated()
            app.phantom.vpn.VpnRuntime.log("Подписка обновлена: ${fresh.servers.size} записей")
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            val message=safeError(e)
            app.phantom.vpn.VpnRuntime.log("Ошибка обновления подписки: $message","error")
            vault.update { c->c.copy(subscriptions=c.subscriptions.map {
                if(it.id==subId && it.url==sub.url && it.userAgent==sub.userAgent && it.updated==sub.updated) it.copy(error=message) else it
            }) }
            throw IllegalStateException(message)
        }
    }
    suspend fun refreshAll(dueOnly: Boolean=false) {
        val c=vault.state.value
        for(s in c.subscriptions.filter { it.url.isNotBlank() }) {
            val hours=c.number("updateHours",12).coerceIn(1,168)
            if (!dueOnly || System.currentTimeMillis() - s.updated >= hours * 3600000L) {
                try { refresh(s.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Individual refresh errors are stored in the catalog. */ }
            }
        }
    }
    fun schedule() {
        val work=WorkManager.getInstance(context)
        if(vault.state.value.flag("autoUpdate")) {
            val request=PeriodicWorkRequestBuilder<UpdateWorker>(1,TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            work.enqueueUniquePeriodicWork("subscriptions",ExistingPeriodicWorkPolicy.UPDATE,request)
        } else work.cancelUniqueWork("subscriptions")
    }
    private fun notifyUpdated() {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates","Обновления подписок",NotificationManager.IMPORTANCE_LOW))
        runCatching { nm.notify(2,NotificationCompat.Builder(context,"updates").setSmallIcon(R.drawable.ic_vpn).setContentTitle("Phantom")
            .setContentText("Подписка обновлена").setAutoCancel(true).build()) }
    }
}

fun safeError(e: Throwable): String = when(e) {
    is app.phantom.core.CoreFailure -> e.category
    is java.net.SocketTimeoutException -> "Истекло время ожидания"
    is java.net.UnknownHostException -> "Не удалось разрешить имя сервера"
    is javax.net.ssl.SSLException -> "Ошибка проверки TLS-сертификата"
    is java.io.IOException -> "Ошибка сети или чтения данных"
    is org.json.JSONException -> "Некорректная структура JSON"
    is IllegalArgumentException, is IllegalStateException -> e.message?.takeIf {
        it.length<220 && !it.contains(Regex("https?://|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}|\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"))
    } ?: "Некорректные или несовместимые параметры"
    else -> "Операция не выполнена (${e.javaClass.simpleName})"
}

class UpdateWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as app.phantom.PhantomApp
        app.ready.await()
        app.subscriptions.refreshAll(dueOnly=true)
        return Result.success()
    }
}
