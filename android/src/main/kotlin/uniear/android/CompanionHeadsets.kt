package uniear.android

import uniear.core.tr
import android.app.Activity
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import uniear.core.Headset

internal object CompanionHeadsets {
    fun available(activity: Activity) = Build.VERSION.SDK_INT >= 31 &&
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    @Suppress("DEPRECATION")
    fun associate(activity: Activity, headset: Headset, report: (String) -> Unit) {
        val manager = activity.getSystemService(CompanionDeviceManager::class.java) ?: return
        val alreadyAssociated = if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations.any { headset.address.equals(it.deviceMacAddress?.toString(), true) }
        } else manager.associations.any { headset.address.equals(it, true) }
        if (alreadyAssociated) {
            report(tr("these_headphones_are_associated_for_background_auto"))
            return
        }
        val request = AssociationRequest.Builder().addDeviceFilter(
            BluetoothDeviceFilter.Builder().setAddress(headset.address).build(),
        ).setSingleDevice(true).build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(chooserLauncher: IntentSender) = openPicker(chooserLauncher)
            override fun onAssociationPending(intentSender: IntentSender) = openPicker(intentSender)
            override fun onFailure(error: CharSequence?) { report(tr("could_not_associate_the_headphones_check_that")) }

            private fun openPicker(sender: IntentSender) {
                if (activity.isDestroyed || activity.isFinishing) return
                runCatching { activity.startIntentSenderForResult(sender, 40, null, 0, 0, 0) }
                    .onFailure { report(tr("could_not_open_the_system_association_screen")) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) manager.associate(request, activity.mainExecutor, callback)
        else manager.associate(request, callback, Handler(Looper.getMainLooper()))
    }
}
