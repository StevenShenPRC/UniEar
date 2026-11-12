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
    NOTIFICATION("notification"), TILE("tile"), WIDGET("widget"), CAPSULE("capsule");

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("quick-controls", Context.MODE_PRIVATE).getBoolean(key, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences("quick-controls", Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
        ControlState.refreshSurfaces(context)
    }
}

/** Process-local state, read and written on the main thread by all Android entry points. */
internal object ControlState {
    var view = HeadsetView("先在系统中连接耳机，再查找已连接耳机。")
        private set
    private var pending: Headset? = null
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

    fun connect(context: Context, headset: Headset) {
        if (busy) return
        pending = headset
        try {
            context.startForegroundService(Intent(context, ControlSessionService::class.java))
            publish(context, HeadsetView("正在连接…", model = headset.identity.deviceName))
        } catch (error: RuntimeException) {
            pending = null
            publish(context, HeadsetView(error.message ?: "无法启动耳机控制服务"))
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
    private var foreground = false
    private var foregroundLocation = false
    private val capsule by lazy { FloatingControls(this) }
    private val automation by lazy {
        NetworkAutomation(this) { command(StandardControlRequest.SetNoiseMode(it)) }
    }
    internal var token: String? = null
        private set
    internal val active get() = session != null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val headset = ControlState.takePending()
        if (headset == null) {
            if (!active) stopSelf(startId)
            return START_NOT_STICKY
        }
        val generation = UUID.randomUUID().toString()
        token = generation
        ControlState.service = this
        try {
            val initial = HeadsetView("正在连接…", model = headset.identity.deviceName)
            enterForeground(initial, generation, false)
            foreground = true
            session = HeadsetSession(AndroidRadio(this), headset) { view ->
                main.post {
                    if (token == generation) {
                        if (view.ended) finish(view) else {
                            ControlState.publish(this, view)
                            automation.onHeadsetChanged(view)
                        }
                    }
                }
            }
            ControlState.publish(this, initial)
            updateAutomation()
        } catch (error: RuntimeException) {
            finish(HeadsetView(error.message ?: "无法建立控制会话"))
        }
        return START_NOT_STICKY
    }

    internal fun command(request: ControlRequest) { session?.control(request) }

    private fun enterForeground(view: HeadsetView, generation: String, location: Boolean) {
        val notice = QuickControls.notification(this, view, generation)
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
            automation.configure(rules, wantsWifi && foregroundLocation)
            automation.onHeadsetChanged(ControlState.view)
        }.onFailure { automation.close() }
    }

    internal fun disconnect() = finish(HeadsetView("已断开控制连接"))

    internal fun renderSurfaces() {
        val current = token ?: return
        if (foreground) QuickControls.updateNotification(this, ControlState.view, current)
        capsule.render(ControlState.view)
    }

    private fun finish(view: HeadsetView) {
        closeSession()
        ControlState.publish(this, view)
        stopSelf()
    }

    private fun closeSession() {
        token = null
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
}
