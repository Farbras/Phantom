@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.phantom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phantom.core.Prober
import app.phantom.data.Catalog
import app.phantom.data.Server
import app.phantom.data.ServerCountries
import app.phantom.data.ServerCountry
import app.phantom.data.groupServers
import app.phantom.data.orderServerGroups
import java.util.Locale

internal const val REFRESH_SUBSCRIPTIONS = "Обновление подписок"

@Composable
internal fun ServerPicker(
    catalog: Catalog,
    servers: List<Server>,
    progress: Prober.Progress?,
    query: String,
    onQuery: (String) -> Unit,
    favorites: Boolean,
    onToggleFavorites: () -> Unit,
    busy: String,
    message: String?,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onAuto: () -> Unit,
    onSelect: (Server) -> Unit,
    onFavorite: (Server) -> Unit,
    onEdit: (Server) -> Unit,
) {
    val countries = remember { ServerCountries(Locale.forLanguageTag("ru")) }
    val countryInputs = remember(servers) { servers.map { Triple(it.id, it.name, it.config) } }
    val countryById = remember(countryInputs, countries) {
        servers.distinctBy { it.id }.associate { it.id to countries.resolve(it) }
    }
    val groups = remember(servers, countryById) { groupServers(servers, countryById) }
    val order = catalog.setting("sort")
    val initialLatencies = progress?.initialLatencies
    val orderedGroups = remember(groups, order, initialLatencies) {
        orderServerGroups(groups, order, countries, initialLatencies)
    }
    val search = query.trim()
    val filter = catalog.setting("filter")
    val selectedId = catalog.selected.takeUnless { catalog.autoSelect }
    val visibleGroups = remember(orderedGroups, search, favorites, filter) {
        orderedGroups.mapNotNull { group ->
            val matchesCountry = group.country.displayName().contains(search, ignoreCase = true)
            val visible = group.servers.filter { server ->
                (!favorites || server.favorite) && server.name.contains(filter, ignoreCase = true) &&
                    (matchesCountry || server.name.contains(search, ignoreCase = true))
            }
            if (visible.isEmpty()) null else group to visible
        }
    }
    val expanded = remember(search) { mutableStateMapOf<String, Boolean>() }
    val accent = MaterialTheme.colorScheme.primary
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Panel) {
        Box(Modifier.fillMaxHeight(.88f)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Text("Выберите маршрут", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerAction(
                        "Подписка", Icons.Rounded.Refresh,
                        enabled = busy.isBlank() && catalog.subscriptions.any { it.url.isNotBlank() },
                        loading = busy == REFRESH_SUBSCRIPTIONS,
                        modifier = Modifier.weight(1f),
                        onClick = onRefresh,
                    )
                    PickerAction(
                        "Пинг", Icons.Rounded.Speed,
                        enabled = busy.isBlank() && progress == null && servers.any { it.issue.isBlank() },
                        loading = progress != null,
                        modifier = Modifier.weight(1f),
                        onClick = onPing,
                    )
                }
                OutlinedTextField(
                    query, onQuery, Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true, label = { Text("Поиск сервера") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = RoundedCornerShape(18.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        favorites, onToggleFavorites, label = { Text("Избранное") },
                        leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) },
                    )
                    if (progress != null) Text("Проверено ${progress.completed} из ${progress.total}", fontSize = 11.sp, color = Muted)
                }
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.completed.toFloat() / progress.total },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (message != null) {
                    Text(message, Modifier.padding(vertical = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                    item(key = "auto") {
                        PickerRow("Авто", "Сервер с наименьшим пингом", catalog.autoSelect, busy.isBlank(), onClick = onAuto,
                            leading = { Icon(Icons.Rounded.Bolt, null, tint = accent) })
                    }
                    if (visibleGroups.isEmpty()) item(key = "empty") {
                        Text(
                            if (servers.isEmpty()) "Добавьте подписку или конфигурацию" else "Серверы не найдены",
                            Modifier.padding(vertical = 16.dp), fontSize = 13.sp, color = Muted,
                        )
                    }
                    for ((group, visible) in visibleGroups) {
                        val key = group.country?.code ?: "other"
                        val isExpanded = expanded[key] ?: search.isNotEmpty()
                        item(key = "country:$key") {
                            PickerRow(group.country.displayName(), serverCount(group.servers.size),
                                isSelected = group.servers.any { it.id == selectedId }, isExpanded = isExpanded,
                                onClick = { expanded[key] = !isExpanded },
                                leading = {
                                    val flag = group.country?.flag
                                    if (flag == null) Icon(Icons.Rounded.Public, null, tint = Muted)
                                    else Text(flag, fontSize = 22.sp)
                                },
                                trailing = {
                                    Text(group.latency?.let { "$it мс" } ?: "—",
                                        fontSize = 12.sp, color = if (group.latency == null) Muted else accent, maxLines = 1)
                                },
                            )
                        }
                        if (isExpanded) items(visible, key = { "server:${it.id}" }) { server ->
                            Box(Modifier.padding(start = 12.dp)) {
                                ServerRow(server, selectedId == server.id, server.id in progress?.pending.orEmpty(),
                                    { onSelect(server) }, { onFavorite(server) }, { onEdit(server) })
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun PickerAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = .15f), contentColor = accent),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
        else Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PickerRow(
    title: String,
    detail: String,
    isSelected: Boolean,
    enabled: Boolean = true,
    isExpanded: Boolean? = null,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) accent.copy(alpha = .13f) else Color.White.copy(alpha = .04f),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    selected = isSelected
                    if (isExpanded != null) stateDescription = if (isExpanded) "Раскрыто" else "Свёрнуто"
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.Center) { leading() }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 11.sp, color = Muted)
            }
            trailing()
            if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = accent)
            if (isExpanded != null) {
                Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null, Modifier.size(18.dp), tint = Muted)
            }
        }
    }
}

private fun ServerCountry?.displayName() = this?.name ?: "Другие"

private fun serverCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "серверов"
        count % 10 == 1 -> "сервер"
        count % 10 in 2..4 -> "сервера"
        else -> "серверов"
    }
    return "$count $word"
}
