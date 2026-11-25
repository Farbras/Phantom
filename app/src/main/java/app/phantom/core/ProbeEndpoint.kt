package app.phantom.core

import app.phantom.data.objects
import org.json.JSONObject

internal data class ProbeEndpoint(val host: String, val port: Int, val tcp: Boolean) {
    companion object {
        private val localProtocols = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")
        private val tcpProtocols = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")
        private val tcpTransports = setOf("raw", "tcp", "ws", "websocket", "grpc", "xhttp", "splithttp", "httpupgrade")

        fun from(config: String): ProbeEndpoint? {
            val json = JSONObject(config)
            val outbound = json.optJSONArray("outbounds")?.objects()
                ?.filterNot { it.optString("protocol") in localProtocols }?.singleOrNull() ?: return null
            val stream = outbound.optJSONObject("streamSettings")
            val socketOptions = stream?.optJSONObject("sockopt")
            if (outbound.optJSONObject("proxySettings")?.optString("tag").orEmpty().isNotBlank() ||
                socketOptions?.optString("dialerProxy").orEmpty().isNotBlank()
            ) return null

            val settings = outbound.optJSONObject("settings") ?: return null
            val remote = when {
                settings.has("vnext") -> settings.optJSONArray("vnext")?.objects()?.singleOrNull()
                settings.has("servers") -> settings.optJSONArray("servers")?.objects()?.singleOrNull()
                else -> settings
            } ?: return null
            val host = remote.optString("address").ifBlank { remote.optString("server") }
                .trim().removeSurrounding("[", "]")
            val port = remote.optInt("port")
            if (host.isBlank() || port !in 1..65535) return null
            val tcp = outbound.optString("protocol") in tcpProtocols &&
                (stream?.optString("network", "tcp") ?: "tcp") in tcpTransports
            return ProbeEndpoint(host, port, tcp)
        }
    }
}
