package uniear.android

import android.annotation.SuppressLint
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
    fun showBluetoothConnection(
        context: Context,
        model: String?,
        message: String,
        acquired: Boolean = false,
        connect: PendingIntent? = null,
        ignore: PendingIntent? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(BLUETOOTH_CHANNEL, "蓝牙耳机连接实况",
            NotificationManager.IMPORTANCE_LOW))
        val status = if (acquired) "已取得控制权" else "蓝牙已连接"
        val builder = Notification.Builder(context, BLUETOOTH_CHANNEL)
            .setSmallIcon(R.drawable.ic_headphones)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(QuickControls.openApp(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(HeadsetConnection.NOTICE_DURATION)
            .setContentTitle(if (model.isNullOrBlank()) status else "$status · $model")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setShowWhen(false)
        if (connect != null) builder.addAction(Notification.Action.Builder(null, "连接", connect).build())
        if (ignore != null) builder.addAction(Notification.Action.Builder(null, "忽略", ignore).build())
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setShortCriticalText(status)
                .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
        }
        runCatching { manager.notify(BLUETOOTH_NOTICE, builder.build()) }
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
        cancel: PendingIntent,
        status: String,
    ): Notification {
        return builder.setContentTitle(view.model?.takeIf { it.isNotBlank() } ?: "UniEar")
            .setContentText(view.status)
            .setShortCriticalText(status)
            .setShowWhen(false)
            .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
            .addAction(Notification.Action.Builder(null, "取消连接", cancel).build())
            .build()
    }
}
