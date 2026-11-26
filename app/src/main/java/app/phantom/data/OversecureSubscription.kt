package app.phantom.data

import app.phantom.core.Importer
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

internal object OversecureSubscription {
    private val hosts = listOf("oversub.cloud", "overbanana.com")
    private val tokenPattern = Regex("[A-Za-z0-9_-]{1,256}")
    private const val MAX_ENVELOPE = Importer.MAX_BYTES * 4 / 3 + 4096
    private val x25519PublicKeyPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()

    class CryptoUnavailable : Exception()
    data class Payload(val text: String, val host: String)

    fun supports(url: String): Boolean = providerLink(url) != null

    suspend fun fetch(url: String, preferredHost: String): Payload {
        val link = requireNotNull(providerLink(url))
        val algorithm = try {
            listOf("XDH", "X25519").first { name -> runCatching {
                KeyPairGenerator.getInstance(name)
                KeyFactory.getInstance(name)
                KeyAgreement.getInstance(name)
            }.isSuccess }
        } catch (_: NoSuchElementException) {
            throw CryptoUnavailable()
        }
        val keys = KeyPairGenerator.getInstance(algorithm).generateKeyPair()
        val public = keys.public.encoded.takeLast(32).toByteArray()
        val body = JSONObject().put("token", link.pathSegments.single())
            .put("client_pub_key", Base64.getEncoder().encodeToString(public)).put("format", "json")
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val candidates = (listOf(preferredHost, link.host) + hosts).filter { it in hosts }.distinct()
        var failure: Exception = IOException("Ошибка загрузки подписки")
        for (host in candidates) {
            coroutineContext.ensureActive()
            try {
                val request = Request.Builder().url("https://$host/fetch").header("User-Agent", "Happ/3.1.5").post(body).build()
                val envelope = client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "Подписка: HTTP ${response.code}" }
                    val bytes = response.body?.byteStream()?.use { it.readBounded(MAX_ENVELOPE + 1) }
                        ?: error("Пустой HTTP-ответ")
                    require(bytes.size <= MAX_ENVELOPE) { "Подписка больше 8 МБ" }
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                val decoder = Base64.getDecoder()
                val publicKey = decoder.decode(envelope.getString("server_pub_key"))
                val nonce = decoder.decode(envelope.getString("nonce"))
                val ciphertext = decoder.decode(envelope.getString("encrypted_payload"))
                require(publicKey.size == 32 && nonce.size == 12 && ciphertext.size in 16..Importer.MAX_BYTES + 16) {
                    "Некорректный ответ подписки"
                }
                val peer = KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(x25519PublicKeyPrefix + publicKey))
                val sharedSecret = KeyAgreement.getInstance(algorithm).run {
                    init(keys.private)
                    doPhase(peer, true)
                    generateSecret()
                }
                val decoded = try {
                    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "ChaCha20"), IvParameterSpec(nonce))
                    cipher.doFinal(ciphertext)
                } finally {
                    sharedSecret.fill(0)
                }
                try {
                    return Payload(decoded.toString(Charsets.UTF_8), host)
                } finally {
                    decoded.fill(0)
                }
            } catch (_: NoSuchAlgorithmException) {
                throw CryptoUnavailable()
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure
    }

    private fun providerLink(url: String) = url.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.host in hosts && it.query == null && it.fragment == null &&
            it.username.isEmpty() && it.password.isEmpty() && it.pathSegments.singleOrNull()?.matches(tokenPattern) == true
    }
}
