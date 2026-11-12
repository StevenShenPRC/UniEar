package uniear.desktop

import dev.hyperears.integration.*
import uniear.core.Headset
import uniear.core.HeadsetLink
import uniear.core.HeadsetRadio
import uniear.core.matchesPeer
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val macAddress = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

internal class DesktopRadio : HeadsetRadio {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val python = System.getenv("UNIEAR_PYTHON") ?: if (windows) "python" else "python3"
    private val script = sequenceOf(
        System.getenv("UNIEAR_RADIO")?.let(::File),
        File("desktop/radio.py"),
        File(File(DesktopRadio::class.java.protectionDomain.codeSource.location.toURI())
            .parentFile.parentFile, "radio/radio.py"),
    ).filterNotNull().firstOrNull(File::isFile)

    private fun start(vararg args: String): Process {
        val helper = script ?: throw IOException("找不到桌面蓝牙组件 radio.py")
        return ProcessBuilder(listOf(python, "-u", helper.absolutePath) + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }

    override fun discover(): List<Headset> = discoverPeers()

    private fun discoverPeers(scanTimeMs: Long = 5000, paired: Boolean = true): List<Headset> {
        val process = start("discover", scanTimeMs.toString(), if (paired) "1" else "0")
        val records = LinkedBlockingQueue<String>()
        pump(process, records::offer)
        val peers = linkedMapOf<String, Headset>()
        val warnings = mutableListOf<String>()
        val limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scanTimeMs + 15_000)
        try {
            while (true) {
                val line = records.poll(maxOf(0L, limit - System.nanoTime()), TimeUnit.NANOSECONDS)
                    ?: throw IOException("扫描蓝牙设备超时")
                val fields = line.split('\t')
                when (fields[0]) {
                    "DONE" -> break
                    "ERR" -> throw IOException(decode(fields[1]))
                    "WARN" -> warnings.add(decode(fields[1]))
                    "EOF" -> throw IOException("蓝牙扫描进程已结束")
                    "PEER" -> {
                        val address = fields[1].uppercase()
                        val previous = peers[address]
                        val name = decode(fields[2]).ifBlank { previous?.identity?.deviceName.orEmpty() }
                        val uuids = fields[3].split(',').filter(String::isNotBlank).toSet()
                        val data = fields[4].split(',').filter(String::isNotBlank).associate {
                            val (id, hex) = it.split(':', limit = 2)
                            id.toInt() to parseHex(hex)
                        }
                        val paired = fields[5] == "1"
                        val identity = EarbudIdentity(name.ifBlank { null }, standardHeadset = true,
                            deviceAddress = address, serviceUuids = uuids + previous?.identity?.serviceUuids.orEmpty())
                        peers[address] = Headset(identity, if (paired) previous?.advertisement
                            else GattPeerIdentity(name.ifBlank { null }, address, uuids, data))
                    }
                }
            }
            if (peers.isEmpty() && warnings.isNotEmpty()) throw IOException(warnings.joinToString("\n"))
            return peers.values.filter { !paired || it.identity.deviceName != null }.sortedBy { it.toString() }
        } finally {
            process.destroy()
        }
    }

    override fun open(headset: Headset, spec: EarbudTransportSpec): HeadsetLink {
        val process = when (spec) {
            is RfcommEndpointSpec.ServiceUuid -> classic(headset, "rfcomm", spec.uuid, true)
            is RfcommEndpointSpec.Channel -> classic(headset, "rfcomm", spec.number.toString(), spec.secure)
            is L2capEndpointSpec -> classic(headset, "l2cap", spec.psm.toString(), spec.authenticated || spec.encrypted)
            is GattTransportSpec -> {
                val address = when (val selection = spec.peerSelection) {
                    GattPeerSelection.SessionDevice -> headset.address
                    is GattPeerSelection.CompanionDevice -> {
                        discoverPeers(selection.scanTimeoutMs, paired = false)
                            .mapNotNull(Headset::advertisement).firstOrNull { candidate ->
                            selection.matchesPeer(headset, candidate)
                        }?.deviceAddress ?: throw IOException("找不到关联的 BLE 控制端点")
                    }
                }
                start("gatt", address, spec.serviceUuid.orEmpty().lowercase(),
                    spec.writeCharacteristicUuid.lowercase(), spec.notifyCharacteristicUuid.lowercase(),
                    spec.writeInstanceId?.toString().orEmpty(), spec.notifyInstanceId?.toString().orEmpty(),
                    if (spec.writeMode == GattWriteMode.WITH_RESPONSE) "1" else "0",
                    if (spec.notificationsRequired) "1" else "0")
            }
        }
        val link = ProcessLink(process)
        return try {
            link.await("OK", 30)
            link
        } catch (error: Exception) {
            link.close()
            throw error
        }
    }

    private fun classic(headset: Headset, kind: String, endpoint: String, secure: Boolean): Process {
        if (!macAddress.matches(headset.address)) throw IOException("该设备没有经典蓝牙地址")
        return start(kind, headset.address, endpoint, if (secure) "1" else "0")
    }
}

private fun pump(process: Process, consume: (String) -> Unit) {
    Thread({
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { it.forEach(consume) }
        } finally {
            consume("EOF")
        }
    }, "UniEar desktop radio output").apply { isDaemon = true }.start()
}

private fun decode(value: String) = String(Base64.getDecoder().decode(value), Charsets.UTF_8)

private fun parseHex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private class ProcessLink(private val process: Process) : HeadsetLink {
    private val input = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val replies = LinkedBlockingQueue<String>()
    private val incoming = LinkedBlockingQueue<String>()

    init {
        pump(process) { line ->
            when (line.substringBefore('\t')) {
                "DATA" -> incoming.offer(line)
                "ERR", "EOF" -> {
                    replies.offer(line)
                    incoming.offer(line)
                }
                else -> replies.offer(line)
            }
        }
    }

    fun await(reply: String, seconds: Long) {
        val line = replies.poll(seconds, TimeUnit.SECONDS) ?: throw IOException("等待蓝牙操作超时")
        if (line == reply) return
        throw IOException(if (line.startsWith("ERR\t")) decode(line.substringAfter('\t')) else "蓝牙连接已关闭")
    }

    override fun read(): ByteArray? {
        val line = incoming.take()
        return when (line.substringBefore('\t')) {
            "DATA" -> parseHex(line.substringAfter('\t'))
            "ERR" -> throw IOException(decode(line.substringAfter('\t')))
            else -> null
        }
    }

    override fun write(frame: ByteArray) {
        input.write("WRITE\t" + frame.joinToString("") { "%02x".format(it.toInt() and 255) })
        input.newLine()
        input.flush()
        await("WROTE", 8)
    }

    override fun close() {
        process.destroyForcibly()
        incoming.offer("EOF")
        replies.offer("EOF")
    }
}
