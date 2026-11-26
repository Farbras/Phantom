package app.phantom.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

fun id() = UUID.randomUUID().toString()
fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings() = (0 until length()).map { getString(it) }

data class Server(
    val id: String = id(), val name: String, val config: String,
    val issue: String = "", val favorite: Boolean = false,
    val latency: Long? = null, val testedAt: Long = 0, val latencyError: String = "",
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("config", config).put("issue", issue)
        .put("favorite", favorite).put("latency", latency ?: JSONObject.NULL).put("testedAt", testedAt).put("latencyError",latencyError)
        .put("latencyVersion", LATENCY_VERSION)
    fun summary(): String = runCatching {
        val c = JSONObject(config)
        val all = c.getJSONArray("outbounds").objects().filter { it.optString("protocol") !in listOf("freedom", "dns", "blackhole") }
        if (all.size > 1) "${all.size} узлов · составной JSON" else all.first().let {
            val st = it.optJSONObject("streamSettings") ?: JSONObject()
            "${it.optString("protocol").uppercase()} · ${st.optString("network", "tcp").uppercase()} · ${st.optString("security", "none").uppercase()}"
        }
    }.getOrDefault(issue.ifBlank { "Конфигурация" })
    fun description(): String = runCatching { JSONObject(config).optJSONObject("meta")?.optString("serverDescription").orEmpty().take(300) }.getOrDefault("")
    companion object {
        private const val LATENCY_VERSION = 2

        fun from(j: JSONObject): Server {
            val currentMeasurement = j.optInt("latencyVersion") == LATENCY_VERSION
            return Server(j.getString("id"), j.getString("name"), j.getString("config"), j.optString("issue"),
                j.optBoolean("favorite"),
                if (currentMeasurement && !j.isNull("latency")) j.optLong("latency") else null,
                if (currentMeasurement) j.optLong("testedAt") else 0,
                if (currentMeasurement) j.optString("latencyError") else "")
        }
    }
}

data class Subscription(
    val id: String = id(), val name: String, val url: String = "", val servers: List<Server> = emptyList(),
    val pinned: Boolean = false, val expanded: Boolean = true, val updated: Long = 0,
    val metadata: Map<String,String> = emptyMap(), val error: String = "", val userAgent: String = "",
    val lastFetchHost: String = "",
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("servers", JSONArray(servers.map { it.json() }))
        .put("pinned", pinned).put("expanded", expanded).put("updated", updated).put("metadata", JSONObject(metadata))
        .put("error", error).put("userAgent", userAgent).put("lastFetchHost",lastFetchHost)
    companion object {
        fun from(j: JSONObject) = Subscription(j.getString("id"),j.getString("name"),j.optString("url"),
            j.getJSONArray("servers").objects().map(Server::from),j.optBoolean("pinned"),j.optBoolean("expanded",true),
            j.optLong("updated"),j.optJSONObject("metadata")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap(),
            j.optString("error"),j.optString("userAgent"),j.optString("lastFetchHost"))
    }
}

object Defaults {
    val values = mapOf(
        "animations" to "true", "textScale" to "1.0", "terminalCharacterDelay" to "40", "accent" to "violet",
        "autoUpdate" to "false", "updateHours" to "12", "fetchTimeout" to "20", "updateNotice" to "false",
        "updateOnOpen" to "false", "pingOnOpen" to "false", "connectOnOpen" to "false",
        "hwid" to "false", "userAgent" to "Phantom/1.0 (Android)", "deduplicate" to "true", "collapsible" to "true",
        "sort" to "original", "filter" to "", "pingMethod" to "auto", "pingMode" to "default", "pingTimeout" to "7",
        "testUrl" to "https://www.gstatic.com/generate_204", "ipv6" to "false", "ipPreference" to "UseIPv4",
        "lanDirect" to "false", "socksPort" to "10808", "httpEnabled" to "false", "httpPort" to "10809",
        "lanProxy" to "false", "proxyUser" to "", "proxyPassword" to "", "mux" to "false", "fragment" to "false",
        "logLevel" to "warning", "dnsHistory" to "false", "dns" to "https://1.1.1.1/dns-query",
        "appMode" to "all", "packages" to "", "routeProfile" to "default",
    )
}

data class Catalog(
    val subscriptions: List<Subscription> = emptyList(), val selected: String = "",
    val settings: Map<String,String> = Defaults.values, val installationId: String = id(),
    val routeProfiles: Map<String,String> = mapOf("default" to "[]"), val totalTx: Long = 0, val totalRx: Long = 0,
    val lastWorking: List<String> = emptyList(),
) {
    fun setting(k: String) = settings[k] ?: Defaults.values[k].orEmpty()
    fun flag(k: String) = setting(k).toBoolean()
    fun number(k: String, fallback: Int) = setting(k).toIntOrNull() ?: fallback
    fun selectedServer() = subscriptions.flatMap { it.servers }.firstOrNull { it.id == selected }
    fun json() = JSONObject().put("subscriptions",JSONArray(subscriptions.map { it.json() })).put("selected",selected)
        .put("settings",JSONObject(settings)).put("installationId",installationId).put("routeProfiles",JSONObject(routeProfiles))
        .put("totalTx",totalTx).put("totalRx",totalRx).put("lastWorking",JSONArray(lastWorking))
    companion object {
        fun from(j: JSONObject) = Catalog(j.optJSONArray("subscriptions")?.objects()?.map(Subscription::from) ?: emptyList(),j.optString("selected"),
            Defaults.values + (j.optJSONObject("settings")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: emptyMap()),
            j.optString("installationId",id()),j.optJSONObject("routeProfiles")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } } ?: mapOf("default" to "[]"),
            j.optLong("totalTx"),j.optLong("totalRx"),j.optJSONArray("lastWorking")?.strings().orEmpty())
    }
}
