package app.phantom.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir,"catalog.aes"))
    private val lock = Mutex()
    private val mutable = MutableStateFlow(Catalog())
    val state = mutable.asStateFlow()
    private fun key(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("phantom-v1",null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("phantom-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        if(file.baseFile.exists()) {
            val bytes=file.openRead().use { it.readBytes() }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            mutable.value=Catalog.from(JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)))
        }
    } }
    suspend fun update(change: (Catalog)->Catalog) = withContext(Dispatchers.IO) { lock.withLock {
        val next=change(mutable.value)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val bytes=cipher.iv+cipher.doFinal(next.json().toString().toByteArray())
        val out=file.startWrite()
        try { out.write(bytes);file.finishWrite(out);mutable.value=next } catch(e: Exception) { file.failWrite(out);throw e }
    } }
    suspend fun setting(k: String,v: String) = update { it.copy(settings=it.settings+(k to v)) }
}
