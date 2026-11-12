package uniear.android

import android.annotation.TargetApi
import android.app.Activity
import android.app.Notification
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

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun canPostPromotedNotifications(context: Context): Boolean = Build.VERSION.SDK_INT >= 36 &&
        context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true

    fun openSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return openSettingsPage(activity, Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS) ||
            openSettingsPage(activity, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
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
    ): Notification = builder
        .setContentTitle(view.model?.takeIf { it.isNotBlank() } ?: "UniEar")
        .setContentText(view.status)
        .setShortCriticalText("连接中")
        .setShowWhen(false)
        .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
        .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, true) })
        .addAction(Notification.Action.Builder(null, "取消连接", cancel).build())
        .build()
}
