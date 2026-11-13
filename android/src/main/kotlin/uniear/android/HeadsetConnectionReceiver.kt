package uniear.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

class HeadsetConnectionReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (!LiveUpdates.supported || !Surface.LIVE_UPDATE.enabled(context) || ControlState.busy ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val action = intent.action ?: return
        val connected: Boolean
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // A Classic audio connection can also open a separate LE control link.
                if (intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO) ==
                    BluetoothDevice.TRANSPORT_LE) return
                connected = false
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTING && state != BluetoothProfile.STATE_CONNECTED) return
                connected = state == BluetoothProfile.STATE_CONNECTED
            }
            else -> return
        }

        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val headset = runCatching { AndroidRadio(context).headsetCandidate(device) }.getOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        recentConnections.entries.removeAll { now - it.value.time >= DEDUP_MS }
        val previous = recentConnections[headset.address]
        if (previous != null && (previous.connected || !connected)) return
        recentConnections[headset.address] = Event(now, connected)
        LiveUpdates.showBluetoothConnection(context, headset.identity.deviceName, connected)
    }

    private companion object {
        const val DEDUP_MS = 5_000L
        data class Event(val time: Long, val connected: Boolean)
        val recentConnections = mutableMapOf<String, Event>()
    }
}
