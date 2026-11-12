package uniear.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import dev.hyperears.integration.*
import uniear.core.Headset
import uniear.core.HeadsetLink
import uniear.core.HeadsetRadio
import uniear.core.matchesPeer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
internal class AndroidRadio(context: Context) : HeadsetRadio {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf(BluetoothAdapter::isEnabled) ?: throw IOException("请先开启蓝牙")

    override fun discover(): List<Headset> {
        val devices = adapter.bondedDevices.associate { it.address to headset(it) }.toMutableMap()
        try {
            scan(4000) { device, advertisement ->
                val identity = devices[device.address]?.identity ?: headset(device).identity
                devices[device.address] = Headset(identity.copy(
                    deviceName = identity.deviceName ?: advertisement.deviceName,
                    serviceUuids = identity.serviceUuids + advertisement.serviceUuids,
                ), advertisement)
                false
            }
        } catch (error: IOException) {
            if (devices.isEmpty()) throw error
        }
        return devices.values.filter { it.identity.deviceName != null }.sortedBy { it.toString() }
    }

    private fun headset(device: BluetoothDevice): Headset = Headset(EarbudIdentity(
        deviceName = device.name,
        standardHeadset = true,
        deviceAddress = device.address,
        bluetoothDeviceClass = device.bluetoothClass?.deviceClass,
        serviceUuids = device.uuids.orEmpty().map { it.uuid.toString() }.toSet(),
    ))

    private fun scan(timeout: Long, match: (BluetoothDevice, GattPeerIdentity) -> Boolean): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("此设备的 BLE 扫描不可用")
        val events = LinkedBlockingQueue<Result<ScanResult>>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                events.offer(Result.success(result))
            }
            override fun onScanFailed(errorCode: Int) {
                events.offer(Result.failure(IOException("BLE 扫描失败（$errorCode）")))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        try {
            while (true) {
                val event = events.poll(maxOf(0L, end - System.nanoTime()), TimeUnit.NANOSECONDS)
                    ?: return null
                val result = event.getOrThrow()
                val record = result.scanRecord
                val mfr = record?.manufacturerSpecificData
                val advertisement = GattPeerIdentity(
                    record?.deviceName ?: result.device.name,
                    result.device.address,
                    record?.serviceUuids.orEmpty().map { it.uuid.toString() }.toSet(),
                    if (mfr == null) emptyMap() else (0 until mfr.size()).associate {
                        mfr.keyAt(it) to mfr.valueAt(it)
                    },
                )
                if (match(result.device, advertisement)) return result.device
            }
        } finally {
            scanner.stopScan(callback)
        }
    }

    override fun open(headset: Headset, spec: EarbudTransportSpec): HeadsetLink {
        val device = adapter.getRemoteDevice(headset.address)
        return when (spec) {
            is RfcommEndpointSpec.ServiceUuid -> {
                adapter.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(spec.uuid))
                val connect = FutureTask { socket.connect() }
                Thread(connect, "UniEar Android RFCOMM connect").apply { isDaemon = true }.start()
                try {
                    connect.get(13, TimeUnit.SECONDS)
                    SocketLink(socket)
                } catch (error: Exception) {
                    socket.close()
                    connect.cancel(true)
                    throw IOException(error.cause?.message ?: "RFCOMM 连接未完成", error)
                }
            }
            is GattTransportSpec -> {
                val peer = when (val selection = spec.peerSelection) {
                    GattPeerSelection.SessionDevice -> device
                    is GattPeerSelection.CompanionDevice -> {
                        scan(selection.scanTimeoutMs) { _, advertisement ->
                            selection.matchesPeer(headset, advertisement)
                        } ?: throw IOException("找不到关联的 BLE 控制端点")
                    }
                }
                GattLink(spec).apply { connect(app, peer) }
            }
            is RfcommEndpointSpec.Channel -> throw IOException("普通 Android 应用无法按固定 RFCOMM 通道号连接")
            is L2capEndpointSpec -> throw IOException("此版本的 Android 传输层未接入经典蓝牙 L2CAP")
        }
    }
}

@SuppressLint("MissingPermission")
private class SocketLink(private val socket: BluetoothSocket) : HeadsetLink {
    override fun read(): ByteArray? {
        val buffer = ByteArray(4096)
        val size = socket.inputStream.read(buffer)
        return if (size < 0) null else buffer.copyOf(size)
    }

    override fun write(frame: ByteArray) {
        socket.outputStream.write(frame)
        socket.outputStream.flush()
    }

    override fun close() = socket.close()
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private class GattLink(private val spec: GattTransportSpec) : HeadsetLink {
    private data class Reply(val operation: String, val status: Int)
    private val replies = LinkedBlockingQueue<Reply>()
    private val frames = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean()
    private var gatt: BluetoothGatt? = null
    private lateinit var writer: BluetoothGattCharacteristic
    private val response = spec.writeMode == GattWriteMode.WITH_RESPONSE
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                replies.offer(Reply("connect", status))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                replies.offer(Reply("disconnect", status))
                frames.offer(byteArrayOf())
            }
        }

        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            replies.offer(Reply("services", status))
        }

        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            replies.offer(Reply("descriptor", status))
        }

        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            replies.offer(Reply("write", status))
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) frames.offer(value.copyOf())
        }

        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onCharacteristicChanged(link, characteristic, it) }
        }
    }

    fun connect(context: Context, device: BluetoothDevice) {
        val link = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("无法打开 GATT")
        gatt = link
        try {
            await("connect", 13)
            check(link.discoverServices()) { "GATT 服务发现未启动" }
            await("services", 10)
            val characteristics = link.services
                .filter { spec.serviceUuid == null || it.uuid.toString().equals(spec.serviceUuid, true) }
                .flatMap { it.characteristics }
            val writeProperty = if (response) BluetoothGattCharacteristic.PROPERTY_WRITE
                else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            writer = select(characteristics, spec.writeCharacteristicUuid, spec.writeInstanceId, writeProperty)
                ?: throw IOException("找不到 GATT 写入特征")
            val reader = select(characteristics, spec.notifyCharacteristicUuid, spec.notifyInstanceId,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)
            if (reader == null) {
                if (spec.notificationsRequired) throw IOException("找不到 GATT 通知特征")
            } else {
                val descriptor = reader.getDescriptor(CCCD)
                val enabled = link.setCharacteristicNotification(reader, true)
                if (descriptor == null || !enabled) {
                    if (spec.notificationsRequired) throw IOException("无法启用 GATT 通知")
                } else {
                    val value = if (reader.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        link.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        descriptor.value = value
                        link.writeDescriptor(descriptor)
                    }
                    if (started) await("descriptor", 7)
                    else if (spec.notificationsRequired) throw IOException("无法订阅 GATT 通知")
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun select(
        items: List<BluetoothGattCharacteristic>, uuid: String, instance: Int?, properties: Int,
    ): BluetoothGattCharacteristic? {
        val matches = items.filter { it.uuid.toString().equals(uuid, true) && it.properties and properties != 0 }
        return if (instance == null) matches.singleOrNull() else matches.singleOrNull { it.instanceId == instance }
    }

    private fun await(operation: String, timeout: Long) {
        val reply = replies.poll(timeout, TimeUnit.SECONDS) ?: throw IOException("GATT $operation 超时")
        if (reply.operation != operation || reply.status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT $operation 失败（${reply.operation}，${reply.status}）")
        }
    }

    override fun read(): ByteArray? = frames.take().takeIf(ByteArray::isNotEmpty)

    override fun write(frame: ByteArray) {
        val link = gatt?.takeUnless { closed.get() } ?: throw IOException("GATT 已关闭")
        val type = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            link.writeCharacteristic(writer, frame, type) == BluetoothStatusCodes.SUCCESS
        } else {
            writer.writeType = type
            writer.value = frame
            link.writeCharacteristic(writer)
        }
        if (!started) throw IOException("GATT 写入未启动")
        await("write", 7)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frames.offer(byteArrayOf())
        replies.offer(Reply("disconnect", -1))
        gatt?.close()
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
