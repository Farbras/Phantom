package app.phantom

import android.content.*
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phantom.core.*
import app.phantom.data.*
import app.phantom.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as PhantomApp
    @Test fun nativeCoreAndUriParametersAreReal()=runBlocking {
        app.ready.await()
        assertTrue(Core.call("xrayVersion").getString("version").startsWith("26.11.9"))
        val links=listOf(
            "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=tcp&security=reality&sni=example.com&fp=chrome&pbk=UuJQTq3QyPs1Y5JNWjr8N9-NpcPWbFfJPnqhVoCbgWo&sid=0123456789abcdef&flow=xtls-rprx-vision#reality",
            "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=grpc&security=tls&serviceName=test&sni=example.com#grpc",
            "trojan://synthetic-password@example.com:443?type=ws&security=tls&path=%2Fws&sni=example.com#ws",
            "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=xhttp&security=tls&path=%2Fx&mode=auto&sni=example.com#xhttp"
        )
        for(link in links) {
            val server=Importer.parse(link).single()
            assertEquals("",server.issue)
            Core.validate(ConfigBuilder.build(server,Catalog(),app.filesDir.absolutePath))
        }
        val malformed=Importer.parse("vless://invalid@example.com:443?type=removed-transport#bad").single()
        assertTrue(malformed.issue.isNotBlank())
    }
    @Test fun vaultSurvivesReloadWithoutPlaintext()=runBlocking {
        app.ready.await()
        app.vault.setting("filter","synthetic-vault-marker")
        val other=Vault(app);other.load()
        assertEquals("synthetic-vault-marker",other.state.value.setting("filter"))
        val bytes=File(app.noBackupFilesDir,"catalog.aes").readBytes()
        assertFalse(String(bytes,Charsets.ISO_8859_1).contains("synthetic-vault-marker"))
        app.vault.setting("filter","")
    }
    @Test fun automaticLatencyCoversInstalledAndNewSubscriptions() = runBlocking {
        assumeTrue("Requires an existing test subscription on the device",
            InstrumentationRegistry.getArguments().getString("latencyAudit") == "true")
        app.ready.await()
        val installed = app.vault.state.value.subscriptions.filter { it.url.isNotBlank() }.map { it.id }.toSet()
        assumeTrue(installed.isNotEmpty())
        fun supported(catalog: Catalog) = catalog.subscriptions.filter { it.id in installed }
            .flatMap { it.servers }.filter { it.issue.isBlank() }
        val checked = withTimeout(300_000) {
            supported(app.vault.state.first { catalog ->
                val profiles = supported(catalog)
                profiles.isNotEmpty() && profiles.all { it.testedAt > 0 }
            })
        }
        val realLatencies = checked.mapNotNull { it.latency?.takeIf { delay -> delay >= 0 } }.sorted()
        assertTrue("At least one installed profile must respond", realLatencies.isNotEmpty())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nInstalled profiles: ${checked.size}, successful: ${realLatencies.size}, " +
                "median: ${realLatencies[realLatencies.size / 2]} ms, max: ${realLatencies.last()} ms\n")
        })

        val source = checked.first { (it.latency ?: -1L) >= 0 }
        val title = "Latency audit ${id()}"
        try {
            val profile = JSONObject(source.config).put("remarks", title)
            app.subscriptions.importText(profile.toString(), title)
            val imported = withTimeout(30_000) {
                app.vault.state.first { catalog ->
                    catalog.subscriptions.any { it.name == title && it.servers.single().testedAt > 0 }
                }.subscriptions.single { it.name == title }.servers.single()
            }
            assertTrue("A newly imported working profile must be measured without a manual check", (imported.latency ?: -1L) >= 0)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nNew subscription auto-ping: ${imported.latency} ms\n") })
        } finally {
            app.vault.update { catalog -> catalog.copy(subscriptions = catalog.subscriptions.filter { it.name != title }) }
        }
    }
    @Test fun realVpnCarriesAnotherApplicationAndRecovers()=runBlocking {
        app.ready.await()
        val private=File(app.filesDir,"audit.json")
        assumeTrue("Opt-in private fixture missing",private.exists())
        assumeTrue("VPN permission must be granted by the test device",VpnService.prepare(app)==null)
        val servers=Importer.parse(private.readText())
        val selected=servers.first { s->
            val outs=JSONObject(s.config).getJSONArray("outbounds").objects().filter { it.optString("protocol") !in listOf("dns","freedom","blackhole") }
            outs.size==1 && outs.first().optString("protocol")=="vless" && outs.first().optJSONObject("streamSettings")?.optString("security")=="reality"
        }
        app.vault.update { Catalog(subscriptions=listOf(Subscription(name="Private test",servers=servers)),selected=selected.id) }
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1200)
        try {
            PhantomVpnService.connect(app)
            waitOnline()
            assertTrue(VpnRuntime.state.value.tunnel)
            val before=VpnRuntime.state.value.rx
            assertEquals(204,otherAppRequest("https://www.gstatic.com/generate_204"))
            delay(1200)
            assertTrue("Another UID must increase tunnel counters",VpnRuntime.state.value.rx>before)
            // A probe in an independent Go process must leave the live core intact.
            Prober(app.vault,app).test(listOf(selected))
            assertTrue(Core.running())
            assertTrue(app.vault.state.value.selectedServer()!!.latency!!>=0)
            // Reconnect exercises fd ownership and native runtime reuse.
            PhantomVpnService.connect(app);delay(800);waitOnline()
            assertEquals(204,otherAppRequest("https://www.gstatic.com/generate_204"))
            // Block a different HTTPS destination using a domain rule and restore it afterwards.
            app.vault.update { it.copy(routeProfiles=mapOf("default" to """[{"action":"Block","domain":["full:example.com"]}]""")) }
            PhantomVpnService.connect(app);delay(800);waitOnline()
            assertEquals(-1,otherAppRequest("https://example.com"))
        } finally {
            PhantomVpnService.disconnect(app)
            repeat(100) { if(VpnRuntime.state.value.phase!=Phase.OFF) delay(100) }
            private.delete()
            app.vault.update { Catalog() }
        }
        assertEquals(Phase.OFF,VpnRuntime.state.value.phase)
    }
    private suspend fun waitOnline() {
        withTimeout(60000) {
            while(!VpnRuntime.state.value.online) {
                check(VpnRuntime.state.value.phase!=Phase.ERROR) { VpnRuntime.state.value.message }
                delay(300)
            }
        }
    }
    private fun otherAppRequest(url: String): Int {
        val latch=CountDownLatch(1);var code=-2
        val receiver=object: BroadcastReceiver() { override fun onReceive(context: Context?,intent: Intent?) {code=intent?.getIntExtra("code",-2) ?: -2;latch.countDown()} }
        app.registerReceiver(receiver,IntentFilter("app.phantom.TEST_TRAFFIC"),Context.RECEIVER_EXPORTED)
        try {
            app.startActivity(Intent().setComponent(ComponentName("app.phantom.trafficprobe","app.phantom.trafficprobe.TrafficActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("url",url))
            assertTrue("Separate traffic app timed out",latch.await(20,TimeUnit.SECONDS))
            return code
        } finally {app.unregisterReceiver(receiver)}
    }
}
