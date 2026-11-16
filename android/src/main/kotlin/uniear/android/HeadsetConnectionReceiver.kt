package uniear.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.hyperears.integration.EarbudAdapterRegistry
import uniear.core.Headset
import java.util.UUID

class HeadsetConnectionReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (!HeadsetConnection.hasBluetoothPermission(context)) return
        val action = intent.action ?: return
        val profile = when (action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> BluetoothProfile.A2DP
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> BluetoothProfile.HEADSET
            BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> BluetoothProfile.LE_AUDIO
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> null
            else -> return
        }
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        if (profile == null) {
            val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO)
            if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                if (Build.VERSION.SDK_INT >= 31 && transport == BluetoothDevice.TRANSPORT_LE &&
                    HeadsetConnection.enabled(context)) observeLeAudioRoute(context, device)
                return
            }
            if (transport != BluetoothDevice.TRANSPORT_LE || device.type == BluetoothDevice.DEVICE_TYPE_LE) {
                HeadsetConnection.disconnected(context, device.address)
            }
            return
        }
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
            BluetoothProfile.STATE_DISCONNECTED -> HeadsetConnection.profileDisconnected(context, device.address, profile)
            BluetoothProfile.STATE_CONNECTED -> {
                if (!HeadsetConnection.enabled(context)) return
                val headset = runCatching { AndroidRadio(context).headsetCandidate(device) }.getOrNull() ?: return
                HeadsetConnection.connected(context, headset, profile)
            }
        }
    }

    private fun observeLeAudioRoute(context: Context, device: BluetoothDevice) {
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val audio = context.getSystemService(AudioManager::class.java)
        var checks = 0
        val check = object : Runnable {
            override fun run() {
                val route = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET && it.address.equals(device.address, true)
                }
                if (route == null && ++checks < 4 && HeadsetConnection.enabled(context)) {
                    main.postDelayed(this, 750)
                    return
                }
                try {
                    if (route != null && HeadsetConnection.enabled(context)) {
                        val headset = runCatching {
                            AndroidRadio(context).headsetCandidate(device, route.productName.toString(), leAudio = true)
                        }.getOrNull()
                        if (headset != null) HeadsetConnection.connected(context, headset, BluetoothProfile.LE_AUDIO)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
        main.post(check)
    }
}

/** Keeps one decision per physical connection even if A2DP and HFP both report it. */
internal object HeadsetConnection {
    const val NOTICE_DURATION = 30_000L
    private const val PREFERENCES = "headset-connection-offer"
    private val connections = mutableMapOf<String, MutableSet<Int>>()

    fun enabled(context: Context) = Surface.AUTO_CONNECT.enabled(context) || Surface.LIVE_UPDATE.enabled(context)

    fun hasBluetoothPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun connected(context: Context, headset: Headset, profile: Int) {
        val firstProfile = headset.address !in connections
        val profiles = connections.getOrPut(headset.address) { mutableSetOf() }
        profiles.add(profile)
        if (!firstProfile || ControlState.busy) return
        val supported = canControl(headset)
        val automatic = Surface.AUTO_CONNECT.enabled(context)
        if (!supported) {
            LiveUpdates.showBluetoothConnection(context, headset.identity.deviceName, "当前型号没有可用的控制适配器")
        } else if (automatic) {
            clearOffer(context)
            LiveUpdates.showBluetoothConnection(context, headset.identity.deviceName, "正在建立 UniEar 控制连接…")
            if (!ControlState.connect(context, headset, automatic = true)) {
                showOffer(context, headset, "系统限制后台连接，请点按「连接」。")
            }
        } else showOffer(context, headset, "可以尝试建立 UniEar 控制连接。")
    }

    fun canControl(headset: Headset) =
        EarbudAdapterRegistry.forIntegration(headset.identity)?.privateProtocolRequired == true

    fun profileDisconnected(context: Context, address: String, profile: Int) {
        val profiles = connections[address] ?: return
        profiles.remove(profile)
        if (profiles.isEmpty()) {
            if (profile == BluetoothProfile.LE_AUDIO) disconnected(context, address)
            else clearDisconnectedState(context, address)
        }
    }

    fun disconnected(context: Context, address: String) {
        connections.remove(address)
        clearDisconnectedState(context, address)
    }

    private fun clearDisconnectedState(context: Context, address: String) {
        if (context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("address", null) == address) {
            clearOffer(context)
            LiveUpdates.dismissBluetoothConnection(context)
        }
        ControlState.service?.automaticHeadsetDisconnected(address)
    }

    fun clearOffer(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun showOffer(context: Context, headset: Headset, message: String) {
        val token = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("address", headset.address).putString("token", token)
            .putLong("expires", SystemClock.elapsedRealtime() + NOTICE_DURATION).apply()
        fun action(accept: Boolean): PendingIntent {
            val data = Uri.Builder().scheme("uniear").authority("headset-offer")
                .appendPath(token).appendPath(if (accept) "connect" else "ignore").build()
            return PendingIntent.getBroadcast(context, 0,
                Intent(context, HeadsetOfferReceiver::class.java).setData(data), PendingIntent.FLAG_IMMUTABLE)
        }
        LiveUpdates.showBluetoothConnection(context, headset.identity.deviceName, message,
            connect = action(true), ignore = action(false))
    }

    fun consumeOffer(context: Context, token: String): String? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getString("token", null) != token) return null
        val expires = prefs.getLong("expires", 0)
        val address = prefs.getString("address", null)
        clearOffer(context)
        LiveUpdates.dismissBluetoothConnection(context)
        return address?.takeIf { expires - SystemClock.elapsedRealtime() in 1..NOTICE_DURATION }
    }
}

class HeadsetOfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 2 || parts[1] !in listOf("connect", "ignore")) return
        val address = HeadsetConnection.consumeOffer(context, parts[0]) ?: return
        if (parts[1] == "ignore" || ControlState.busy || !HeadsetConnection.hasBluetoothPermission(context)) return

        val pending = goAsync()
        Thread({
            val headset = runCatching { AndroidRadio(context).discover().firstOrNull { it.address == address } }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                try {
                    if (headset != null && HeadsetConnection.canControl(headset)) ControlState.connect(context, headset)
                } finally {
                    pending.finish()
                }
            }
        }, "UniEar connected headset").start()
    }
}
