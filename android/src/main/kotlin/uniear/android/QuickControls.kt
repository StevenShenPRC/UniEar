package uniear.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import dev.hyperears.integration.NoiseMode
import uniear.core.HeadsetView
import uniear.core.label

internal object QuickControls {
    const val NOTIFICATION_ID = 80
    private const val CHANNEL = "headset_session"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val notificationModes = mapOf(
        NoiseMode.ANC to R.id.notify_anc, NoiseMode.TRANSPARENCY to R.id.notify_transparency,
        NoiseMode.OFF to R.id.notify_off, NoiseMode.WIND to R.id.notify_wind,
    )

    fun appIntent(context: Context) = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0, appIntent(context), FLAGS)

    fun action(context: Context, surface: Surface, mode: NoiseMode, token: String): PendingIntent {
        val uri = Uri.Builder().scheme("uniear").authority("control").appendPath(surface.name)
            .appendPath(token).appendPath(mode.name).build()
        return PendingIntent.getBroadcast(context, 0,
            Intent(context, ControlReceiver::class.java).setData(uri), FLAGS)
    }

    fun notification(context: Context, view: HeadsetView, token: String): Notification {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "耳机控制会话", NotificationManager.IMPORTANCE_LOW))
        val showControls = Surface.NOTIFICATION.enabled(context)
        val text = if (showControls && view.ready) {
            listOfNotNull(view.mode?.label(), view.battery.label()).joinToString(" · ")
        } else if (view.ready) "耳机控制会话正在运行" else "正在连接耳机…"
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(if (showControls) view.model ?: "UniEar" else "UniEar")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (showControls && view.ready) {
            builder.setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(notificationView(context, view, token, expanded = false))
                .setCustomBigContentView(notificationView(context, view, token, expanded = true))
        }
        return builder.build()
    }

    private fun notificationView(context: Context, view: HeadsetView, token: String, expanded: Boolean): RemoteViews {
        val layout = if (expanded) R.layout.notification_details else R.layout.notification_modes
        return RemoteViews(context.packageName, layout).apply {
            for ((mode, id) in notificationModes) {
                val supported = mode in view.modes
                val selected = supported && mode == view.mode
                setTextViewText(id, if (selected) "✓${mode.label()}" else mode.label())
                setTextColor(id, if (supported) Color.rgb(19, 54, 43) else Color.rgb(90, 101, 94))
                setContentDescription(id, when {
                    selected -> "${mode.label()}，当前模式"
                    !supported -> "${mode.label()}，此耳机不支持"
                    else -> mode.label()
                })
                setOnClickPendingIntent(id, if (supported) action(context, Surface.NOTIFICATION, mode, token) else null)
            }
            if (expanded) {
                setTextViewText(R.id.notify_device, view.model ?: "UniEar")
                setTextViewText(R.id.notify_mode, "当前模式  ${view.mode?.label() ?: "未知"}")
                setTextViewText(R.id.notify_battery, view.battery.label())
                setTextViewText(R.id.notify_link, view.endpoint?.let { "控制通道  $it" } ?: view.status)
            }
        }
    }

    fun updateNotification(context: Context, view: HeadsetView, token: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, notification(context, view, token))
    }
}

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 3) return
        val surface = Surface.entries.firstOrNull { it.name == parts[0] } ?: return
        if (ControlState.service?.token != parts[1]) {
            if (surface == Surface.WIDGET) EarbudWidget.updateAll(context)
            return
        }
        val mode = NoiseMode.entries.firstOrNull { it.name == parts[2] }
        if (mode != null) ControlState.select(context, surface, mode, parts[1])
    }
}

class EarbudTile : TileService() {
    override fun onStartListening() {
        val enabled = Surface.TILE.enabled(this)
        val view = ControlState.view
        qsTile?.apply {
            label = "耳机模式"
            state = when {
                !enabled -> Tile.STATE_UNAVAILABLE
                view.ready && view.modes.isNotEmpty() -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= 29) subtitle = when {
                !enabled -> "UniEar 设置中已关闭"
                !view.ready -> "打开 UniEar 连接"
                view.modes.isEmpty() -> "该型号无可切换模式"
                else -> view.mode?.label() ?: "点击切换"
            }
            updateTile()
        }
    }

    override fun onClick() {
        if (!Surface.TILE.enabled(this)) return
        if (ControlState.view.ready && ControlState.view.modes.isNotEmpty()) {
            ControlState.cycle(this, Surface.TILE)
        } else if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(QuickControls.openApp(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(QuickControls.appIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        fun refresh(context: Context) = requestListeningState(context, ComponentName(context, EarbudTile::class.java))
    }
}

class EarbudWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        private val modeButtons = mapOf(
            NoiseMode.ANC to R.id.widget_anc, NoiseMode.TRANSPARENCY to R.id.widget_transparency,
            NoiseMode.OFF to R.id.widget_off, NoiseMode.WIND to R.id.widget_wind,
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, EarbudWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val view = ControlState.view
            val enabled = Surface.WIDGET.enabled(context)
            val token = ControlState.service?.token
            val active = enabled && view.ready && token != null
            return RemoteViews(context.packageName, R.layout.earbud_widget).apply {
                setTextViewText(R.id.widget_title, if (active) view.model ?: "UniEar" else "UniEar")
                setTextViewText(R.id.widget_status, when {
                    !enabled -> "小组件控制已关闭"
                    !active -> "点击连接耳机"
                    else -> listOfNotNull(view.mode?.label(), view.battery.label()).joinToString(" · ")
                })
                setOnClickPendingIntent(R.id.widget_header, QuickControls.openApp(context))
                setViewVisibility(R.id.widget_modes, if (active && view.modes.isNotEmpty()) View.VISIBLE else View.GONE)
                for ((mode, id) in modeButtons) {
                    val supported = active && mode in view.modes
                    setViewVisibility(id, if (supported) View.VISIBLE else View.GONE)
                    setTextViewText(id, if (mode == view.mode) "✓ ${mode.label()}" else mode.label())
                    setOnClickPendingIntent(id, if (supported && token != null)
                        QuickControls.action(context, Surface.WIDGET, mode, token) else null)
                }
            }
        }
    }
}
