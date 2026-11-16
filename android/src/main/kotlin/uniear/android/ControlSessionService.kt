package uniear.android

import uniear.core.tr
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.hyperears.integration.ControlRequest
import dev.hyperears.integration.NoiseMode
import dev.hyperears.integration.StandardControlRequest
import uniear.core.Headset
import uniear.core.HeadsetSession
import uniear.core.HeadsetView
import java.util.UUID

internal enum class Surface(val key: String) {
    NOTIFICATION("notification"), TILE("tile"), WIDGET("widget"), CAPSULE("capsule"),
    LIVE_UPDATE("live_update"), AUTO_CONNECT("auto_connect");

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("quick-controls", Context.MODE_PRIVATE).getBoolean(key, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences("quick-controls", Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
        if (this == AUTO_CONNECT && !enabled) ControlState.service?.stopAutomaticAttempts()
        if (!HeadsetConnection.enabled(context)) {
            HeadsetConnection.clearOffer(context)
            LiveUpdates.dismissBluetoothConnection(context)
        }
        ControlState.refreshSurfaces(context)
    }
}

internal data class SessionRequest(val headset: Headset, val automatic: Boolean)

/** Process-local state, read and written on the main thread by all Android entry points. */
internal object ControlState {
    var view = HeadsetView(tr("connect_your_headphones_in_system_settings_then"))
        private set
    private var pending: SessionRequest? = null
    var service: ControlSessionService? = null
    private val listeners = linkedSetOf<(HeadsetView) -> Unit>()
    val busy get() = pending != null || service?.active == true

    fun observe(listener: (HeadsetView) -> Unit) {
        listeners.add(listener)
        listener(view)
    }

    fun remove(listener: (HeadsetView) -> Unit) { listeners.remove(listener) }

    fun publish(context: Context, next: HeadsetView) {
        view = next
        listeners.toList().forEach { it(next) }
        refreshSurfaces(context)
    }

    fun refreshSurfaces(context: Context) {
        service?.renderSurfaces()
        EarbudWidget.updateAll(context)
        EarbudTile.refresh(context)
    }

    fun connect(context: Context, headset: Headset, automatic: Boolean = false): Boolean {
        if (busy) return false
        pending = SessionRequest(headset, automatic)
        return try {
            context.startForegroundService(Intent(context, ControlSessionService::class.java))
            publish(context, HeadsetView(tr("connecting"), model = headset.identity.deviceName))
            true
        } catch (error: RuntimeException) {
            pending = null
            publish(context, HeadsetView(error.message ?: tr("could_not_start_the_headphone_control_service")))
            false
        }
    }

    fun takePending(): SessionRequest? = pending.also { pending = null }

    fun command(request: ControlRequest) {
        if (view.ready) service?.command(request)
    }

    fun select(context: Context, surface: Surface, mode: NoiseMode, token: String? = null) {
        val current = service ?: return
        if (surface.enabled(context) && (token == null || current.token == token) && mode in view.modes) {
            command(StandardControlRequest.SetNoiseMode(mode))
        }
    }

    fun cycle(context: Context, surface: Surface) {
        val modes = NoiseMode.entries.filter { it in view.modes }
        val current = modes.indexOfFirst { it == view.mode }
        if (modes.isNotEmpty()) select(context, surface, modes[(current + 1) % modes.size])
    }
}

class ControlSessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: HeadsetSession? = null
    private var request: SessionRequest? = null
    private var attempt = 0
    private var retry: Runnable? = null
    private var attemptDeadline: Runnable? = null
    private var connecting = false
    private var foreground = false
    private var foregroundLocation = false
    private val capsule by lazy { FloatingControls(this) }
    private val automation by lazy {
        NetworkAutomation(this) { command(StandardControlRequest.SetNoiseMode(it)) }
    }
    internal var token: String? = null
        private set
    internal val active get() = token != null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val next = ControlState.takePending()
        if (next == null) {
            if (!active) stopSelf(startId)
            return START_NOT_STICKY
        }
        val generation = UUID.randomUUID().toString()
        token = generation
        request = next
        attempt = 0
        connecting = true
        ControlState.service = this
        HeadsetConnection.clearOffer(this)
        try {
            val initial = HeadsetView(if (next.automatic) tr("opening_the_uniear_control_channel") else tr("connecting"),
                model = next.headset.identity.deviceName)
            enterForeground(initial, generation, false)
            foreground = true
            LiveUpdates.dismissBluetoothConnection(this)
            if (next.automatic && !Surface.AUTO_CONNECT.enabled(this)) {
                finish(HeadsetView(tr("auto_connect_stopped"), model = next.headset.identity.deviceName))
                return START_NOT_STICKY
            }
            ControlState.publish(this, initial)
            updateAutomation()
            startAttempt(next, generation)
        } catch (error: RuntimeException) {
            if (next.automatic) HeadsetConnection.showOffer(this, next.headset, tr("could_not_start_the_control_service_tap"))
            finish(HeadsetView(error.message ?: tr("could_not_establish_a_control_session")))
        }
        return START_NOT_STICKY
    }

    private fun startAttempt(next: SessionRequest, generation: String) {
        retry = null
        val number = ++attempt
        session = HeadsetSession(AndroidRadio(this), next.headset) { view ->
            main.post {
                if (token == generation && attempt == number && session != null) handleAttempt(next, generation, view)
            }
        }
        if (next.automatic) {
            val deadline = Runnable { failAutomaticAttempt(next, generation) }
            attemptDeadline = deadline
            main.postDelayed(deadline, 30_000)
        }
    }

    private fun handleAttempt(next: SessionRequest, generation: String, view: HeadsetView) {
        if (next.automatic && connecting && (view.ended || (view.ready && view.endpoint == null))) {
            failAutomaticAttempt(next, generation)
            return
        }
        if (view.ended) {
            finish(view)
            return
        }
        if (view.ready && connecting) {
            connecting = false
            clearAttemptDeadline()
            if (next.automatic && view.endpoint != null) {
                LiveUpdates.showBluetoothConnection(this, view.model, tr("uniear_can_now_control_these_headphones"), acquired = true)
            }
        }
        ControlState.publish(this, view)
        automation.onHeadsetChanged(view)
    }

    private fun failAutomaticAttempt(next: SessionRequest, generation: String) {
        clearAttemptDeadline()
        session?.close()
        session = null
        val model = next.headset.identity.deviceName
        if (!Surface.AUTO_CONNECT.enabled(this)) {
            finish(HeadsetView(tr("auto_connect_stopped"), model = model))
        } else if (attempt >= MAX_AUTOMATIC_ATTEMPTS) {
            LiveUpdates.showBluetoothConnection(this, model, tr("could_not_obtain_control_after_0_attempts", attempt))
            finish(HeadsetView(tr("auto_connect_failed_retries_stopped"), model = model))
        } else {
            ControlState.publish(this, HeadsetView(tr("attempt_0_failed_waiting_to_retry", attempt), model = model))
            val nextAttempt = Runnable { if (token == generation) startAttempt(next, generation) }
            retry = nextAttempt
            main.postDelayed(nextAttempt, 1_200L * attempt)
        }
    }

    private fun clearAttemptDeadline() {
        attemptDeadline?.let(main::removeCallbacks)
        attemptDeadline = null
    }

    internal fun command(request: ControlRequest) { session?.control(request) }

    private fun enterForeground(view: HeadsetView, generation: String, location: Boolean) {
        val notice = QuickControls.notification(this, view, generation, connecting, request?.automatic == true)
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(QuickControls.NOTIFICATION_ID, notice, type)
        } else {
            startForeground(QuickControls.NOTIFICATION_ID, notice)
        }
        foregroundLocation = location
    }

    internal fun updateAutomation() {
        val generation = token ?: return
        if (!foreground) return
        val rules = NetworkRules.read(this)
        val wantsWifi = rules.enabled && rules.wifi.isNotEmpty() && canReadWifiName()
        if (wantsWifi != foregroundLocation) {
            runCatching { enterForeground(ControlState.view, generation, wantsWifi) }
        }
        runCatching {
            automation.configure(rules, wantsWifi)
            automation.onHeadsetChanged(ControlState.view)
        }.onFailure { automation.close() }
    }

    internal fun disconnect() = finish(HeadsetView(tr("control_connection_disconnected")))

    internal fun cancelConnection(generation: String) {
        if (connecting && token == generation && (request?.automatic == true || Surface.LIVE_UPDATE.enabled(this))) {
            finish(HeadsetView(tr("control_connection_canceled")))
        }
    }

    internal fun stopAutomaticAttempts() {
        if (connecting && request?.automatic == true) finish(HeadsetView(tr("auto_connect_stopped")))
    }

    internal fun automaticHeadsetDisconnected(address: String) {
        if (request?.automatic == true && request?.headset?.address == address) {
            LiveUpdates.dismissBluetoothConnection(this)
            finish(HeadsetView(tr("bluetooth_headphones_disconnected")))
        }
    }

    internal fun renderSurfaces() {
        val current = token ?: return
        if (foreground) QuickControls.updateNotification(this, ControlState.view, current, connecting, request?.automatic == true)
        capsule.render(ControlState.view)
    }

    private fun finish(view: HeadsetView) {
        closeSession()
        ControlState.publish(this, view)
        stopSelf()
    }

    private fun closeSession() {
        token = null
        connecting = false
        request = null
        retry?.let(main::removeCallbacks)
        retry = null
        clearAttemptDeadline()
        automation.close()
        session?.close()
        session = null
        capsule.close()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        foregroundLocation = false
    }

    override fun onDestroy() {
        val wasActive = active
        closeSession()
        if (ControlState.service === this) {
            ControlState.service = null
            if (wasActive) ControlState.publish(this, HeadsetView(tr("control_session_ended")))
        }
        super.onDestroy()
    }

    private companion object {
        const val MAX_AUTOMATIC_ATTEMPTS = 3
    }
}
