package uniear.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.hyperears.integration.NoiseMode
import org.json.JSONObject
import uniear.core.HeadsetView

internal data class NetworkRules(
    val enabled: Boolean = false,
    val cellular: NoiseMode? = null,
    val wifi: Map<String, NoiseMode> = emptyMap(),
) {
    fun save(context: Context) {
        val value = JSONObject().put("enabled", enabled).put("cellular", cellular?.name)
            .put("wifi", JSONObject(wifi.mapValues { it.value.name }))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rules", value.toString()).apply()
    }

    companion object {
        private const val PREFS = "network-automation"

        fun read(context: Context): NetworkRules {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rules", null)
                ?: return NetworkRules()
            val value = runCatching { JSONObject(raw) }.getOrNull() ?: return NetworkRules()
            val wifi = value.optJSONObject("wifi") ?: JSONObject()
            return NetworkRules(value.optBoolean("enabled"), parseMode(value.optString("cellular")),
                wifi.keys().asSequence().mapNotNull { ssid ->
                    parseMode(wifi.optString(ssid))?.let { ssid to it }
                }.toMap())
        }

        private fun parseMode(name: String): NoiseMode? = NoiseMode.entries.firstOrNull { it.name == name }
    }
}

internal fun Context.hasSsidPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal fun Context.canReadWifiName(): Boolean {
    if (!hasSsidPermission()) return false
    val location = getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled
    else location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

private fun knownSsid(value: String?): String? = value?.removeSurrounding("\"")
    ?.takeUnless { it.isEmpty() || it == WifiManager.UNKNOWN_SSID }

@Suppress("DEPRECATION")
internal fun Context.connectedWifiName(): String? {
    if (!canReadWifiName()) return null
    return runCatching {
        knownSsid(applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
    }.getOrNull()
}

/** Observes the system's default network only while UniEar owns a headset session. */
internal class NetworkAutomation(
    private val context: Context,
    private val applyMode: (NoiseMode) -> Unit,
) {
    private sealed interface Environment {
        data object Other : Environment
        data object Cellular : Environment
        data class Wifi(val ssid: String?) : Environment
    }

    private val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val main = Handler(Looper.getMainLooper())
    private var rules = NetworkRules()
    private var includeWifi = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var environment: Environment? = null
    private var evaluated: Environment? = null
    private var headset = HeadsetView("")
    private var settling = false
    private val settle = Runnable {
        settling = false
        evaluate()
    }

    fun configure(next: NetworkRules, readWifi: Boolean) {
        val changed = next.enabled != rules.enabled || targetMode(next) != targetMode(rules)
        val restart = includeWifi != readWifi || callback == null
        rules = next
        if (!rules.enabled || (rules.cellular == null && rules.wifi.isEmpty())) {
            close()
            return
        }
        if (changed) evaluated = null
        if (restart) {
            unregister()
            includeWifi = readWifi
            val monitor = if (Build.VERSION.SDK_INT >= 31 && readWifi) {
                object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onAvailable(network: Network) = available(this, network)
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                        capabilities(this, network, caps)
                    override fun onLost(network: Network) = lost(this, network)
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = available(this, network)
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                        capabilities(this, network, caps)
                    override fun onLost(network: Network) = lost(this, network)
                }
            }
            callback = monitor
            manager.registerDefaultNetworkCallback(monitor, main)
        } else if (changed) scheduleEvaluation()
    }

    fun onHeadsetChanged(view: HeadsetView) {
        headset = view
        if (!settling) evaluate()
    }

    private fun available(source: ConnectivityManager.NetworkCallback, network: Network) {
        if (callback !== source) return
        currentNetwork = network
        environment = null
        main.removeCallbacks(settle)
        settling = false
    }

    private fun capabilities(source: ConnectivityManager.NetworkCallback, network: Network, caps: NetworkCapabilities) {
        if (callback !== source || network != currentNetwork) return
        val next = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = if (!includeWifi) null else if (Build.VERSION.SDK_INT >= 31) {
                    knownSsid((caps.transportInfo as? WifiInfo)?.ssid)
                } else context.connectedWifiName()
                Environment.Wifi(ssid ?: (environment as? Environment.Wifi)?.ssid)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Environment.Cellular
            else -> Environment.Other
        }
        if (next != environment) {
            environment = next
            scheduleEvaluation()
        }
    }

    private fun lost(source: ConnectivityManager.NetworkCallback, network: Network) {
        if (callback !== source || network != currentNetwork) return
        currentNetwork = null
        environment = Environment.Other
        scheduleEvaluation()
    }

    private fun scheduleEvaluation() {
        main.removeCallbacks(settle)
        settling = true
        main.postDelayed(settle, 850)
    }

    private fun evaluate() {
        val current = environment ?: return
        if (!rules.enabled || !headset.ready || current == evaluated) return
        val mode = targetMode(rules)
        if (mode != null && mode !in headset.modes) return
        evaluated = current
        if (mode != null && mode != headset.mode) applyMode(mode)
    }

    private fun targetMode(settings: NetworkRules): NoiseMode? = when (val current = environment) {
        Environment.Cellular -> settings.cellular
        is Environment.Wifi -> current.ssid?.takeIf { context.canReadWifiName() }?.let(settings.wifi::get)
        else -> null
    }

    fun close() {
        unregister()
        evaluated = null
        headset = HeadsetView("")
    }

    private fun unregister() {
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
        main.removeCallbacks(settle)
        settling = false
        currentNetwork = null
        environment = null
    }
}
