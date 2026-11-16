package uniear.core

import dev.hyperears.integration.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class Headset(val identity: EarbudIdentity, val advertisement: GattPeerIdentity? = null) {
    val address: String get() = requireNotNull(identity.deviceAddress)
    override fun toString() = "${identity.deviceName ?: tr("unknown_device")}  ·  $address"
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

    fun cachedSystemBattery(headset: Headset): Int? = null

    fun observeSystemProfile(
        headset: Headset,
        onBatteryChanged: (Int?) -> Unit,
        onDisconnected: () -> Unit,
    ): Closeable? = null
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
    val controls: List<ControlGroup> = emptyList(),
    val controlApps: List<ControlAppSpec> = emptyList(),
)

fun NoiseMode.label(): String = when (this) {
    NoiseMode.ANC -> tr("noise_canceling")
    NoiseMode.TRANSPARENCY -> tr("transparency")
    NoiseMode.OFF -> tr("off")
    NoiseMode.WIND -> tr("wind_reduction")
}

fun EarbudBattery.label(): String {
    fun item(name: String, value: BatteryReading) =
        value.percent?.let { "$name $it%${if (value.charging) tr("charging") else ""}" }
    return if (overall.available) item(tr("headphones"), overall).orEmpty()
    else listOfNotNull(item(tr("left"), left), item(tr("right"), right), item(tr("case"), case))
        .joinToString("  ·  ").ifBlank { tr("battery_level_unknown") }
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
    private val systemObserver = AtomicReference<Closeable?>()
    private var systemBattery: Int? = null
    private var systemOnly = false
    private var status = tr("identifying_headphones")
    private var endpoint: String? = null
    private var candidate = 0
    private var replacements = 0
    private var ready = false
    private var ended = false
    private var deadline: ScheduledFuture<*>? = null
    private val queries = mutableMapOf<String, ScheduledFuture<*>>()
    private var lastWrite = Long.MIN_VALUE
    private val failures = mutableListOf<String>()
    private val attemptedAdapters = mutableSetOf<String>()
    private val controlApps = linkedSetOf<ControlAppSpec>()

    init {
        enqueue {
            adapter = EarbudAdapterRegistry.forIntegration(headset.identity)
            if (adapter == null) {
                publish(tr("these_headphones_are_managed_by_system_bluetooth"), ended = true)
                return@enqueue
            }
            observeSystemProfile()
            refreshSystemBattery()
            connectNext()
        }
    }

    fun setNoiseMode(mode: NoiseMode) = control(StandardControlRequest.SetNoiseMode(mode))

    fun refresh() = control(StandardControlRequest.Refresh)

    private fun observeSystemProfile() {
        val observer = runCatching {
            radio.observeSystemProfile(headset, { level ->
                enqueue {
                    if (setSystemBattery(level)) publish(status)
                }
            }, {
                enqueue {
                    closeLink()
                    endpoint = null
                    systemBattery = null
                    publish(tr("headphones_disconnected"), ended = true)
                }
            })
        }.getOrNull()
        systemObserver.set(observer)
        if (stopped.get()) systemObserver.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun refreshSystemBattery(): Boolean = runCatching { radio.cachedSystemBattery(headset) }
        .getOrNull()?.takeIf { it in 0..100 }?.let(::setSystemBattery) ?: false

    private fun setSystemBattery(level: Int?): Boolean {
        val valid = level?.takeIf { it in 0..100 }
        if (systemBattery == valid) return false
        systemBattery = valid
        val current = adapter
        current?.onSystemBatteryChanged(valid)
        return systemOnly || current?.effectiveBatterySource() == BatterySource.SYSTEM_AGGREGATE
    }

    private fun enqueue(action: () -> Unit) {
        if (!stopped.get()) {
            try {
                worker.execute { if (!stopped.get() && !ended) action() }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Close can race a Bluetooth callback.
            }
        }
    }

    private fun connectNext() {
        val current = adapter ?: return
        if (stopped.get()) return
        controlApps.addAll(current.controlApps)
        if (!current.privateProtocolRequired) {
            useSystemProfile()
            return
        }
        attemptedAdapters.add(current.id)
        while (!stopped.get() && candidate < current.transports.size) {
            val spec = current.transports[candidate++]
            endpoint = spec.id
            publish(tr("connecting"))
            try {
                current.resetProtocolSession()
                current.onSystemBatteryChanged(systemBattery)
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
        useSystemProfile()
    }

    private fun useSystemProfile() {
        endpoint = null
        systemOnly = true
        ready = true
        val summary = if (failures.isEmpty()) tr("system_bluetooth") else tr("system_bluetooth_private_controls_unavailable")
        publish((listOf(summary) + failures.distinct()).joinToString("\n"))
    }

    private fun begin(current: EarbudAdapter, opened: HeadsetLink) {
        val initial = current.beginHandshake()
        send(opened, initial.commands, 120)
        when (initial.handshake) {
            HandshakeResult.Ready -> markReady()
            HandshakeResult.AwaitingEvidence, null -> {
                publish(tr("checking_the_headphone_protocol"))
                deadline?.cancel(false)
                deadline = worker.schedule({
                    if (link === opened && !ready) fail(opened, tr("timed_out_waiting_for_a_protocol_response"))
                }, 8000, MILLISECONDS)
            }
            else -> throw IOException(tr("could_not_initialize_the_headphone_protocol"))
        }
    }

    private fun startReader(opened: HeadsetLink) {
        Thread({
            try {
                while (!stopped.get() && link === opened) {
                    val frame = opened.read() ?: break
                    enqueue { receive(opened, frame) }
                }
                enqueue { fail(opened, tr("headphones_disconnected")) }
            } catch (error: Exception) {
                enqueue { fail(opened, error.message ?: tr("could_not_read_bluetooth_data")) }
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
                HandshakeResult.Rejected -> throw IOException(tr("the_headphones_rejected_the_protocol_handshake"))
                is HandshakeResult.Replace -> replace(opened, handshake)
                else -> Unit
            }
            if (adapter === current && link === opened) {
                scheduleEffects(current, opened, result.effects)
                if (result.stateChanged && ready && result.handshake != HandshakeResult.Ready) publish(status)
            }
        } catch (error: Exception) {
            fail(opened, error.message ?: tr("could_not_process_the_headphone_response"))
        }
    }

    private fun replace(opened: HeadsetLink, replacement: HandshakeResult.Replace) {
        check(++replacements <= 8) { tr("repeated_headphone_model_changes") }
        cancelQueries()
        deadline?.cancel(false)
        ready = false
        adapter = replacement.adapter
        controlApps.addAll(replacement.adapter.controlApps)
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
        publish(tr("connected"))
    }

    fun control(request: ControlRequest) = enqueue {
        val current = adapter ?: return@enqueue
        if (!ready) return@enqueue
        if (request === StandardControlRequest.Refresh &&
            (systemOnly || current.effectiveBatterySource() == BatterySource.SYSTEM_AGGREGATE)
        ) {
            if (refreshSystemBattery()) publish(status)
        }
        if (systemOnly) return@enqueue
        val opened = link ?: return@enqueue
        if (!current.supportsControl(request)) return@enqueue
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
                publish(status)
                if (result.readback.isNotEmpty()) {
                    Thread.sleep(policy.readbackDelayMs)
                    send(opened, result.readback, 120)
                }
            }
        } catch (error: Exception) {
            fail(opened, error.message ?: tr("could_not_send_the_command"))
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
                            fail(opened, error.message ?: tr("could_not_read_status"))
                        }
                    }
                }, effect.delayMs, MILLISECONDS)
            }
        }
    }

    private fun send(opened: HeadsetLink, frames: List<ByteArray>, gapMs: Long) {
        frames.forEachIndexed { index, frame ->
            if (index > 0 && gapMs > 0) Thread.sleep(gapMs)
            if (stopped.get() || link !== opened) throw IOException(tr("connection_closed"))
            opened.write(frame)
        }
    }

    private fun fail(opened: HeadsetLink, message: String) {
        if (link !== opened || stopped.get()) return
        val wasReady = ready
        failures.add("${endpoint ?: tr("bluetooth")}：$message")
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
        this.status = status
        this.ended = ended
        val current = adapter
        val state = current?.runtimeState()
        val privateReady = ready && !systemOnly
        val modes = if (privateReady && current != null) {
            current.effectiveSupportedNoiseModes().filter {
                current.supportsControl(StandardControlRequest.SetNoiseMode(it))
            }.toSet()
        } else emptySet()
        val battery = if (!ended && (systemOnly || current?.effectiveBatterySource() == BatterySource.SYSTEM_AGGREGATE)) {
            EarbudBattery.fromSystemAggregate(systemBattery)
        } else if (privateReady) state?.battery ?: EarbudBattery() else EarbudBattery()
        onView(HeadsetView(
            status, headset.identity.deviceName.takeIf { current?.resolution == AdapterResolution.STANDARD }
                ?: current?.displayName, endpoint, battery,
            state?.noiseMode?.takeIf { it in modes }, modes, ready, ended,
            if (privateReady) current?.controlGroups().orEmpty() else emptyList(),
            controlApps.toList(),
        ))
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            worker.shutdownNow()
            systemObserver.getAndSet(null)?.let { runCatching { it.close() } }
            link?.let { runCatching { it.close() } }
        }
    }
}
