package uniear.core

import dev.hyperears.integration.EarbudIdentity
import dev.hyperears.integration.EarbudTransportSpec
import dev.hyperears.integration.FloS2ControlRequest
import dev.hyperears.integration.FloS2SoundEffect
import dev.hyperears.integration.NoiseMode
import dev.hyperears.protocol.flos2.FloS2WireCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.HexFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

class HeadsetSessionTest {
    @Test
    fun unavailableVendorChannelKeepsSystemBatteryAndNeverSendsControls() {
        val radio = TestRadio()
        val views = LinkedBlockingQueue<HeadsetView>()
        HeadsetSession(radio, headset("OpenPro S2"), { views.offer(it) }).use { session ->
            val fallback = views.await { it.ready }
            assertFalse(fallback.ended)
            assertEquals(35, fallback.battery.overall.percent)
            assertTrue(fallback.modes.isEmpty())
            assertTrue(fallback.controls.isEmpty())
            assertTrue(fallback.controlApps.isNotEmpty())
            assertEquals(1, radio.opens)

            radio.cachedLevel = 47
            session.refresh()
            assertEquals(47, views.await { it.battery.overall.percent == 47 }.battery.overall.percent)
            radio.onBattery(null)
            assertTrue(views.await { !it.battery.overall.available }.ready)
            radio.onDisconnect()
            assertFalse(views.await { it.ended }.ready)
        }
        assertTrue(radio.observerClosed)
    }

    @Test
    fun confirmedPrivateBatteryAndSoundEffectUseTheAdapterReports() {
        val link = TestLink()
        val radio = TestRadio(link)
        val views = LinkedBlockingQueue<HeadsetView>()
        HeadsetSession(radio, headset("OpenPro S2"), { views.offer(it) }).use { session ->
            assertArrayEquals(FloS2WireCodec.queryAllStatus, link.writes.poll(5, SECONDS))
            assertFalse(views.await { it.endpoint != null }.ready)
            link.frames.offer(HexFormat.of().parseHex(OPENPRO_STATUS))
            val confirmed = views.await { it.ready }
            assertEquals(70, confirmed.battery.overall.percent)
            assertTrue(confirmed.modes.isEmpty())
            val command = FloS2ControlRequest.SetSoundEffect(FloS2SoundEffect.ENHANCED)
            assertNotNull(confirmed.controls.flatMap { it.choices }.find { it.request == command })

            radio.onBattery(18)
            session.setNoiseMode(NoiseMode.ANC)
            session.control(command)
            assertArrayEquals(FloS2WireCodec.setSoundEffect(FloS2WireCodec.SoundEffect.ENHANCED), link.writes.poll(5, SECONDS))
            assertArrayEquals(FloS2WireCodec.queryAllStatus, link.writes.poll(5, SECONDS))
            assertEquals(70, views.await { it.ready }.battery.overall.percent)
        }
    }

    private fun headset(name: String) = Headset(EarbudIdentity(name, true, deviceAddress = "00:11:22:33:44:55"))

    private fun LinkedBlockingQueue<HeadsetView>.await(predicate: (HeadsetView) -> Boolean): HeadsetView {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (true) {
            val view = poll(maxOf(0L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)
                ?: error("Timed out waiting for headset view")
            if (predicate(view)) return view
        }
    }

    private class TestRadio(private val link: HeadsetLink? = null) : HeadsetRadio {
        @Volatile var cachedLevel = 35
        var opens = 0
        var observerClosed = false
        lateinit var onBattery: (Int?) -> Unit
        lateinit var onDisconnect: () -> Unit

        override fun discover(): List<Headset> = emptyList()
        override fun open(headset: Headset, spec: EarbudTransportSpec): HeadsetLink {
            opens++
            return link ?: throw IOException("No vendor channel")
        }
        override fun cachedSystemBattery(headset: Headset): Int = cachedLevel
        override fun observeSystemProfile(
            headset: Headset,
            onBatteryChanged: (Int?) -> Unit,
            onDisconnected: () -> Unit,
        ): Closeable {
            onBattery = onBatteryChanged
            onDisconnect = onDisconnected
            return Closeable { observerClosed = true }
        }
    }

    private class TestLink : HeadsetLink {
        val frames = LinkedBlockingQueue<ByteArray>()
        val writes = LinkedBlockingQueue<ByteArray>()
        override fun read(): ByteArray? = frames.take().takeIf(ByteArray::isNotEmpty)
        override fun write(frame: ByteArray) { writes.offer(frame) }
        override fun close() { frames.offer(byteArrayOf()) }
    }

    private companion object {
        // Anonymized full-status capture from the pinned upstream FloS2EarbudAdapterTest.
        const val OPENPRO_STATUS =
            "AE66FF48A38DDB6AE001312E362E36312E302E30322E322E36FFFFFFFF" +
                "46003800727400011AB43C466C6F2053326655443322113200020000" +
                "010145303230303354455354303030303031003D08"
    }
}
