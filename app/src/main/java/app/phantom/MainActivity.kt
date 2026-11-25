package app.phantom

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import app.phantom.ui.PhantomRoot
import app.phantom.data.readBounded
import app.phantom.data.safeError
import app.phantom.vpn.PhantomVpnService
import app.phantom.vpn.VpnAction
import app.phantom.vpn.VpnRuntime
import app.phantom.vpn.VpnStep
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity: ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)
    private var exportText=""
    private var externalMessage by mutableStateOf("")
    private var vpnRequest: Long?=null
    private val permission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
        if(result.resultCode==RESULT_OK) startVpn()
        else failVpnRequest(VpnStep.PERMISSION_DENIED,"Разрешение на VPN отклонено")
    }
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpnConsent() }
    private val scanner=registerForActivityResult(ScanContract()) { if(it.contents!=null) incoming=it.contents }
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { input->
                val bytes=input.readBounded(8*1024*1024+1)
                require(bytes.size<=8*1024*1024) { "Файл больше 8 МБ" };String(bytes,Charsets.UTF_8)
            } ?: error("Не удалось открыть файл") } }.onSuccess { incoming=it }.onFailure { externalMessage=app.phantom.data.safeError(it) }
        }
    }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri->
        if(uri!=null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri,"wt")?.use { it.write(exportText.toByteArray()) } ?: error("Не удалось записать файл") } }
                .onSuccess { externalMessage="Конфигурация экспортирована" }.onFailure { externalMessage=app.phantom.data.safeError(it) }
            exportText=""
        } else exportText=""
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getLong("vpnRequest",0L)?.takeIf { it!=0L }?.let { saved ->
            vpnRequest=saved.takeIf { it==VpnRuntime.pendingConnectId() }
                ?: if(VpnRuntime.operations.value.isEmpty()) VpnRuntime.request(VpnAction.CONNECT) else null
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        incoming=intent.dataString
        setContent {
            PhantomRoot(application as PhantomApp,incoming,{ incoming=null },externalMessage,{ externalMessage="" },
                onConnect=::requestVpn,onFile={ importFile.launch(arrayOf("*/*")) },
                onQr={ scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Отсканируйте конфигурацию или URL подписки").setBeepEnabled(false).setOrientationLocked(false)) },
                onClipboard={ (getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()).also { if(it.isNullOrBlank()) externalMessage="Буфер обмена пуст" else incoming=it } },
                onExport={ text->exportText=text;exportFile.launch("phantom-config.json") })
        }
        handleConnectIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        vpnRequest?.let { outState.putLong("vpnRequest",it) }
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);incoming=intent.dataString;handleConnectIntent(intent) }
    private fun handleConnectIntent(intent: Intent) {
        if(intent.getBooleanExtra("connect",false)) {
            intent.removeExtra("connect")
            lifecycleScope.launch { (application as PhantomApp).ready.await();requestVpn() }
        }
    }
    private fun startVpn() {
        if(vpnRequest==null || vpnRequest!=VpnRuntime.pendingConnectId()) return
        runCatching { PhantomVpnService.connect(this) }
            .onFailure { failVpnRequest(VpnStep.CONNECT_FAILED,safeError(it)) }
        vpnRequest=null
    }
    private fun requestVpn() {
        val operation=VpnRuntime.request(VpnAction.CONNECT) ?: return
        vpnRequest=operation
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VpnRuntime.step(operation,VpnStep.VPN_PERMISSION)
            runCatching { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { failVpnRequest(VpnStep.CONNECT_FAILED,safeError(it)) }
        } else requestVpnConsent()
    }
    private fun requestVpnConsent() {
        val operation=vpnRequest ?: return
        if(operation!=VpnRuntime.pendingConnectId()) return
        runCatching {
            val prepare=VpnService.prepare(this)
            if(prepare!=null) {
                VpnRuntime.step(operation,VpnStep.VPN_PERMISSION)
                permission.launch(prepare)
            } else startVpn()
        }.onFailure { failVpnRequest(VpnStep.CONNECT_FAILED,safeError(it)) }
    }

    private fun failVpnRequest(step: VpnStep,message: String) {
        val operation=vpnRequest ?: return
        vpnRequest=null
        if(operation!=VpnRuntime.pendingConnectId()) return
        VpnRuntime.step(operation,step)
        externalMessage=message
    }
}
