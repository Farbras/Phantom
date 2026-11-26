package app.phantom

import app.phantom.core.Prober
import app.phantom.data.Server
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ProxyLatencyTest {
    @Test fun legacyLatencyIsInvalidatedWithoutChangingTheProfile() {
        val server = Server(id = "saved", name = "Test", config = "{}", favorite = true, latency = 1500, testedAt = 100)
        val legacy = server.json().apply { remove("latencyVersion") }
        assertEquals(server.copy(latency = null, testedAt = 0), Server.from(legacy))
        assertEquals(server, Server.from(server.json()))
    }

    @Test fun defaultExcludesWarmupAndReusesTheConnection() = runBlocking {
        val requests = AtomicInteger()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            server.accept().use { connection ->
                connection.soTimeout = 5_000
                val input = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                val output = connection.getOutputStream()
                repeat(2) { index ->
                    if (!readHeaders(input)) return@thread
                    requests.incrementAndGet()
                    if (index == 0) Thread.sleep(400)
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 512\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(ByteArray(512))
                    output.flush()
                }
            }
        }
        val client = OkHttpClient()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/ping").build()
            val start = System.nanoTime()
            val latency = Prober.measureProxyLatency(client, request, 5, "default", false)
            val total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(2, requests.get())
            assertTrue(total - latency >= 350)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
            worker.join(5_000)
        }
    }

    @Test fun repeatedColdConnectionsDoNotProduceAWarmLatency() = runBlocking {
        val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            repeat(3) {
                server.accept().use { connection ->
                    connection.soTimeout = 5_000
                    if (!readHeaders(connection.getInputStream().bufferedReader(Charsets.US_ASCII))) return@thread
                    connection.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
                    )
                }
            }
        }
        val client = OkHttpClient()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/ping").build()
            val failure = runCatching { Prober.measureProxyLatency(client, request, 5, "default", false) }.exceptionOrNull()
            assertEquals("Сервер закрывает соединение до повторного замера", failure?.message)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
            worker.join(5_000)
        }
    }

    private fun readHeaders(input: BufferedReader): Boolean {
        while (true) {
            val line = input.readLine() ?: return false
            if (line.isEmpty()) return true
        }
    }
}
