@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.phantom.ui

import android.content.pm.PackageManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phantom.PhantomApp
import app.phantom.data.Catalog
import app.phantom.vpn.Phase
import app.phantom.vpn.VpnSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

fun screenTitle(route: String)=mapOf("settings" to "Настройки","interface" to "Оформление","subprefs" to "Подписки","ping" to "Проверка серверов","routing" to "Маршрутизация","apps" to "Приложения","inbounds" to "Локальные прокси","tunnel" to "Туннель и DNS","stats" to "Статистика","logs" to "Журнал","about" to "О Phantom","help" to "Справка","licenses" to "Лицензии")[route] ?: "Phantom"

@Composable fun SettingsMenu(vpn: VpnSnapshot,navigate: (String)->Unit,onReset: ()->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        if(vpn.phase!=Phase.OFF) item { Text("Изменения туннеля, маршрутов и прокси применятся после переподключения.",color=Muted,fontSize=12.sp) }
        item { SurfaceCard {
            ActionRow(Icons.Rounded.Palette,"Оформление") {navigate("interface")}
            ActionRow(Icons.Rounded.Subscriptions,"Подписки") {navigate("subprefs")}
            ActionRow(Icons.Rounded.Speed,"Проверка серверов") {navigate("ping")}
        } }
        item { SurfaceCard {
            ActionRow(Icons.Rounded.VpnLock,"Туннель и DNS") {navigate("tunnel")}
            ActionRow(Icons.Rounded.AltRoute,"Профили маршрутизации") {navigate("routing")}
            ActionRow(Icons.Rounded.Apps,"Приложения в VPN") {navigate("apps")}
            ActionRow(Icons.Rounded.Router,"Локальные SOCKS5 / HTTP") {navigate("inbounds")}
        } }
        item { SurfaceCard {
            ActionRow(Icons.Rounded.BarChart,"Статистика") {navigate("stats")}
            ActionRow(Icons.Rounded.Terminal,"Журнал подключения") {navigate("logs")}
            ActionRow(Icons.Rounded.HelpOutline,"Справка и ограничения") {navigate("help")}
            ActionRow(Icons.Rounded.Info,"О приложении") {navigate("about")}
            ActionRow(Icons.Rounded.Description,"Лицензии компонентов") {navigate("licenses")}
        } }
        item { TextButton(onClick=onReset) { Icon(Icons.Rounded.RestartAlt,null);Text("Сбросить настройки",Modifier.padding(start=8.dp)) } }
    }
}

data class Pref(val key: String,val title: String,val detail: String="",val options: List<Pair<String,String>> = emptyList(),val toggle: Boolean=false,val secret: Boolean=false)
private fun choices(vararg pairs: Pair<String,String>)=pairs.toList()
private val preferenceGroups=mapOf(
    "interface" to listOf(
        Pref("animations","Декоративные анимации","Матричный дождь, переходы и печать в терминале.",toggle=true),
        Pref("textScale","Размер текста",options=choices("0.9" to "Компактный","1.0" to "Обычный","1.15" to "Крупный","1.3" to "Очень крупный")),
        Pref("terminalCharacterDelay","Скорость печати в терминале",options=choices("20" to "Быстрая","40" to "Обычная","80" to "Медленная")),
        Pref("accent","Фиолетовый акцент",options=choices("violet" to "Фиолетовый","lavender" to "Лавандовый","deep" to "Глубокий")),
    ),
    "subprefs" to listOf(
        Pref("autoUpdate","Автообновление","WorkManager проверяет срок примерно раз в час. Android может отложить фоновую задачу.",toggle=true),
        Pref("updateHours","Интервал обновления, ч","1–168 часов. Интервал пользователя имеет приоритет над метаданными провайдера."),
        Pref("fetchTimeout","Тайм-аут загрузки, с","5–120 секунд"),
        Pref("updateNotice","Уведомлять об обновлении","Нужно разрешение Android на уведомления.",toggle=true),
        Pref("updateOnOpen","Обновлять при открытии",toggle=true),
        Pref("pingOnOpen","Проверять серверы при запуске","Выполняется при отключённом VPN.",toggle=true),
        Pref("connectOnOpen","Подключать при открытии","Android запросит разрешение, если оно ещё не предоставлено.",toggle=true),
        Pref("hwid","Отправлять идентификатор установки","Случайный UUID Phantom, модель и версия Android. Это не HWID Happ; провайдер должен разрешать собственные клиенты.",toggle=true),
        Pref("userAgent","User-Agent","По умолчанию Phantom. Для составного JSON можно указать Happ/5.5.0/android в настройках отдельной подписки."),
        Pref("deduplicate","Убирать дубликаты","Повторный URL обновляет существующую подписку; идентичные конфигурации объединяются.",toggle=true),
        Pref("collapsible","Сворачивать подписки",toggle=true),
        Pref("sort","Порядок серверов",options=choices("original" to "Как в подписке","latency" to "По задержке","alphabet" to "По алфавиту")),
        Pref("filter","Фильтр по названию","Показывать названия, содержащие этот текст. Пустое значение показывает все."),
    ),
    "ping" to listOf(
        Pref("pingMethod","Метод проверки",options=choices("auto" to "Авто (TCP или HTTPS)","GET" to "HTTP GET через прокси","HEAD" to "HTTP HEAD через прокси","TCP" to "TCP до сервера","ICMP" to "ICMP до сервера")),
        Pref("pingMode","Повтор HTTP-проверки","Double открывает новое соединение. Keepalive использует открытое соединение, если сервер это разрешает. Показан лучший успешный замер.",options=choices("default" to "Один запрос","double" to "Double","keepalive" to "Keepalive")),
        Pref("pingTimeout","Тайм-аут проверки, с","2–60 секунд на запрос"),
        Pref("testUrl","URL проверки","HTTPS endpoint с небольшим ответом, например generate_204. Используется и для подтверждения интернета через TUN."),
    ),
    "inbounds" to listOf(
        Pref("socksPort","Порт SOCKS5","1024–65535. Вход работает, пока включён VPN."),
        Pref("httpEnabled","Локальный HTTP-прокси",toggle=true),
        Pref("httpPort","Порт HTTP","Порт должен отличаться от SOCKS5 и служебных 10990/10991."),
        Pref("proxyUser","Логин прокси","Пустые логин и пароль отключают авторизацию для loopback."),
        Pref("proxyPassword","Пароль прокси",secret=true),
        Pref("lanProxy","Принимать подключения из LAN","Открывает прокси на 0.0.0.0; обязательны логин и пароль. Внутренний TUN SOCKS остаётся на loopback.",toggle=true),
    ),
    "tunnel" to listOf(
        Pref("ipv6","IPv6 через туннель","Если отключено, Android блокирует IPv6 для приложений VPN. Включение добавляет IPv6-адрес и маршрут ::/0.",toggle=true),
        Pref("ipPreference","Разрешение имён для URI-серверов","Составной JSON сохраняет собственный DNS провайдера.",options=choices("UseIPv4" to "Только IPv4","UseIP" to "IPv4 и IPv6","UseIPv6" to "Только IPv6")),
        Pref("dns","DNS для URI-конфигураций","IP или DoH URL. Составные JSON сохраняют DNS провайдера. DNS приложений на 10.66.0.2 поступает в DNS Xray."),
        Pref("lanDirect","Локальная сеть напрямую","Добавляет geoip:private → Direct внутри Xray. Локальные назначения выходят через защищённые сокеты ядра.",toggle=true),
        Pref("mux","Mux для совместимых URI","Только одиночный RAW/TCP без Vision flow. Несовместимая комбинация вызывает понятную ошибку при подключении.",toggle=true),
        Pref("fragment","Фрагментация TLS ClientHello","Только одиночный RAW/TCP + TLS. Создаёт Freedom dialerProxy с tlshello 100–200 байт и паузой 10–20 мс. Не меняет составной JSON.",toggle=true),
        Pref("logLevel","Уровень журнала Phantom","Журнал событий клиента в памяти, до 300 строк. Сырые логи ядра отключены для защиты секретов.",options=choices("debug" to "Debug","info" to "Info","warning" to "Warning","error" to "Error","none" to "Отключён")),
    ),
)

fun validatePreference(key: String,value: String) {
    val ranges=mapOf("updateHours" to 1..168,"fetchTimeout" to 5..120,"pingTimeout" to 2..60,"socksPort" to 1024..65535,"httpPort" to 1024..65535)
    ranges[key]?.let { require(value.toIntOrNull() in it) { "Число должно быть в диапазоне ${it.first}–${it.last}" } }
    if(key=="testUrl") require(runCatching { URI(value).let { it.scheme=="https" && it.host!=null && it.userInfo==null } }.getOrDefault(false)) { "Нужен корректный HTTPS URL" }
    if(key=="dns") require(value.matches(Regex("[0-9a-fA-F:.]+")) || runCatching { URI(value).let { it.scheme=="https" && it.host!=null } }.getOrDefault(false)) { "DNS: IP или HTTPS DoH URL" }
    if(key=="userAgent") require(value.isNotBlank() && '\n' !in value && '\r' !in value && value.length<=256) { "Некорректный User-Agent" }
}

@Composable fun PreferenceScreen(route: String,c: Catalog,onChange: (String,String)->Unit,onSystemVpn: ()->Unit) {
    var editing by remember { mutableStateOf<Pref?>(null) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(route=="interface") item { Text("Русский интерфейс · чёрная тема",color=Muted,fontSize=12.sp) }
        if(route=="ping") item { Text("Авто измеряет время TCP-подключения к адресу и порту сервера. Для UDP и маршрутов без однозначного адреса используется HTTPS через Xray. ICMP доступен при отключённом VPN.",color=Muted,fontSize=12.sp) }
        if(route=="tunnel") item { SurfaceCard { Text("Постоянный VPN Android",fontSize=18.sp);Text("Откройте системные настройки → Phantom → Постоянная VPN и Блокировать соединения без VPN. Это системный эквивалент On Demand. У vivo также разрешите работу приложения в фоне.",Modifier.padding(vertical=10.dp),color=Muted,fontSize=12.sp);TextButton(onClick=onSystemVpn) {Text("Открыть настройки Android")} } }
        items(preferenceGroups[route].orEmpty(),key={it.key}) { pref->
            SurfaceCard(onClick=if(pref.toggle) null else { { editing=pref } }) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(pref.title,fontSize=15.sp);if(!pref.toggle) Text(if(pref.secret) (if(c.setting(pref.key).isBlank()) "Не задан" else "••••••••") else pref.options.find { it.first==c.setting(pref.key) }?.second ?: c.setting(pref.key).ifBlank { "Не задано" },color=MaterialTheme.colorScheme.primary,fontSize=12.sp,modifier=Modifier.padding(top=5.dp)) }
                    if(pref.toggle) Switch(c.flag(pref.key),{onChange(pref.key,it.toString())}) else Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
                }
                if(pref.detail.isNotBlank()) Text(pref.detail,color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=9.dp))
            }
        }
        item { Text("Сетевые настройки применяются при следующем подключении.",fontSize=11.sp,color=Muted) }
    }
    editing?.let { pref->
        var value by remember(pref) { mutableStateOf(c.setting(pref.key)) };var error by remember(pref) { mutableStateOf("") }
        AlertDialog(onDismissRequest={editing=null},title={Text(pref.title)},text={Column(Modifier.verticalScroll(rememberScrollState())) {
            if(pref.options.isNotEmpty()) pref.options.forEach { (key,label)->Row(Modifier.fillMaxWidth().clickable { value=key }.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) { RadioButton(value==key,{value=key});Text(label) } }
            else OutlinedTextField(value,{value=it},Modifier.fillMaxWidth(),visualTransformation=if(pref.secret) PasswordVisualTransformation() else VisualTransformation.None)
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        }},confirmButton={TextButton(onClick={runCatching {validatePreference(pref.key,value)}.onSuccess {onChange(pref.key,value);editing=null}.onFailure {error=it.message.orEmpty()} }) {Text("Применить")}},dismissButton={TextButton(onClick={editing=null}) {Text("Отмена")}})
    }
}

@Composable fun RoutingScreen(c: Catalog,onSelect: (String)->Unit,onEdit: (String)->Unit,onExport: (String)->Unit,onDelete: (String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Пользовательские правила проверяются перед правилами провайдера. Proxy использует прокси или балансировщик исходной конфигурации. Поддерживается импорт JSON Phantom, Xray routing и открытого профиля Happ.",color=Muted,fontSize=12.sp) }
        items(c.routeProfiles.keys.toList(),key={it}) { name->SurfaceCard {
            Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(c.setting("routeProfile")==name,{onSelect(name)});Text(name,Modifier.weight(1f),fontSize=18.sp);IconButton(onClick={onEdit(name)}) {Icon(Icons.Rounded.Edit,"Редактировать")} }
            Row {TextButton(onClick={onExport(name)}) {Text("Экспорт")};if(name!="default") TextButton(onClick={onDelete(name)}) {Text("Удалить")} }
        } }
        item { Button(onClick={onEdit("Новый профиль")},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Rounded.Add,null);Text("Создать или импортировать",Modifier.padding(start=8.dp))} }
    }
}
data class InstalledApp(val packageName: String,val label: String)
@Composable fun AppsScreen(app: PhantomApp,c: Catalog,onSave: (String,Set<String>)->Unit) {
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var mode by remember { mutableStateOf(c.setting("appMode")) }
    var selected by remember { mutableStateOf(c.setting("packages").lineSequence().filter(String::isNotBlank).toSet()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps=withContext(Dispatchers.IO) {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        app.packageManager.queryIntentActivities(intent,0).map { InstalledApp(it.activityInfo.packageName,it.loadLabel(app.packageManager).toString()) }
            .filter { it.packageName!=app.packageName }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    } }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Text("Приложения с выбранным направлением обходят правила доменов. В режиме «Только выбранные» Phantom также включён для проверки TUN. Системные компоненты без значка можно добавить пакетом ниже.",fontSize=12.sp,color=Muted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("all" to "Все","allow" to "Только","deny" to "Кроме").forEach { (k,label)->FilterChip(mode==k,{mode=k},label={Text(label)}) } }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Поиск приложения / имя пакета")},singleLine=true)
        Row {TextButton(onClick={if(query.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {selected=selected+query;query=""}}) {Text("Добавить пакет")};TextButton(onClick={selected=emptySet()}) {Text("Очистить выбор")} }
        Text("Выбрано: ${selected.size}",color=Muted,fontSize=12.sp)
        LazyColumn(Modifier.weight(1f)) {
            items((apps+selected.filter { p->apps.none { it.packageName==p } }.map { InstalledApp(it,it) }).filter { it.label.contains(query,true) || it.packageName.contains(query,true) },key={it.packageName}) { entry->
                Row(Modifier.fillMaxWidth().clickable { selected=if(entry.packageName in selected) selected-entry.packageName else selected+entry.packageName }.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(entry.packageName in selected,{checked->selected=if(checked) selected+entry.packageName else selected-entry.packageName},enabled=mode!="all")
                    Column { Text(entry.label,fontSize=14.sp);Text(entry.packageName,fontSize=10.sp,color=Muted) }
                }
            }
        }
        Button(onClick={onSave(mode,selected)},Modifier.fillMaxWidth().padding(vertical=14.dp)) {Text("Сохранить выбор")} 
    }
}
@Composable fun LogsScreen(logs: List<String>,onClear: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("События клиента без адресов, ключей и конфигураций. Журнал хранится в памяти. По умолчанию отображаются предупреждения и ошибки.",fontSize=12.sp,color=Muted)
        TextButton(onClick=onClear) {Text("Очистить журнал")}
        SelectionContainer { LazyColumn {if(logs.isEmpty()) item {Text("Записей нет",color=Muted)};items(logs) {Text(it,fontSize=11.sp,fontFamily=FontFamily.Monospace,modifier=Modifier.padding(vertical=7.dp))} } }
    }
}
@Composable fun StatisticsScreen(c: Catalog,vpn: VpnSnapshot) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("Счётчики IP-пакетов TUN. Это трафик устройства, включая заголовки; биллинг провайдера может отличаться.",fontSize=12.sp,color=Muted)}
        item {SurfaceCard {Text("Текущее подключение",fontSize=21.sp);Text("Загрузка: ${bytes(vpn.rx)}\nОтдача: ${bytes(vpn.tx)}",Modifier.padding(top=16.dp),fontFamily=FontFamily.Monospace)} }
        item {SurfaceCard {Text("Всего на устройстве",fontSize=21.sp);Text("Загрузка: ${bytes(c.totalRx)}\nОтдача: ${bytes(c.totalTx)}",Modifier.padding(top=16.dp),fontFamily=FontFamily.Monospace)} }
        item {Text("Статистика подписки поступает от провайдера при обновлении. Общие счётчики сохраняются каждые 10 секунд и при отключении; при принудительном завершении возможна потеря последнего интервала.",color=Muted,fontSize=12.sp)}
    }
}
@Composable fun InfoScreen(route: String,app: PhantomApp) {
    var text by remember(route) { mutableStateOf("") }
    LaunchedEffect(route) { text=withContext(Dispatchers.IO) { when(route) {
        "licenses"->app.assets.list("licenses").orEmpty().joinToString("\n\n━━━━━━━━━━━━━━━━\n\n") { name->"$name\n\n"+app.assets.open("licenses/$name").bufferedReader().use { it.readText() } }
        "about"->"Phantom ${app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()}\n\nЛичный Android-клиент на Xray-core 26.11.9 / libXray 26.11.9 и hev-socks5-tunnel 2.19.3.\n\nKotlin 2.2.21 · Jetpack Compose · Android VpnService\n\nДанные подписок шифруются AES-GCM ключом Android Keystore, исключены из резервного копирования. Телеметрия не отправляется.\n\nИсходники компонентов:\nhttps://github.com/XTLS/Xray-core/tree/v26.11.9\nhttps://github.com/XTLS/libXray/tree/v26.11.9\nhttps://github.com/heiher/hev-socks5-tunnel/tree/2.19.3"
        else->"Начало работы\n\nДобавьте HTTPS-подписку, QR, файл или ссылку сервера. Выберите сервер и разрешите VPN в системном диалоге. Фиолетовый дождь означает запущенный TUN; зелёный статус — успешную HTTP-проверку через VPN с изменением счётчиков TUN.\n\nСовместимость с Happ\n\nОдин URL может отдавать разные форматы. Если нужен составной JSON с балансировщиками, откройте меню подписки → Редактировать → Запрашивать формат Happ JSON → Сохранить → Обновить. User-Agent не добавляет закрытые технологии Happ. HWID Phantom отличается от HWID Happ; для привязанной подписки обратитесь к провайдеру. Закрытые crypt/limited/HWID-ссылки и AES-GCM Happ требуют обычный URL или ключ провайдера.\n\nПроверки серверов\n\nАвто использует время TCP-подключения для серверов с одним TCP-адресом. Для остальных конфигураций выполняется HTTPS GET через отдельный процесс Xray. HTTPS-замер заканчивается при получении заголовков ответа. ICMP использует время ответа на пакет и требует отключённого VPN.\n\nМаршруты и DNS\n\nПрофили содержат domain/IP и направления Proxy, Direct, Block. Правила срабатывают до правил провайдера. Для зашифрованного ECH доменное распознавание может быть ограничено. Составные JSON сохраняют свой DNS и маршруты. Для URI DNS настраивается отдельно. DNS для начального разрешения адреса самого сервера идёт через DNS физической сети с защищённым сокетом; DNS приложений поступает в Xray через TUN. IPv6 отключён по умолчанию и блокируется в VPN.\n\nРабота в фоне\n\nИспользуется foreground service. В Android включите Постоянную VPN и Блокировать соединения без VPN. Разрешите Phantom фоновую работу в настройках питания vivo. Приложение не берёт постоянный WakeLock. Android может отложить автообновление подписок. Быстрая плитка добавляется через редактор панели уведомлений.\n\nСсылки приложения\n\nphantom://import?url=<URL-encoded HTTPS URL>\nphantom://import?text=<URL-encoded config>\nТакже поддерживаются схемы VLESS, VMess, Trojan, SS и Hysteria2. Импорт из ссылки требует просмотра и сохранения.\n\nИзвестные ограничения\n\nИстория DNS и сырые логи Xray не предоставляются. Live Activity заменена постоянным Android-уведомлением. Нет подвижной кнопки iOS. Нативный Xray TUN не используется: выбран HEV для стабильного TCP/UDP и измеримых счётчиков. Изменения сетевых настроек требуют переподключения. Не реализованы закрытые команды Provider ID, криптоссылки и автоматическое применение настроек интерфейса от провайдера. Android 17 и прошивка vivo требуют проверки на телефоне.\n\nЭкспорт\n\nЭкспорт JSON содержит ключи серверов. Сохраняйте его только в выбранное вами безопасное место; приложение не включает URL подписки в экспорт."
    } } }
    SelectionContainer { Text(text,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),fontSize=13.sp,lineHeight=21.sp,color=if(route=="licenses") Muted else Color.White) }
}
