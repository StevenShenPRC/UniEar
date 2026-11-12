package uniear.core

import dev.hyperears.integration.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean

data class Headset(val identity: EarbudIdentity, val advertisement: GattPeerIdentity? = null) {
    val address: String get() = requireNotNull(identity.deviceAddress)
    override fun toString() = "${identity.deviceName ?: "未知设备"}  ·  $address"
}

fun GattPeerSelection.CompanionDevice.matchesPeer(
    headset: Headset,
    candidate: GattPeerIdentity,
): Boolean {
    val source = headset.advertisement ?: GattPeerIdentity(
        headset.identity.deviceName, headset.address, headset.identity.serviceUuids,
    )
    return (allowSessionAddress || !headset.address.equals(candidate.deviceAddress, true)) &&
        (filter.manufacturerId == null || filter.manufacturerId in candidate.manufacturerData) &&
        (filter.serviceUuid == null || candidate.serviceUuids.any { it.equals(filter.serviceUuid, true) }) &&
        (filter.deviceName == null || filter.deviceName == candidate.deviceName) &&
        matcher.matches(source, candidate)
}

interface HeadsetRadio {
    fun discover(): List<Headset>
    fun open(headset: Headset, spec: EarbudTransportSpec): HeadsetLink
}

interface HeadsetLink : Closeable {
    /** Returns null on disconnect. Exactly one reader and one writer may run concurrently. */
    fun read(): ByteArray?
    fun write(frame: ByteArray)
}

data class HeadsetView(
    val status: String,
    val model: String? = null,
    val endpoint: String? = null,
    val battery: EarbudBattery = EarbudBattery(),
    val mode: NoiseMode? = null,
    val modes: Set<NoiseMode> = emptySet(),
    val ready: Boolean = false,
    val ended: Boolean = false,
)

fun NoiseMode.label(): String = when (this) {
    NoiseMode.ANC -> "降噪"
    NoiseMode.TRANSPARENCY -> "通透"
    NoiseMode.OFF -> "关闭"
    NoiseMode.WIND -> "抗风噪"
}

fun EarbudBattery.label(): String {
    fun item(name: String, value: BatteryReading) =
        value.percent?.let { "$name $it%${if (value.charging) "（充电中）" else ""}" }
    return if (overall.available) item("整机", overall).orEmpty()
    else listOfNotNull(item("左耳", left), item("右耳", right), item("充电盒", case))
        .joinToString("  ·  ").ifBlank { "电量未知" }
}

/** All adapter calls and outbound transactions run on the same executor. */
class HeadsetSession(
    private val radio: HeadsetRadio,
    private val headset: Headset,
    private val onView: (HeadsetView) -> Unit,
) : Closeable {
    private val stopped = AtomicBoolean()
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "UniEar protocol").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var adapter: EarbudAdapter? = null
    @Volatile private var link: HeadsetLink? = null
    private var endpoint: String? = null
    private var candidate = 0
    private var replacements = 0
    private var ready = false
    private var deadline: ScheduledFuture<*>? = null
    private val queries = mutableMapOf<String, ScheduledFuture<*>>()
    private var lastWrite = Long.MIN_VALUE
    private val failures = mutableListOf<String>()
    private val attemptedAdapters = mutableSetOf<String>()

    init {
        enqueue {
            adapter = EarbudAdapterRegistry.forIntegration(headset.identity)
            connectNext()
        }
    }

    fun setNoiseMode(mode: NoiseMode) = control(StandardControlRequest.SetNoiseMode(mode))

    fun refresh() = control(StandardControlRequest.Refresh)

    private fun enqueue(action: () -> Unit) {
        if (!stopped.get()) {
            try {
                worker.execute { if (!stopped.get()) action() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Close can race a Bluetooth callback.
            }
        }
    }

    private fun connectNext() {
        val current = adapter
        if (current == null || !current.privateProtocolRequired) {
            publish(failures.distinct().joinToString("\n").ifBlank {
                "此设备没有匹配的专有控制协议"
            }, ended = true)
            return
        }
        attemptedAdapters.add(current.id)
        while (!stopped.get() && candidate < current.transports.size) {
            val spec = current.transports[candidate++]
            endpoint = spec.id
            publish("正在连接…")
            try {
                current.resetProtocolSession()
                val opened = radio.open(headset, spec)
                link = opened
                if (stopped.get()) {
                    closeLink()
                    return
                }
                startReader(opened)
                begin(current, opened)
                return
            } catch (error: Exception) {
                failures.add("${spec.id}：${error.message ?: error.javaClass.simpleName}")
                closeLink()
            }
        }
        endpoint = null
        val fallback = current.resolveInitialProtocolFailure()
        if (fallback is InitialProtocolFailureResolution.FallbackTo && fallback.adapter.id !in attemptedAdapters) {
            fallback.adapter.adoptRuntimeState(current.runtimeState())
            adapter = fallback.adapter
            candidate = 0
            connectNext()
            return
        }
        publish(failures.distinct().joinToString("\n").ifBlank { "没有可用的控制通道" }, ended = true)
    }

    private fun begin(current: EarbudAdapter, opened: HeadsetLink) {
        val initial = current.beginHandshake()
        send(opened, initial.commands, 120)
        when (initial.handshake) {
            HandshakeResult.Ready -> markReady()
            HandshakeResult.AwaitingEvidence, null -> {
                publish("正在确认耳机协议…")
                deadline?.cancel(false)
                deadline = worker.schedule({
                    if (link === opened && !ready) fail(opened, "等待协议响应超时")
                }, 8000, MILLISECONDS)
            }
            else -> throw IOException("耳机协议初始化失败")
        }
    }

    private fun startReader(opened: HeadsetLink) {
        Thread({
            try {
                while (!stopped.get() && link === opened) {
                    val frame = opened.read() ?: break
                    enqueue { receive(opened, frame) }
                }
                enqueue { fail(opened, "耳机已断开") }
            } catch (error: Exception) {
                enqueue { fail(opened, error.message ?: "读取蓝牙数据失败") }
            }
        }, "UniEar Bluetooth receive").apply { isDaemon = true }.start()
    }

    private fun receive(opened: HeadsetLink, frame: ByteArray) {
        if (link !== opened) return
        val current = adapter ?: return
        try {
            val result = current.receive(frame)
            send(opened, result.commands, 0)
            when (val handshake = result.handshake) {
                HandshakeResult.Ready -> markReady()
                HandshakeResult.Rejected -> throw IOException("耳机拒绝协议握手")
                is HandshakeResult.Replace -> replace(opened, handshake)
                else -> Unit
            }
            if (adapter === current) {
                scheduleEffects(current, opened, result.effects)
                if (result.stateChanged && ready) publish("已连接")
            }
        } catch (error: Exception) {
            fail(opened, error.message ?: "处理耳机响应失败")
        }
    }

    private fun replace(opened: HeadsetLink, replacement: HandshakeResult.Replace) {
        check(++replacements <= 8) { "型号识别反复切换" }
        cancelQueries()
        deadline?.cancel(false)
        ready = false
        adapter = replacement.adapter
        when (replacement.activation) {
            AdapterActivation.KEEP_CHANNEL_READY -> markReady()
            AdapterActivation.RESTART_ON_CURRENT_CHANNEL -> {
                candidate = 0
                begin(replacement.adapter, opened)
            }
            AdapterActivation.RECONNECT -> {
                closeLink()
                candidate = 0
                connectNext()
            }
        }
    }

    private fun markReady() {
        ready = true
        deadline?.cancel(false)
        publish("已连接")
    }

    private fun control(request: ControlRequest) = enqueue {
        val current = adapter ?: return@enqueue
        val opened = link ?: return@enqueue
        if (!ready || !current.supportsControl(request)) return@enqueue
        val policy = current.controlPolicy(request)
        val now = System.nanoTime()
        if (lastWrite != Long.MIN_VALUE &&
            now - lastWrite < MILLISECONDS.toNanos(policy.cooldownMs)) return@enqueue
        try {
            val result = current.executeControl(request)
            if (result.accepted) {
                send(opened, result.commands, policy.commandGapMs)
                lastWrite = System.nanoTime()
                scheduleEffects(current, opened, current.controlWritten(request))
                publish("已连接")
                if (result.readback.isNotEmpty()) {
                    Thread.sleep(policy.readbackDelayMs)
                    send(opened, result.readback, 120)
                }
            }
        } catch (error: Exception) {
            fail(opened, error.message ?: "发送指令失败")
        }
    }

    private fun scheduleEffects(
        current: EarbudAdapter,
        opened: HeadsetLink,
        effects: List<AdapterEffect>,
    ) {
        for (effect in effects) when (effect) {
            is AdapterEffect.CancelStateRequest -> queries.remove(effect.featureId)?.cancel(false)
            is AdapterEffect.RequestState -> {
                queries.remove(effect.featureId)?.cancel(false)
                queries[effect.featureId] = worker.schedule({
                    queries.remove(effect.featureId)
                    if (link === opened && adapter === current) {
                        try {
                            send(opened, current.queryState(effect.featureId), 120)
                        } catch (error: Exception) {
                            fail(opened, error.message ?: "读取状态失败")
                        }
                    }
                }, effect.delayMs, MILLISECONDS)
            }
        }
    }

    private fun send(opened: HeadsetLink, frames: List<ByteArray>, gapMs: Long) {
        frames.forEachIndexed { index, frame ->
            if (index > 0 && gapMs > 0) Thread.sleep(gapMs)
            if (stopped.get() || link !== opened) throw IOException("连接已关闭")
            opened.write(frame)
        }
    }

    private fun fail(opened: HeadsetLink, message: String) {
        if (link !== opened || stopped.get()) return
        val wasReady = ready
        failures.add("${endpoint ?: "蓝牙"}：$message")
        closeLink()
        if (wasReady) {
            endpoint = null
            publish(message, ended = true)
        } else connectNext()
    }

    private fun cancelQueries() {
        queries.values.forEach { it.cancel(false) }
        queries.clear()
    }

    private fun closeLink() {
        deadline?.cancel(false)
        cancelQueries()
        ready = false
        lastWrite = Long.MIN_VALUE
        val previous = link
        link = null
        previous?.let { runCatching { it.close() } }
    }

    private fun publish(status: String, ended: Boolean = false) {
        if (stopped.get()) return
        val current = adapter
        val state = current?.runtimeState()
        val modes = if (ready && current != null) {
            current.effectiveSupportedNoiseModes().filter {
                current.supportsControl(StandardControlRequest.SetNoiseMode(it))
            }.toSet()
        } else emptySet()
        onView(HeadsetView(
            status, current?.displayName, endpoint, state?.battery ?: EarbudBattery(),
            state?.noiseMode, modes, ready, ended,
        ))
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            worker.shutdownNow()
            link?.let { runCatching { it.close() } }
        }
    }
}
