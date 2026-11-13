package uniear.android

import android.annotation.TargetApi
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.annotation.SuppressLint
import uniear.core.HeadsetView

internal object LiveUpdates {
    // Public notification extra. Android 36.1 exposes a named constant for it.
    private const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val BLUETOOTH_CHANNEL = "headset_connection_events"
    private const val BLUETOOTH_NOTICE = 81

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun canPostPromotedNotifications(context: Context): Boolean = Build.VERSION.SDK_INT >= 36 &&
        context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true

    fun openSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return openSettingsPage(activity, Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS) ||
            openSettingsPage(activity, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }

    @SuppressLint("MissingPermission")
    fun showBluetoothConnection(context: Context, model: String?, connected: Boolean) {
        if (Build.VERSION.SDK_INT < 36) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(BLUETOOTH_CHANNEL, "蓝牙耳机连接实况",
            NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(context, BLUETOOTH_CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(QuickControls.openApp(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(6_000)
        val view = HeadsetView(if (connected) "蓝牙耳机已连接" else "正在建立蓝牙音频连接…", model = model)
        val notice = if (connected) {
            builder.setContentTitle(model ?: "UniEar").setContentText(view.status)
                .setShortCriticalText("已连接").setShowWhen(false)
                .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
                .build()
        } else connectionNotification(builder, view)
        runCatching { manager.notify(BLUETOOTH_NOTICE, notice) }
    }

    fun dismissBluetoothConnection(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(BLUETOOTH_NOTICE)
    }

    private fun openSettingsPage(activity: Activity, action: String): Boolean {
        val intent = Intent(action)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @TargetApi(36)
    fun connectionNotification(
        builder: Notification.Builder,
        view: HeadsetView,
        cancel: PendingIntent? = null,
    ): Notification {
        builder.setContentTitle(view.model?.takeIf { it.isNotBlank() } ?: "UniEar")
            .setContentText(view.status)
            .setShortCriticalText("连接中")
            .setShowWhen(false)
            .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
        if (cancel != null) builder.addAction(Notification.Action.Builder(null, "取消连接", cancel).build())
        return builder.build()
    }
}
