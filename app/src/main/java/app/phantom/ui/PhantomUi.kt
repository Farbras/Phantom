@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.phantom.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.phantom.PhantomApp
import app.phantom.core.*
import app.phantom.data.*
import app.phantom.vpn.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

val Background=Color(0xFF050407)
val Panel=Color(0xFF141019)
val Muted=Color(0xFF9B94A8)
val Purple=Color(0xFFAD81FF)
val Green=Color(0xFFA2E9CF)

fun bytes(value: Long): String {
    val n=value.toDouble()
    return when { value>=1L.shl(30) -> "%.2f ГБ".format(n/(1L.shl(30))); value>=1L.shl(20) -> "%.1f МБ".format(n/(1L.shl(20)));value>=1024 -> "%.1f КБ".format(n/1024);else -> "$value Б" }
}
fun date(ms: Long) = if(ms<=0) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,java.util.Locale("ru")).format(Date(ms))
fun duration(ms: Long): String { val s=(ms/1000).coerceAtLeast(0);return "%02d:%02d:%02d".format(s/3600,s/60%60,s%60) }

data class Editor(val title: String,val text: String,val hint: String="",val name: String="",val onSave: suspend (String,String)->Unit)
data class Confirm(val title: String,val body: String,val action: suspend ()->Unit)

@Composable
fun PhantomRoot(app: PhantomApp,incoming: String?,consumeIncoming: ()->Unit,externalMessage: String,consumeMessage: ()->Unit,
    onConnect: ()->Unit,onFile: ()->Unit,onQr: ()->Unit,onClipboard: ()->Unit,onExport: (String)->Unit) {
    val catalog by app.vault.state.collectAsStateWithLifecycle()
    val vpn by VpnRuntime.state.collectAsStateWithLifecycle()
    val logs by VpnRuntime.logs.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf("home") }
    var busy by remember { mutableStateOf("") }
    var workJob by remember { mutableStateOf<Job?>(null) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var editSubscription by remember { mutableStateOf<Subscription?>(null) }
    var showServers by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    fun work(label: String,action: suspend ()->Unit) {
        if(busy.isNotBlank()) return
        busy=label
        workJob=scope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch(e: CancellationException) { throw e }
            catch(e: Throwable) { snackbar.showSnackbar(safeError(e)) }
            finally { busy="" }
        }
    }
    val prober = app.prober
    val checking by prober.checking.collectAsStateWithLifecycle()
    val servers = remember(catalog.subscriptions) { catalog.subscriptions.flatMap { it.servers } }
    fun checkPing(targets: List<Server>) {
        scope.launch {
            try { prober.test(targets) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar(safeError(e)) }
        }
    }
    fun select(server: Server) {
        if(server.issue.isNotBlank()) { scope.launch { snackbar.showSnackbar(server.issue) };return }
        work("Выбор сервера") {
            app.vault.update { it.copy(selected=server.id) }
            if(vpn.phase!=Phase.OFF) PhantomVpnService.connect(app)
        }
        showServers=false
    }
    fun favorite(server: Server) { work("Сохранение") { app.vault.update { c->c.copy(subscriptions=c.subscriptions.map { s->s.copy(servers=s.servers.map { if(it.id==server.id) it.copy(favorite=!it.favorite) else it }) }) } } }
    fun editServer(server: Server?) {
        editor=Editor(if(server==null) "Новый сервер" else "Редактировать сервер",server?.config ?: "", "Ссылка VLESS/VMess/Trojan/SS/Hysteria2 или полный Xray JSON",server?.name.orEmpty()) { name,text->
            val nodes=Importer.parse(text)
            require(nodes.size==1) { "Редактор одного сервера принимает одну конфигурацию" }
            val new=nodes.first().copy(name=name.ifBlank { nodes.first().name })
            if(server==null) app.vault.update { c->
                val manual=c.subscriptions.firstOrNull { it.id=="manual" } ?: Subscription(id="manual",name="Мои серверы")
                c.copy(subscriptions=c.subscriptions.filter { it.id!="manual" }+manual.copy(servers=manual.servers+new),selected=c.selected.ifBlank { new.id })
            } else app.vault.update { c->c.copy(subscriptions=c.subscriptions.map { s->s.copy(servers=s.servers.map { if(it.id==server.id) new.copy(id=server.id,favorite=it.favorite) else it }) }) }
            editor=null
        }
    }
    LaunchedEffect(Unit) {
        try {
            app.ready.await();ready=true
            val c=app.vault.state.value
            withContext(Dispatchers.IO) {
                if(c.flag("updateOnOpen")) app.subscriptions.refreshAll()
                if(c.flag("pingOnOpen") && vpn.phase==Phase.OFF) prober.test(app.vault.state.value.subscriptions.flatMap { it.servers })
            }
            if(c.flag("connectOnOpen") && vpn.phase==Phase.OFF && c.selectedServer()!=null) onConnect()
        } catch(e: Exception) { loadError=app.startupError ?: safeError(e);snackbar.showSnackbar(loadError!!) }
    }
    LaunchedEffect(incoming,ready) { if(ready && incoming!=null) {
        val text=incoming;consumeIncoming()
        // Deep links are displayed for review; merely opening a link does not alter stored data.
        editor=Editor("Импорт конфигурации",text,"Проверьте источник перед добавлением") { name,body->app.subscriptions.importText(body,name.ifBlank { "Импорт" });editor=null }
    } }
    LaunchedEffect(externalMessage) { if(externalMessage.isNotBlank()) { snackbar.showSnackbar(externalMessage);consumeMessage() } }
    BackHandler(route!="home") { route=if(route in listOf("interface","subprefs","ping","routing","apps","inbounds","tunnel","stats","logs","help","about","licenses")) "settings" else "home" }
    val accent=when(catalog.setting("accent")) { "lavender"->Color(0xFFCBB9FF);"deep"->Color(0xFF9560EE);else->Purple }
    val colors=darkColorScheme(primary=accent,secondary=accent,background=Background,surface=Panel,onBackground=Color.White,onSurface=Color.White,onSurfaceVariant=Muted)
    val density=LocalDensity.current
    MaterialTheme(colorScheme=colors) {
        CompositionLocalProvider(LocalDensity provides Density(density.density,density.fontScale*(catalog.setting("textScale").toFloatOrNull() ?: 1f))) {
            Scaffold(containerColor=Background,snackbarHost={ SnackbarHost(snackbar) },topBar={
                if(route!="home") TopAppBar(title={ Text(screenTitle(route),fontWeight=FontWeight.SemiBold) },navigationIcon={ IconButton(onClick={ route="settings".takeIf { route!="settings" } ?: "home" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад") } },colors=TopAppBarDefaults.topAppBarColors(containerColor=Background))
            }) { padding->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    if(!ready) Column(Modifier.align(Alignment.Center).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        if(loadError==null) CircularProgressIndicator()
                        Text(loadError ?: "Открытие защищённого хранилища…",Modifier.padding(top=20.dp))
                    } else when(route) {
                        "home" -> Home(catalog,vpn,busy,route=="home",{ route="settings" },{ addMenu=true },{ showServers=true },{ action ->
                            when(action) {
                                VpnAction.CONNECT -> onConnect()
                                VpnAction.DISCONNECT -> VpnRuntime.request(action)?.let { operation ->
                                    runCatching { PhantomVpnService.disconnect(app) }.onFailure {
                                        VpnRuntime.step(operation,VpnStep.DISCONNECT_FAILED)
                                    }
                                }
                            }
                        },checking=checking,onPing={ checkPing(listOf(it)) },
                            onPingAll={ checkPing(servers) },canPingAll=servers.any { it.issue.isBlank() },
                            onSubAction={ sub,action->when(action) {
                            "expand" -> work("Сохранение") { app.vault.update { c->c.copy(subscriptions=c.subscriptions.map { if(it.id==sub.id) it.copy(expanded=!it.expanded) else it }) } }
                            "refresh" -> work("Обновление подписки") { app.subscriptions.refresh(sub.id) }
                            "ping" -> checkPing(sub.servers)
                            "edit" -> editSubscription=sub
                            "support","website" -> {
                                val uri=android.net.Uri.parse(sub.metadata[if(action=="support") "support-url" else "profile-web-page-url"].orEmpty())
                                if(uri.scheme in listOf("https","tg")) runCatching {app.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
                                    .onFailure {scope.launch {snackbar.showSnackbar("Не найдено приложение для открытия ссылки")}}
                            }
                            "pin" -> work("Сохранение") { app.vault.update { c->c.copy(subscriptions=c.subscriptions.map { if(it.id==sub.id) it.copy(pinned=!it.pinned) else it }) } }
                            "export" -> { val export=JSONArray(sub.servers.filter { it.issue.isBlank() }.map { JSONObject(it.config).apply { remove("_phantomProviderJson") } });onExport(export.toString(2)) }
                            "info" -> editor=Editor("Метаданные подписки",sub.metadata.filterKeys { it !in listOf("support-url","profile-web-page-url") }.entries.joinToString("\n") { "${it.key}: ${it.value}" },"Данные предоставлены сервером") { _,_->editor=null }
                            "delete" -> confirm=Confirm("Удалить подписку?","Серверы этой подписки будут удалены из хранилища.") {
                                if(sub.servers.any { it.id==vpn.serverId } && vpn.phase!=Phase.OFF) PhantomVpnService.disconnect(app)
                                app.vault.update { c->c.copy(subscriptions=c.subscriptions.filter { it.id!=sub.id },selected=if(sub.servers.any { it.id==c.selected }) "" else c.selected) }
                            }
                        } },onSelect=::select,onFavorite=::favorite,onEditServer=::editServer)
                        "settings" -> SettingsMenu(vpn,{ route=it },{ confirm=Confirm("Сбросить настройки?","Подписки и серверы сохранятся. VPN будет отключён.") {
                            PhantomVpnService.disconnect(app);app.vault.update { it.copy(settings=Defaults.values,routeProfiles=mapOf("default" to "[]")) };app.subscriptions.schedule()
                        } })
                        "interface","subprefs","ping","inbounds","tunnel" -> PreferenceScreen(route,catalog,{ key,value->work("Сохранение настроек") {
                            validatePreference(key,value);app.vault.setting(key,value);if(key=="logLevel") VpnRuntime.logLevel=value;if(key in listOf("autoUpdate","updateHours")) app.subscriptions.schedule()
                        } },{ app.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                        "routing" -> RoutingScreen(catalog,{ name->work("Выбор профиля") { app.vault.setting("routeProfile",name) } },{ name->
                            editor=Editor("Правила маршрутизации",catalog.routeProfiles[name] ?: "[]","Массив {action: Proxy/Direct/Block, domain: [...] / ip: [...]}. Действуют после переподключения.",name) { newName,text->
                                val rules=RoutingImport.parse(text)
                                require(newName.isNotBlank()) { "Введите имя профиля" }
                                app.vault.update { it.copy(routeProfiles=it.routeProfiles+(newName to rules.toString()),settings=it.settings+("routeProfile" to newName)) };editor=null
                            }
                        },{ name->onExport(catalog.routeProfiles[name] ?: "[]") },{ name->work("Удаление профиля") { app.vault.update { c->c.copy(routeProfiles=c.routeProfiles-name,settings=c.settings+("routeProfile" to "default")) } } })
                        "apps" -> AppsScreen(app,catalog,{ mode,packages->work("Сохранение приложений") { app.vault.update { it.copy(settings=it.settings+mapOf("appMode" to mode,"packages" to packages.joinToString("\n"))) } } })
                        "logs" -> LogsScreen(logs,{ VpnRuntime.clearLogs() })
                        "stats" -> StatisticsScreen(catalog,vpn)
                        "about","help","licenses" -> InfoScreen(route,app)
                    }
                    if(busy.isNotBlank()) Surface(Modifier.align(Alignment.BottomCenter).padding(20.dp),color=Panel,shape=RoundedCornerShape(20.dp),tonalElevation=8.dp) {
                        Row(Modifier.padding(start=16.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text(busy,Modifier.padding(start=12.dp),fontSize=13.sp);IconButton(onClick={busy="Отмена…";workJob?.cancel()}) {Icon(Icons.Rounded.Close,"Отменить операцию",Modifier.size(17.dp))} }
                    }
                }
            }
            if(addMenu) ModalBottomSheet(onDismissRequest={ addMenu=false },containerColor=Panel) {
                Text("Добавить в Phantom",Modifier.padding(horizontal=24.dp,vertical=12.dp),fontSize=23.sp,fontWeight=FontWeight.SemiBold)
                ActionRow(Icons.Rounded.Link,"URL подписки") { addMenu=false;editor=Editor("Добавить подписку","","HTTPS URL. Не публикуйте персональные ссылки.") { name,text->app.subscriptions.importText(text,name.ifBlank { "Подписка" });editor=null } }
                ActionRow(Icons.Rounded.ContentPaste,"Из буфера обмена") { addMenu=false;onClipboard() }
                ActionRow(Icons.Rounded.QrCodeScanner,"Сканировать QR-код") { addMenu=false;onQr() }
                ActionRow(Icons.Rounded.FileOpen,"Импорт из файла") { addMenu=false;onFile() }
                ActionRow(Icons.Rounded.Edit,"Добавить сервер вручную") { addMenu=false;editServer(null) }
                Spacer(Modifier.height(24.dp))
            }
            if(showServers) ModalBottomSheet(onDismissRequest={ showServers=false },containerColor=Panel) {
                Column(Modifier.fillMaxHeight(.88f).padding(horizontal=20.dp)) {
                    Text("Выберите маршрут",fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(vertical=16.dp),singleLine=true,label={Text("Поиск сервера")},leadingIcon={Icon(Icons.Rounded.Search,null)},shape=RoundedCornerShape(18.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        FilterChip(favorites,{favorites=!favorites},label={Text("Избранное")},leadingIcon={Icon(Icons.Rounded.Star,null,Modifier.size(16.dp))})
                        Spacer(Modifier.weight(1f))
                        PingButton("Проверить все", checking.isEmpty() && servers.any { it.issue.isBlank() }) { checkPing(servers) }
                    }
                    LazyColumn { items(visibleServers(servers,catalog,query,favorites),key={it.id}) { server->
                        ServerRow(server,catalog.selected==server.id,server.id in checking,{select(server)},{favorite(server)},{editServer(server)})
                    } }
                }
            }
            editor?.let { e->EditorDialog(e,{editor=null}) { name,text->work("Обработка конфигурации") { e.onSave(name,text) } } }
            confirm?.let { c->AlertDialog(onDismissRequest={confirm=null},title={Text(c.title)},text={Text(c.body)},confirmButton={TextButton(onClick={confirm=null;work("Выполнение") { c.action() }}) {Text("Подтвердить")}},dismissButton={TextButton(onClick={confirm=null}) {Text("Отмена")}}) }
            editSubscription?.let { sub->SubscriptionEditor(sub,{editSubscription=null}) { name,url,ua->work("Сохранение подписки") {
                require(url.isBlank() || (android.net.Uri.parse(url).scheme=="https" && !android.net.Uri.parse(url).host.isNullOrBlank())) { "Укажите HTTPS URL" }
                require('\n' !in ua && '\r' !in ua && ua.length<=256) { "Некорректный User-Agent" }
                app.vault.update { c->c.copy(subscriptions=c.subscriptions.map { if(it.id==sub.id) it.copy(name=name.ifBlank { it.name },url=url,userAgent=ua) else it }) };editSubscription=null
            } } }
        }
    }
}

fun visibleServers(servers: List<Server>,c: Catalog,query: String="",favorite: Boolean=false): List<Server> {
    val list=servers.distinctBy { it.id }.filter { (!favorite || it.favorite) && it.name.contains(query,true) && it.name.contains(c.setting("filter"),true) }
    return when(c.setting("sort")) { "latency"->list.sortedBy { it.latency?.takeIf { n->n>=0 } ?: Long.MAX_VALUE };"alphabet"->list.sortedBy { it.name.lowercase() };else->list }
}

@Composable
internal fun Home(c: Catalog,vpn: VpnSnapshot,busy: String,visible: Boolean,onSettings: ()->Unit,onAdd: ()->Unit,onServers: ()->Unit,onPower: (VpnAction)->Unit,
    checking: Set<String>,onPing: (Server)->Unit,onPingAll: ()->Unit,canPingAll: Boolean,
    onSubAction: (Subscription,String)->Unit,onSelect: (Server)->Unit,onFavorite: (Server)->Unit,onEditServer: (Server?)->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var interactive by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isInteractive) }
    DisposableEffect(context) {
        val receiver=object: BroadcastReceiver() { override fun onReceive(c: Context?,intent: Intent?) { interactive=context.getSystemService(PowerManager::class.java).isInteractive } }
        ContextCompat.registerReceiver(context,receiver,IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT) },ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val animate=visible && interactive && lifecycle.value.isAtLeast(Lifecycle.State.RESUMED) && c.flag("animations")
    val rain=animate && vpn.tunnel
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(vpn.tunnel,lifecycle.value) { if(vpn.tunnel && lifecycle.value.isAtLeast(Lifecycle.State.RESUMED)) while(true) { now=SystemClock.elapsedRealtime();delay(1000) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Leave room for Home actions, the title bar, and a toolbar that wraps on narrow screens.
        val transcriptMaxHeight=(maxHeight-216.dp).coerceIn(112.dp,354.dp)
        MatrixRain(rain,Modifier.fillMaxSize())
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp,4.dp,20.dp,40.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onSettings) { Icon(Icons.Rounded.Tune,"Настройки") }
                    IconButton(onClick=onAdd) { Icon(Icons.Rounded.Add,"Добавить",tint=MaterialTheme.colorScheme.primary) }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                    VpnTerminal(
                        phase=vpn.phase,
                        routeUnconfirmed=vpn.routeUnconfirmed,
                        uptime=if(vpn.tunnel && vpn.connectedAt>0) duration(now-vpn.connectedAt) else null,
                        enabled=busy.isBlank(),
                        animate=animate,
                        characterDelay=c.number("terminalCharacterDelay",40).coerceIn(20,80).toLong(),
                        transcriptMaxHeight=transcriptMaxHeight,
                        onPower=onPower,
                    )
                }
            }
            item {
                val selected = c.selectedServer()
                SurfaceCard {
                    Row(Modifier.fillMaxWidth().clickable(onClick=onServers),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Public,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp))
                        Column(Modifier.padding(horizontal=14.dp).weight(1f)) { Text("ВЫБРАННЫЙ СЕРВЕР",fontSize=9.sp,letterSpacing=2.sp,color=Muted);Text(selected?.name ?: "Выбрать сервер",Modifier.padding(top=5.dp),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium);Text(selected?.summary() ?: "Добавьте подписку или конфигурацию",fontSize=10.sp,color=Muted,maxLines=1) }
                        if (selected != null) PingResult(selected, selected.id in checking)
                        Icon(Icons.Rounded.ExpandMore,null,tint=Muted)
                    }
                    if (selected != null) Box(Modifier.align(Alignment.End).padding(top=4.dp)) {
                        PingButton("Проверить пинг", checking.isEmpty() && selected.issue.isBlank()) { onPing(selected) }
                    }
                }
            }
            item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { SpeedCard("ЗАГРУЗКА",vpn.rxSpeed,vpn.rx,Icons.Rounded.South,Modifier.weight(1f));SpeedCard("ОТДАЧА",vpn.txSpeed,vpn.tx,Icons.Rounded.North,Modifier.weight(1f)) } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Мои подписки",fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                PingButton("Проверить все", checking.isEmpty() && canPingAll, onPingAll)
            } }
            if(c.subscriptions.isEmpty()) item { SurfaceCard {
                Column(Modifier.fillMaxWidth().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Rounded.Hub,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(34.dp));Text("Создайте свой маршрут",Modifier.padding(top=18.dp),fontSize=19.sp);Text("Импортируйте ссылку, QR-код или JSON.\nДанные останутся на вашем устройстве.",Modifier.padding(vertical=12.dp),fontSize=12.sp,color=Muted);Button(onClick=onAdd) {Text("Добавить подписку")} }
            } }
            items(c.subscriptions.sortedByDescending { it.pinned },key={it.id}) { sub->SubscriptionCard(sub,c,checking,onSubAction,onSelect,onFavorite,onEditServer) }
            item { Text("XRAY-CORE 26.11.9  /  PHANTOM 1.0.2",Modifier.fillMaxWidth().padding(top=10.dp),fontFamily=FontFamily.Monospace,fontSize=9.sp,color=Muted.copy(alpha=.6f),letterSpacing=1.sp) }
        }
    }
}

@Composable fun SurfaceCard(modifier: Modifier=Modifier,onClick: (()->Unit)?=null,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier),color=Panel.copy(alpha=.97f),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Color.White.copy(alpha=.06f))) { Column(Modifier.padding(18.dp),content=content) }
}
@Composable fun SpeedCard(label: String,speed: Long,total: Long,icon: ImageVector,modifier: Modifier) {
    SurfaceCard(modifier) { Row(verticalAlignment=Alignment.CenterVertically) {Icon(icon,null,Modifier.size(15.dp),tint=MaterialTheme.colorScheme.primary);Text(label,Modifier.padding(start=6.dp),fontSize=9.sp,color=Muted,letterSpacing=1.sp)};Text("${bytes(speed)}/с",Modifier.padding(top=10.dp),fontSize=20.sp,fontWeight=FontWeight.Medium);Text(bytes(total),Modifier.padding(top=4.dp),fontSize=11.sp,color=Muted) }
}
@Composable fun SubscriptionCard(sub: Subscription,c: Catalog,checking: Set<String>,action: (Subscription,String)->Unit,onSelect: (Server)->Unit,onFavorite: (Server)->Unit,onEdit: (Server?)->Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(),color=Panel.copy(alpha=.98f),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Color.White.copy(alpha=.06f))) {
        Column {
            Row(Modifier.padding(start=16.dp,top=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={action(sub,"expand")}) {Icon(if(sub.expanded || !c.flag("collapsible")) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,"Раскрыть")}
                Column(Modifier.weight(1f)) {Text((if(sub.pinned) "⌖ " else "")+sub.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold);Text("${sub.servers.size} серверов · ${date(sub.updated)}",fontSize=10.sp,color=Muted)}
                Box { IconButton(onClick={menu=true}) {Icon(Icons.Rounded.MoreHoriz,"Действия")};DropdownMenu(menu,{menu=false}) {
                    listOf("refresh" to "Обновить","ping" to "Проверить серверы","edit" to "Редактировать","pin" to if(sub.pinned) "Открепить" else "Закрепить","export" to "Экспорт JSON","info" to "Метаданные","delete" to "Удалить").forEach { (key,label)->DropdownMenuItem(text={Text(label)},enabled=key!="ping" || (checking.isEmpty() && sub.servers.any { it.issue.isBlank() }),onClick={menu=false;action(sub,key)}) }
                    if(!sub.metadata["support-url"].isNullOrBlank()) DropdownMenuItem(text={Text("Поддержка провайдера")},onClick={menu=false;action(sub,"support")})
                    if(!sub.metadata["profile-web-page-url"].isNullOrBlank()) DropdownMenuItem(text={Text("Сайт подписки")},onClick={menu=false;action(sub,"website")})
                } }
            }
            val info=sub.metadata["subscription-userinfo"].orEmpty().split(';').mapNotNull { s->s.split('=',limit=2).takeIf { it.size==2 }?.let { it[0].trim() to it[1].trim().toLongOrNull() } }.toMap()
            if(info.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${bytes((info["upload"] ?: 0)+(info["download"] ?: 0))} / ${info["total"]?.takeIf { it>0 }?.let(::bytes) ?: "∞"}",fontSize=11.sp,color=MaterialTheme.colorScheme.primary)
                Text("До ${date((info["expire"] ?: 0)*1000).substringBefore(' ')}",fontSize=11.sp,color=Muted)
            }
            sub.metadata["announce"]?.let { Text(it,Modifier.padding(20.dp,0.dp,20.dp,12.dp),fontSize=11.sp,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis) }
            if(sub.error.isNotBlank()) Text(sub.error,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            AnimatedVisibility(sub.expanded || !c.flag("collapsible"),enter=expandVertically(tween(if(c.flag("animations")) 250 else 0))+fadeIn(),exit=shrinkVertically(tween(if(c.flag("animations")) 200 else 0))+fadeOut()) {
                Column(Modifier.padding(horizontal=12.dp)) { visibleServers(sub.servers,c).forEach { server->ServerRow(server,c.selected==server.id,server.id in checking,{onSelect(server)},{onFavorite(server)},{onEdit(server)}) };Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
@Composable
private fun PingButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    FilledTonalButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=accent.copy(alpha=.15f),contentColor=accent)) {
        Icon(Icons.Rounded.Speed,null,Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label,fontSize=13.sp)
    }
}

@Composable
private fun PingResult(server: Server, checking: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    if (checking) {
        CircularProgressIndicator(Modifier.size(16.dp).semantics { contentDescription = "Идёт проверка пинга" },color=accent,strokeWidth=2.dp)
    } else {
        val delay = server.latency
        val label = when {
            delay == null -> "—"
            delay >= 0 -> "$delay мс"
            server.latencyError == PROBE_TIMEOUT_LABEL -> PROBE_TIMEOUT_LABEL
            else -> "Ошибка"
        }
        Text(label,fontSize=11.sp,color=if (delay != null && delay >= 0) accent else Muted,maxLines=1)
    }
}

@Composable fun ServerRow(server: Server,selected: Boolean,checking: Boolean,onSelect: ()->Unit,onFavorite: ()->Unit,onEdit: ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=3.dp).background(if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.11f) else Color.Transparent,RoundedCornerShape(16.dp)).clickable(onClick=onSelect).padding(start=12.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(if(server.issue.isNotBlank()) Icons.Rounded.ErrorOutline else if(selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,null,tint=if(selected) MaterialTheme.colorScheme.primary else Muted,modifier=Modifier.size(19.dp))
        Column(Modifier.weight(1f).padding(start=10.dp,end=4.dp)) { Text(server.name,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(server.issue.ifBlank { server.description().ifBlank { server.summary() } },fontSize=9.sp,color=if(server.issue.isBlank()) Muted else MaterialTheme.colorScheme.error,maxLines=2);if(!checking && server.latencyError.isNotBlank() && server.latencyError!=PROBE_TIMEOUT_LABEL) Text(server.latencyError,fontSize=9.sp,color=Muted,maxLines=2) }
        PingResult(server,checking)
        IconButton(onClick=onFavorite,modifier=Modifier.size(35.dp)) {Icon(if(server.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,"Избранное",Modifier.size(17.dp),tint=if(server.favorite) MaterialTheme.colorScheme.primary else Muted)}
        IconButton(onClick=onEdit,modifier=Modifier.size(32.dp)) {Icon(Icons.Rounded.Edit,"Редактировать сервер",Modifier.size(14.dp),tint=Muted)}
    }
}
@Composable fun ActionRow(icon: ImageVector,label: String,onClick: ()->Unit) { ListItem(headlineContent={Text(label)},leadingContent={Icon(icon,null,tint=MaterialTheme.colorScheme.primary)},trailingContent={Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,tint=Muted)},modifier=Modifier.clickable(onClick=onClick),colors=ListItemDefaults.colors(containerColor=Color.Transparent)) }
@Composable fun EditorDialog(editor: Editor,onDismiss: ()->Unit,onSave: (String,String)->Unit) {
    var text by remember(editor) { mutableStateOf(editor.text) };var name by remember(editor) { mutableStateOf(editor.name) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(editor.title)},text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        if(editor.hint.isNotBlank()) Text(editor.hint,fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=12.dp))
        OutlinedTextField(name,{name=it},label={Text("Название / профиль")},singleLine=true)
        OutlinedTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=180.dp,max=350.dp).padding(top=12.dp),label={Text("Содержимое")},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp))
    } },confirmButton={TextButton(onClick={onSave(name,text)}) {Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss) {Text("Отмена")}})
}
@Composable fun SubscriptionEditor(sub: Subscription,onDismiss: ()->Unit,onSave: (String,String,String)->Unit) {
    var name by remember { mutableStateOf(sub.name) };var url by remember { mutableStateOf(sub.url) };var ua by remember { mutableStateOf(sub.userAgent) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Подписка")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name,{name=it},label={Text("Название")});OutlinedTextField(url,{url=it},label={Text("HTTPS URL")});OutlinedTextField(ua,{ua=it},label={Text("User-Agent этой подписки")})
        Text("Пустой User-Agent использует общую настройку. Некоторые провайдеры отдают составной JSON только для Happ.",fontSize=12.sp,color=Muted)
        TextButton(onClick={ua="Happ/5.5.0/android"}) {Text("Запрашивать формат Happ JSON")}
        Text("После сохранения обновите подписку. Это не добавляет закрытые технологии или идентификатор Happ.",fontSize=11.sp,color=Muted)
    }},confirmButton={TextButton(onClick={onSave(name,url,ua)}) {Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss) {Text("Отмена")}})
}
