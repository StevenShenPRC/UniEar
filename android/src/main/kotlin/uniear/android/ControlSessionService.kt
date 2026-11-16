package uniear.android

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
    var view = HeadsetView("先在系统中连接耳机，再查找已连接耳机。")
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
            publish(context, HeadsetView("正在连接…", model = headset.identity.deviceName))
            true
        } catch (error: RuntimeException) {
            pending = null
            publish(context, HeadsetView(error.message ?: "无法启动耳机控制服务"))
            false
        }
    }

    fun takePending(): Headset? = pending.also { pending = null }

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
            val initial = HeadsetView(if (next.automatic) "正在建立 UniEar 控制连接…" else "正在连接…",
                model = next.headset.identity.deviceName)
            enterForeground(initial, generation, false)
            foreground = true
            LiveUpdates.dismissBluetoothConnection(this)
            ControlState.publish(this, initial)
            updateAutomation()
            startAttempt(next, generation)
        } catch (error: RuntimeException) {
            if (next.automatic) HeadsetConnection.showOffer(this, next.headset, "系统未能启动控制服务，请点按「连接」。")
            finish(HeadsetView(error.message ?: "无法建立控制会话"))
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
                LiveUpdates.showBluetoothConnection(this, view.model, "现在可以通过 UniEar 控制此耳机。", acquired = true)
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
            finish(HeadsetView("已停止自动连接", model = model))
        } else if (attempt >= MAX_AUTOMATIC_ATTEMPTS) {
            LiveUpdates.showBluetoothConnection(this, model, "尝试 $attempt 次后未取得控制权。")
            finish(HeadsetView("自动连接失败，已停止重试", model = model))
        } else {
            ControlState.publish(this, HeadsetView("第 $attempt 次尝试未成功，正在等待重试…", model = model))
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

    internal fun disconnect() = finish(HeadsetView("已断开控制连接"))

    internal fun cancelConnection(generation: String) {
        if (connecting && token == generation && (request?.automatic == true || Surface.LIVE_UPDATE.enabled(this))) {
            finish(HeadsetView("已取消控制连接"))
        }
    }

    internal fun stopAutomaticAttempts() {
        if (connecting && request?.automatic == true) finish(HeadsetView("已停止自动连接"))
    }

    internal fun automaticHeadsetDisconnected(address: String) {
        if (request?.automatic == true && request?.headset?.address == address) {
            LiveUpdates.dismissBluetoothConnection(this)
            finish(HeadsetView("蓝牙耳机已断开"))
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
            if (wasActive) ControlState.publish(this, HeadsetView("控制会话已结束"))
        }
        super.onDestroy()
    }

    private companion object {
        const val MAX_AUTOMATIC_ATTEMPTS = 3
    }
}
