package app.phantom

import app.phantom.core.ConfigBuilder
import app.phantom.core.Importer
import app.phantom.core.ProbeEndpoint
import app.phantom.core.RoutingImport
import app.phantom.core.XrayLoopback
import app.phantom.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigurationTest {
    private fun server(provider: Boolean=false)=Server(name="Synthetic",config="""{"_phantomProviderJson":$provider,"inbounds":[{"listen":"0.0.0.0","port":1,"protocol":"socks"}],"outbounds":[{"tag":"proxy","protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":12345}]}}],"dns":{"servers":["9.9.9.9"]},"routing":{"rules":[]}}""")
    @Test fun probeUsesTheConfiguredAddressAndPortRatherThanSni() {
        val config = """{"outbounds":[{"protocol":"vless","settings":{"address":"[2001:db8::1]","port":8443},"streamSettings":{"network":"xhttp","tlsSettings":{"serverName":"sni.example"}}},{"protocol":"freedom"}]}"""
        assertEquals(ProbeEndpoint("2001:db8::1", 8443, true), ProbeEndpoint.from(config))
    }
    @Test fun probeDoesNotTreatUdpOrMissingPortAsDirectTcp() {
        val config = """{"outbounds":[{"protocol":"hysteria","settings":{"address":"vpn.example","port":443},"streamSettings":{"network":"hysteria"}}]}"""
        assertEquals(ProbeEndpoint("vpn.example", 443, false), ProbeEndpoint.from(config))
        assertNull(ProbeEndpoint.from(config.replace("\"address\":\"vpn.example\",\"port\":443", "\"address\":\"vpn.example\"")))
    }
    @Test fun probeDoesNotPickOneOfSeveralRemoteAddresses() {
        val config = """{"outbounds":[{"protocol":"vmess","settings":{"vnext":[{"address":"a.example","port":1111},{"address":"b.example","port":2222}]}}]}"""
        assertNull(ProbeEndpoint.from(config))
    }
    @Test fun importedListenersAreReplacedAndDnsHijackComesFirst() {
        val c=JSONObject(ConfigBuilder.build(server(),Catalog(),"C:/assets"))
        assertEquals("127.0.0.1",c.getJSONArray("inbounds").getJSONObject(0).getString("listen"))
        assertEquals(ConfigBuilder.TUN_PORT,c.getJSONArray("inbounds").getJSONObject(0).getInt("port"))
        assertEquals("phantom-dns",c.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertEquals("none",c.getJSONObject("log").getString("loglevel"))
    }
    @Test fun providerCompositeKeepsBalancersDnsAndTransport() {
        val original=JSONObject(server(true).config)
        original.getJSONObject("routing").put("balancers",JSONArray("""[{"tag":"auto","selector":["proxy"],"strategy":{"type":"leastPing"}}]"""))
        original.getJSONObject("routing").put("rules",JSONArray("""[{"type":"field","network":"tcp,udp","balancerTag":"auto"}]"""))
        val built=JSONObject(ConfigBuilder.build(server(true).copy(config=original.toString()),Catalog(),"assets"))
        assertEquals("9.9.9.9",built.getJSONObject("dns").getJSONArray("servers").getString(0))
        assertEquals("auto",built.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getString("tag"))
        assertEquals("socks",built.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }
    @Test fun directTunnelUsesOnlyTheRuntimeDescriptorAndRestrictsMetricsToThePrivateSocksInbound() {
        val profile = JSONObject(server().config).put("env", JSONObject().put("xray.tun.fd", "99"))
        val built = JSONObject(ConfigBuilder.buildTunnel(server().copy(config = profile.toString()), Catalog(), "assets", 41, XrayLoopback()))
        assertEquals("41", built.getJSONObject("env").getString("xray.tun.fd"))
        val inbounds = built.getJSONArray("inbounds").objects()
        val tun = inbounds.single { it.getString("protocol") == "tun" }
        assertEquals(ConfigBuilder.TUN_TAG, tun.getString("tag"))
        val socks = inbounds.single { it.getString("tag") == "socks" }
        assertEquals("password", socks.getJSONObject("settings").getString("auth"))
        assertEquals("127.0.0.1", socks.getString("listen"))
        val metrics = built.getJSONObject("metrics")
        assertFalse(metrics.has("listen"))
        val route = built.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
        assertEquals(listOf("socks"), route.getJSONArray("inboundTag").strings())
        assertEquals(metrics.getString("tag"), route.getString("outboundTag"))
    }
    @Test fun customRoutesPrecedeProviderRules() {
        val c=Catalog(routeProfiles=mapOf("default" to """[{"action":"Block","domain":["full:blocked.example"]}]"""))
        val rules=JSONObject(ConfigBuilder.build(server(),c,"assets")).getJSONObject("routing").getJSONArray("rules")
        assertEquals("phantom-block",rules.getJSONObject(1).getString("outboundTag"))
    }
    @Test(expected=IllegalArgumentException::class) fun lanProxyRequiresCredentials() {
        ConfigBuilder.build(server(),Catalog(settings=Defaults.values+("lanProxy" to "true")),"assets")
    }
    @Test(expected=IllegalArgumentException::class) fun localPortsCannotCollideWithTun() {
        ConfigBuilder.build(server(),Catalog(settings=Defaults.values+("socksPort" to ConfigBuilder.TUN_PORT.toString())),"assets")
    }
    @Test fun jsonImportPreservesRootAndDoesNotClaimUnsupportedJsonWorks() {
        val nodes=Importer.parse("""[{"remarks":"auto","outbounds":[],"burstObservatory":{"subjectSelector":["a"]}},{"remarks":"bad"}]""")
        assertEquals(2,nodes.size);assertTrue(nodes.all { it.issue.isNotBlank() })
        assertTrue(JSONObject(nodes.first().config).has("burstObservatory"))
    }
    @Test(expected=IllegalArgumentException::class) fun routeImportRejectsLossyXrayRules() {
        RoutingImport.parse("""{"rules":[{"type":"field","port":"443","outboundTag":"proxy"}]}""")
    }
    @Test fun routeImportMapsExplicitDirections() {
        val r=RoutingImport.parse("""{"rules":[{"type":"field","domain":["domain:example.com"],"outboundTag":"direct"}]}""")
        assertEquals("Direct",r.getJSONObject(0).getString("action"))
    }
}
