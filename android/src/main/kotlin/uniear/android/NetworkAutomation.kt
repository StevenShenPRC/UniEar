package uniear.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
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
    ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" }

@Suppress("DEPRECATION")
internal fun Context.connectedWifiName(): String? {
    if (!canReadWifiName()) return null
    return runCatching {
        val info = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        knownSsid(info?.takeIf { it.supplicantState == SupplicantState.COMPLETED }?.ssid)
    }.getOrNull()
}

/** Observes the default route and the physical Wi-Fi connection during a headset session. */
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
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetwork: Network? = null
    private var defaultCaps: NetworkCapabilities? = null
    private val wifiNetworks = mutableMapOf<Network, String?>()
    private var observedEnvironment: Environment? = null
    private var environment: Environment? = null
    private var evaluated: Environment? = null
    private var headset = HeadsetView("")
    private var settling = false
    private var wifiRetries = 0
    private val settle = Runnable { settleNetwork() }

    private fun settleNetwork() {
        settling = false
        environment = currentEnvironment()
        if (includeWifi && (environment as? Environment.Wifi)?.let { it.ssid == null } == true && wifiRetries < 4) {
            wifiRetries++
            settling = true
            main.postDelayed(settle, 750)
            return
        }
        evaluate()
    }

    fun configure(next: NetworkRules, readWifi: Boolean) {
        val changed = next.enabled != rules.enabled || targetMode(next) != targetMode(rules)
        val restart = includeWifi != readWifi || defaultCallback == null
        rules = next
        if (!rules.enabled || (rules.cellular == null && rules.wifi.isEmpty())) {
            close()
            return
        }
        if (changed) evaluated = null
        if (restart) {
            unregister()
            includeWifi = readWifi
            val defaultMonitor = monitor(wifi = false)
            defaultCallback = defaultMonitor
            manager.registerDefaultNetworkCallback(defaultMonitor, main)
            if (rules.wifi.isNotEmpty()) observeWifi()
        } else {
            if (rules.wifi.isNotEmpty() && wifiCallback == null) observeWifi()
            if (rules.wifi.isEmpty()) stopObservingWifi()
            scheduleEvaluation()
        }
    }

    fun onHeadsetChanged(view: HeadsetView) {
        headset = view
        if (!settling) evaluate()
    }

    private fun observeWifi() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val wifiMonitor = monitor(wifi = true)
        wifiCallback = wifiMonitor
        try {
            manager.registerNetworkCallback(request, wifiMonitor, main)
        } catch (_: RuntimeException) {
            wifiCallback = null
        }
    }

    private fun monitor(wifi: Boolean): ConnectivityManager.NetworkCallback {
        fun available(source: ConnectivityManager.NetworkCallback, network: Network) {
            if (!wifi && source === defaultCallback && defaultNetwork != network) {
                defaultNetwork = network
                defaultCaps = null
            }
        }
        fun capabilities(source: ConnectivityManager.NetworkCallback, network: Network, caps: NetworkCapabilities) {
            if (wifi && source === wifiCallback) {
                wifiNetworks[network] = ssid(caps) ?: wifiNetworks[network]
            } else if (!wifi && source === defaultCallback && network == defaultNetwork) {
                defaultCaps = caps
            } else return
            networkChanged()
        }
        fun lost(source: ConnectivityManager.NetworkCallback, network: Network) {
            if (wifi && source === wifiCallback) {
                wifiNetworks.remove(network)
            } else if (!wifi && source === defaultCallback && network == defaultNetwork) {
                defaultNetwork = null
                defaultCaps = null
            } else return
            networkChanged()
        }
        return if (Build.VERSION.SDK_INT >= 31 && includeWifi) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = available(this, network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = capabilities(this, network, caps)
                override fun onLost(network: Network) = lost(this, network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = available(this, network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = capabilities(this, network, caps)
                override fun onLost(network: Network) = lost(this, network)
            }
        }
    }

    private fun ssid(caps: NetworkCapabilities?): String? =
        if (Build.VERSION.SDK_INT >= 31 && includeWifi) knownSsid((caps?.transportInfo as? WifiInfo)?.ssid) else null

    private fun currentEnvironment(): Environment {
        val caps = defaultCaps
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || wifiNetworks.isNotEmpty()) {
            if (!includeWifi || !context.canReadWifiName()) return Environment.Wifi(null)
            return Environment.Wifi(ssid(caps) ?: defaultNetwork?.let(wifiNetworks::get) ?: context.connectedWifiName()
                ?: wifiNetworks.values.filterNotNull().distinct().singleOrNull())
        }
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) Environment.Cellular else Environment.Other
    }

    private fun networkChanged() {
        val current = currentEnvironment()
        if (current != observedEnvironment) {
            observedEnvironment = current
            scheduleEvaluation()
        }
    }

    private fun scheduleEvaluation() {
        main.removeCallbacks(settle)
        wifiRetries = 0
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
        stopObservingWifi()
        defaultCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        defaultCallback = null
        main.removeCallbacks(settle)
        settling = false
        defaultNetwork = null
        defaultCaps = null
        observedEnvironment = null
        environment = null
    }

    private fun stopObservingWifi() {
        wifiCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        wifiCallback = null
        wifiNetworks.clear()
    }
}
