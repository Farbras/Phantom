package app.phantom.core

import app.phantom.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object ConfigBuilder {
    const val TUN_PORT = 10990
    const val PROBE_PORT = 10991
    internal const val TUN_MTU = 1500
    internal const val TUN_ADDRESS = "26.26.26.1"
    internal const val TUN_TAG = "tun-in"
    internal const val METRICS_HOST = "phantom-metrics.invalid"
    private const val METRICS_TAG = "phantom-metrics"
    private fun outbound(tag: String, protocol: String, settings: JSONObject = JSONObject()) =
        JSONObject().put("tag",tag).put("protocol",protocol).put("settings",settings)
    private fun inbound(tag: String, protocol: String, port: Int, listen: String, settings: JSONObject) =
        JSONObject().put("tag",tag).put("protocol",protocol).put("port",port).put("listen",listen).put("settings",settings)
            .put("sniffing",JSONObject().put("enabled",true).put("destOverride",JSONArray(listOf("http","tls","quic"))).put("routeOnly",true))

    fun build(server: Server, catalog: Catalog, assetDir: String, probe: Boolean = false): String =
        build(server, catalog, assetDir, probe, null, null).toString()

    internal data class TunnelConfig(val json: String, val protocolsByTag: Map<String, String>)

    internal fun buildTunnel(server: Server, catalog: Catalog, assetDir: String, fd: Int, control: XrayLoopback): TunnelConfig {
        val config = build(server, catalog, assetDir, false, fd, control)
        val protocols = config.getJSONArray("outbounds").remoteOutbounds().associate {
            it.getString("tag") to it.getString("protocol").uppercase(Locale.ROOT)
        }
        return TunnelConfig(config.toString(), protocols)
    }

    private fun build(server: Server, catalog: Catalog, assetDir: String, probe: Boolean, fd: Int?, control: XrayLoopback?): JSONObject {
        require(server.issue.isBlank()) { server.issue }
        val c=JSONObject(server.config)
        val outs=c.optJSONArray("outbounds") ?: error("Нет outbounds")
        val remotes=outs.remoteOutbounds()
        require(remotes.isNotEmpty()) { "В конфигурации нет удалённого прокси" }
        remotes.forEachIndexed { i,o -> if(o.optString("tag").isBlank()) o.put("tag","phantom-node-$i") }
        val providerJson=c.optBoolean("_phantomProviderJson")
        c.remove("_phantomProviderJson")
        // Never trust imported local listeners, file paths, RPC endpoints or environment.
        c.remove("api");c.remove("metrics");c.remove("stats")
        c.put("env",JSONObject().put("xray.location.asset",assetDir).apply {
            if (fd != null) put("xray.tun.fd", fd.toString())
        })
        c.put("log",JSONObject().put("loglevel","none").put("access","none").put("error","none"))
        val controlSettings = JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1")
        if (control != null) controlSettings.put("auth", "password").put("accounts", control.accounts())
        val inbounds = JSONArray().put(inbound("socks", "socks", if (probe) PROBE_PORT else TUN_PORT, "127.0.0.1", controlSettings))
        if (fd != null) {
            inbounds.put(JSONObject().put("tag", TUN_TAG).put("port", 0).put("protocol", "tun")
                .put("settings", JSONObject().put("name", "phantom").put("mtu", TUN_MTU))
                .put("sniffing", JSONObject().put("enabled", true).put("routeOnly", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic")))))
            c.put("stats", JSONObject()).put("metrics", JSONObject().put("tag", METRICS_TAG))
            val policy = c.optJSONObject("policy") ?: JSONObject()
            val system = policy.optJSONObject("system") ?: JSONObject()
            system.put("statsInboundUplink", true).put("statsInboundDownlink", true)
                .put("statsOutboundUplink", true).put("statsOutboundDownlink", true)
            c.put("policy", policy.put("system", system))
        }
        if(!probe) {
            val socks=catalog.number("socksPort",10808)
            val http=catalog.number("httpPort",10809)
            require(socks in 1024..65535 && socks !in listOf(TUN_PORT,PROBE_PORT)) { "Недопустимый SOCKS-порт" }
            require(!catalog.flag("httpEnabled") || (http in 1024..65535 && http !in listOf(socks,TUN_PORT,PROBE_PORT))) { "HTTP-порт занят или недопустим" }
            val user=catalog.setting("proxyUser");val pass=catalog.setting("proxyPassword")
            require(!catalog.flag("lanProxy") || (user.isNotBlank() && pass.isNotBlank())) { "Для прокси в LAN задайте логин и пароль" }
            require(user.isBlank() == pass.isBlank()) { "Укажите и логин, и пароль прокси" }
            val listen=if(catalog.flag("lanProxy")) "0.0.0.0" else "127.0.0.1"
            val accounts=JSONArray().put(JSONObject().put("user",user).put("pass",pass))
            val ss=JSONObject().put("auth",if(user.isBlank()) "noauth" else "password").put("udp",true)
            if(user.isNotBlank()) ss.put("accounts",accounts)
            inbounds.put(inbound("phantom-local-socks","socks",socks,listen,ss))
            if(catalog.flag("httpEnabled")) {
                val hs=JSONObject();if(user.isNotBlank()) hs.put("accounts",accounts)
                inbounds.put(inbound("http","http",http,listen,hs))
            }
        }
        c.put("inbounds",inbounds)
        val routing=c.optJSONObject("routing") ?: JSONObject().put("domainStrategy","IPIfNonMatch")
        val providerRules=routing.optJSONArray("rules") ?: JSONArray()
        require(providerRules.objects().none { it.optString("outboundTag") == METRICS_TAG }) { "Зарезервированные теги Phantom" }
        val target=JSONObject()
        val balancing=providerRules.objects().lastOrNull { it.has("balancerTag") }
        if(balancing!=null) target.put("balancerTag",balancing.getString("balancerTag"))
        else target.put("outboundTag",remotes.first().getString("tag"))
        val direct="phantom-direct";val block="phantom-block";val dnsTag="phantom-dns"
        require(outs.objects().none { it.optString("tag") in listOf(direct,block,dnsTag,"phantom-fragment",METRICS_TAG) }) { "Зарезервированные теги Phantom" }
        outs.put(outbound(direct,"freedom",JSONObject().put("domainStrategy",catalog.setting("ipPreference"))))
        outs.put(outbound(block,"blackhole"))
        outs.put(outbound(dnsTag,"dns"))
        val rules=JSONArray()
        if (fd == null) {
            rules.put(JSONObject().put("type","field").put("ip",JSONArray(listOf("10.66.0.2/32"))).put("port","53").put("outboundTag",dnsTag))
        } else {
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray(listOf("socks")))
                .put("domain", JSONArray(listOf("full:$METRICS_HOST"))).put("port", "80").put("outboundTag", METRICS_TAG))
            if (!providerJson) rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray(listOf(TUN_TAG)))
                .put("port", "53").put("outboundTag", dnsTag))
        }
        if(catalog.flag("lanDirect")) rules.put(JSONObject().put("type","field").put("ip",JSONArray(listOf("geoip:private"))).put("outboundTag",direct))
        val custom=JSONArray(catalog.routeProfiles[catalog.setting("routeProfile")] ?: "[]")
        custom.objects().forEach { r ->
            val action=r.optString("action","Proxy")
            require(action in listOf("Proxy","Direct","Block")) { "Направление правила: Proxy, Direct или Block" }
            require(r.has("domain") || r.has("ip")) { "Правило должно содержать domain или ip" }
            val rule=JSONObject().put("type","field")
            for(k in listOf("domain","ip")) if(r.has(k)) { require(r.getJSONArray(k).length()>0);rule.put(k,r.getJSONArray(k)) }
            if(action=="Proxy") target.keys().forEach { rule.put(it,target.get(it)) }
            else rule.put("outboundTag",if(action=="Direct") direct else block)
            rules.put(rule)
        }
        providerRules.objects().forEach { rule ->
            val tags = rule.optJSONArray("inboundTag")
            if (fd != null && tags != null && "socks" in tags.strings() && TUN_TAG !in tags.strings()) tags.put(TUN_TAG)
            rules.put(rule)
        }
        if(providerRules.length()==0) { val r=JSONObject().put("type","field").put("network","tcp,udp");target.keys().forEach { r.put(it,target.get(it)) };rules.put(r) }
        routing.put("rules",rules);c.put("routing",routing)
        if(!providerJson) c.put("dns",JSONObject().put("servers",JSONArray(listOf(catalog.setting("dns")))).put("queryStrategy",catalog.setting("ipPreference")))
        if(catalog.flag("mux")) {
            require(!providerJson && remotes.size==1) { "Mux: для составного JSON используйте встроенные параметры провайдера" }
            val o=remotes.first();val st=o.optJSONObject("streamSettings") ?: JSONObject()
            val users=o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)?.optJSONArray("users")
            require(st.optString("network","tcp") in listOf("tcp","raw") && users?.optJSONObject(0)?.optString("flow").orEmpty().isBlank()) { "Mux здесь несовместим с транспортом или Vision flow" }
            o.put("mux",JSONObject().put("enabled",true).put("concurrency",8).put("xudpConcurrency",16))
        }
        if(catalog.flag("fragment")) {
            require(!providerJson && remotes.size==1) { "Фрагментация: составной JSON сохраняет настройки провайдера" }
            val o=remotes.first();val st=o.optJSONObject("streamSettings") ?: JSONObject()
            require(st.optString("security")=="tls" && st.optString("network","tcp") in listOf("tcp","raw")) { "Фрагментация TLS ClientHello доступна только для RAW/TCP + TLS" }
            val sock=st.optJSONObject("sockopt") ?: JSONObject()
            require(!sock.has("dialerProxy")) { "Конфигурация уже содержит dialerProxy" }
            sock.put("dialerProxy","phantom-fragment");st.put("sockopt",sock);o.put("streamSettings",st)
            outs.put(outbound("phantom-fragment","freedom",JSONObject().put("fragment",JSONObject().put("packets","tlshello").put("length","100-200").put("interval","10-20"))))
        }
        c.put("outbounds",outs)
        return c
    }
}
