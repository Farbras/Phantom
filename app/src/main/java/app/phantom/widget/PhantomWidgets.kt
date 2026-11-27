package app.phantom.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import app.phantom.MainActivity
import app.phantom.PhantomApp
import app.phantom.R
import app.phantom.data.Catalog
import app.phantom.data.Server
import app.phantom.data.ServerCountries
import app.phantom.data.remoteOutbounds
import app.phantom.vpn.VpnAction
import app.phantom.vpn.VpnRuntime
import app.phantom.vpn.WidgetVpnState
import app.phantom.vpn.WidgetVpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class CompactVpnWidget : VpnWidget(R.layout.widget_compact)
class LargeVpnWidget : VpnWidget(R.layout.widget_large)

abstract class VpnWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as PhantomApp
        val display = PhantomWidgets.display(app.vault.state.value, VpnRuntime.widgetState())
        manager.updateAppWidget(ids, PhantomWidgets.views(app, layout, display))
    }
}

internal object PhantomWidgets {
    private val countries by lazy { ServerCountries(Locale.forLanguageTag("ru")) }
    private val flags = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
    private val providers = listOf(
        CompactVpnWidget::class.java to R.layout.widget_compact,
        LargeVpnWidget::class.java to R.layout.widget_large,
    )

    internal data class Display(val vpn: WidgetVpnState, val server: Server?, val autoSelect: Boolean)

    internal enum class Rain { OFF, FALLING, FADING }

    private val WidgetVpnState.rain: Rain
        get() = when (status) {
            WidgetVpnStatus.STARTING, WidgetVpnStatus.ACTIVE, WidgetVpnStatus.WAITING, WidgetVpnStatus.NO_ROUTE -> Rain.FALLING
            WidgetVpnStatus.OFF, WidgetVpnStatus.ERROR, WidgetVpnStatus.STOPPING -> Rain.OFF
        }

    fun observe(app: PhantomApp) {
        app.scope.launch(Dispatchers.Main.immediate) {
            val manager = AppWidgetManager.getInstance(app)
            // Leave the outgoing child attached while the launcher draws its fade-out.
            val detachDelay = app.resources.getInteger(R.integer.widget_rain_fade_duration) + 120L
            var previousRain = Rain.OFF
            var detachAt = 0L
            val vpn = combine(VpnRuntime.state, VpnRuntime.operations) { _, _ -> VpnRuntime.widgetState() }
                .distinctUntilChanged()
            val catalog = app.vault.state.map { it.copy(totalTx = 0, totalRx = 0) }.distinctUntilChanged()
            combine(catalog, vpn, ::display).distinctUntilChanged().collectLatest { display ->
                val rain = display.vpn.rain
                val now = SystemClock.elapsedRealtime()
                if (rain == Rain.OFF && previousRain == Rain.FALLING) detachAt = now + detachDelay
                previousRain = rain

                val fading = rain == Rain.OFF && now < detachAt
                updateWidgets(app, manager, display, if (fading) Rain.FADING else rain)
                if (fading) {
                    delay(detachAt - now)
                    updateWidgets(app, manager, display, Rain.OFF)
                }
            }
        }
    }

    private fun updateWidgets(context: Context, manager: AppWidgetManager, display: Display, rain: Rain) {
        for ((provider, layout) in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, layout, display, rain))
        }
    }

    fun display(catalog: Catalog, vpn: WidgetVpnState): Display {
        val server = when {
            vpn.serverId.isNotEmpty() -> catalog.server(vpn.serverId)
            catalog.autoSelect -> catalog.fastestServer()
            else -> catalog.selectedServer()
        }
        return Display(vpn, server, catalog.autoSelect)
    }

    fun views(context: Context, layout: Int, display: Display, rain: Rain = display.vpn.rain): RemoteViews =
        RemoteViews(context.packageName, layout).apply {
            val vpn = display.vpn
            val compact = layout == R.layout.widget_compact
            removeAllViews(R.id.widget_rain_placeholder)
            if (rain != Rain.OFF) {
                val frames = RemoteViews(context.packageName, R.layout.widget_rain_frames)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    addStableView(R.id.widget_rain_placeholder, frames, R.id.widget_rain_frames)
                } else {
                    addView(R.id.widget_rain_placeholder, frames)
                }
            }
            setDisplayedChild(R.id.widget_rain, if (rain == Rain.FALLING) 1 else 0)

            val status = when (vpn.status) {
                WidgetVpnStatus.OFF, WidgetVpnStatus.ERROR -> R.string.widget_off
                WidgetVpnStatus.STARTING -> R.string.widget_starting
                WidgetVpnStatus.ACTIVE -> R.string.widget_active
                WidgetVpnStatus.WAITING -> R.string.widget_waiting
                WidgetVpnStatus.NO_ROUTE -> R.string.widget_no_route
                WidgetVpnStatus.STOPPING -> R.string.widget_stopping
            }
            setTextViewText(R.id.widget_status, context.getString(status))
            setTextColor(R.id.widget_status, context.getColor(
                if (vpn.status == WidgetVpnStatus.ACTIVE) R.color.widget_green else R.color.widget_muted,
            ))

            val action = vpn.powerAction
            val enabled = vpn.allows(action)
            val connect = action == VpnAction.CONNECT
            val label = context.getString(if (connect) R.string.widget_connect else R.string.widget_disconnect)
            setTextViewText(R.id.widget_power, label)
            setBoolean(R.id.widget_power, "setEnabled", enabled)
            setTextColor(R.id.widget_power, context.getColor(if (enabled) R.color.widget_text else R.color.widget_muted))
            val intent = Intent(context, WidgetVpnActivity::class.java)
                .setAction(if (connect) WidgetVpnActivity.CONNECT else WidgetVpnActivity.DISCONNECT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setOnClickPendingIntent(R.id.widget_power, if (enabled) activityIntent(context, intent) else null)

            val empty = context.getString(R.string.widget_empty_value)
            val country = display.server?.let(countries::resolve)?.name ?: empty
            val location = if (display.autoSelect) {
                context.getString(R.string.widget_location, country, context.getString(R.string.widget_auto))
            } else country
            setTextViewText(R.id.widget_location, location)
            if (compact) return@apply

            val serverName = vpn.serverName.ifBlank { display.server?.name.orEmpty() }
                .replace(flags, "").lineSequence().joinToString(" ").trim().ifBlank { empty }
            setTextViewText(R.id.widget_server, serverName)
            setContentDescription(R.id.widget_location_row, context.getString(R.string.widget_open_location, location, serverName))
            val picker = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PICK_LOCATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setOnClickPendingIntent(R.id.widget_location_row, activityIntent(context, picker))

            val protocol = vpn.protocol.ifBlank { configuredProtocols(display.server) }
            setTextViewText(R.id.widget_protocol, protocol.ifBlank { empty })

            val hasSession = vpn.connectedAt > 0
            setViewVisibility(R.id.widget_timer, if (hasSession) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_no_timer, if (hasSession) View.GONE else View.VISIBLE)
            setChronometer(R.id.widget_timer, vpn.connectedAt.takeIf { hasSession } ?: SystemClock.elapsedRealtime(), null, hasSession)
        }

    private fun activityIntent(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

    private fun configuredProtocols(server: Server?): String = server?.let {
        runCatching {
            JSONObject(it.config).getJSONArray("outbounds").remoteOutbounds()
                .map { outbound -> outbound.optString("protocol").uppercase(Locale.ROOT) }
                .filter(String::isNotBlank).distinct().joinToString(" / ")
        }.getOrDefault("")
    }.orEmpty()
}
