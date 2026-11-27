package app.phantom.core

import app.phantom.data.readBounded
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class XrayLoopback {
    private val user = "phantom"
    private val password = UUID.randomUUID().toString()

    fun accounts() = JSONArray().put(JSONObject().put("user", user).put("pass", password))

    suspend fun check(url: String, timeoutMs: Int): Long {
        val target = url.toHttpUrl()
        require(target.isHttps && target.username.isEmpty() && target.password.isEmpty()) { "Проверка требует HTTPS URL" }
        val started = System.nanoTime()
        throughSocks(target.host, target.port, timeoutMs) { socket ->
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(socket, target.host, target.port, true) as SSLSocket).use { tls ->
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                val host = if (':' in target.host) "[${target.host}]" else target.host
                val authority = if (target.port == 443) host else "$host:${target.port}"
                val path = target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: "")
                sendHttp(tls, "HEAD", path, authority)
                val status = headerLine(BufferedInputStream(tls.inputStream))
                if (status.substringAfter(' ').substringBefore(' ').toIntOrNull() !in 100..599) {
                    throw IOException("Нет ответа через VPN")
                }
            }
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    data class Counters(val tx: Long, val rx: Long, val usedOutbounds: Set<String>)

    suspend fun counters(): Counters = throughSocks(ConfigBuilder.METRICS_HOST, 80, 2000) { socket ->
        sendHttp(socket, "GET", "/debug/vars", ConfigBuilder.METRICS_HOST)
        val input = BufferedInputStream(socket.inputStream)
        check(headerLine(input).startsWith("HTTP/1.1 200 ")) { "Счётчики Xray недоступны" }
        var chunked = false
        repeat(32) {
            val line = headerLine(input)
            if (line.isEmpty()) {
                val body = if (chunked) readChunks(input) else input.readBounded(512 * 1024)
                val stats = JSONObject(body.toString(Charsets.UTF_8)).getJSONObject("stats")
                val outbounds = stats.optJSONObject("outbound") ?: JSONObject()
                val counters = if (outbounds.length() > 0) {
                    outbounds.keys().asSequence().mapNotNull(outbounds::optJSONObject).toList()
                } else listOfNotNull(stats.optJSONObject("inbound")?.optJSONObject(ConfigBuilder.TUN_TAG))
                val usedOutbounds = outbounds.keys().asSequence().filter { tag ->
                    val counter = outbounds.optJSONObject(tag)
                    counter != null && (counter.optLong("uplink") > 0 || counter.optLong("downlink") > 0)
                }.toSet()
                return@throughSocks Counters(
                    counters.sumOf { it.optLong("uplink") },
                    counters.sumOf { it.optLong("downlink") },
                    usedOutbounds,
                )
            }
            if (line.equals("Transfer-Encoding: chunked", ignoreCase = true)) chunked = true
        }
        throw IOException("Некорректный ответ счётчиков Xray")
    }

    private suspend fun <T> throughSocks(host: String, port: Int, timeoutMs: Int, request: (Socket) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val socket = Socket()
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            continuation.resumeWith(runCatching {
                socket.use {
                    socket.connect(InetSocketAddress("127.0.0.1", ConfigBuilder.TUN_PORT), timeoutMs)
                    socket.soTimeout = timeoutMs
                    val output = socket.getOutputStream()
                    val input = DataInputStream(socket.getInputStream())
                    output.write(byteArrayOf(5, 1, 2))
                    if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 2) throw IOException("SOCKS недоступен")
                    val username = user.toByteArray(Charsets.US_ASCII)
                    val secret = password.toByteArray(Charsets.US_ASCII)
                    output.write(byteArrayOf(1, username.size.toByte()) + username + byteArrayOf(secret.size.toByte()) + secret)
                    if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS отклонил проверку")
                    val domain = host.toByteArray(Charsets.US_ASCII)
                    val address = if (':' in host) byteArrayOf(4) + java.net.InetAddress.getByName(host).address
                    else byteArrayOf(3, domain.size.toByte()) + domain
                    output.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port shr 8).toByte(), port.toByte()))
                    if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("SOCKS не подключился")
                    input.readUnsignedByte()
                    val addressSize = when (input.readUnsignedByte()) {
                        1 -> 4
                        3 -> input.readUnsignedByte()
                        4 -> 16
                        else -> throw IOException("Некорректный ответ SOCKS")
                    }
                    input.readFully(ByteArray(addressSize + 2))
                    request(socket)
                }
            })
        }

    private fun sendHttp(socket: Socket, method: String, path: String, host: String) {
        socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun headerLine(input: BufferedInputStream): String {
        val line = StringBuilder()
        repeat(4096) {
            val ch = input.read()
            if (ch < 0) throw IOException("Неполный HTTP-ответ")
            if (ch == 10) return line.toString().trimEnd('\r')
            line.append(ch.toChar())
        }
        throw IOException("Слишком длинный HTTP-заголовок")
    }

    private fun readChunks(input: BufferedInputStream): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        val data = DataInputStream(input)
        while (true) {
            val size = headerLine(input).substringBefore(';').toIntOrNull(16) ?: throw IOException("Некорректный HTTP-ответ")
            if (size == 0) return body.toByteArray()
            if (size !in 1..(512 * 1024 - body.size())) throw IOException("Слишком большой ответ счётчиков")
            val chunk = ByteArray(size)
            data.readFully(chunk)
            body.write(chunk)
            if (headerLine(input).isNotEmpty()) throw IOException("Некорректный HTTP-ответ")
        }
    }
}
