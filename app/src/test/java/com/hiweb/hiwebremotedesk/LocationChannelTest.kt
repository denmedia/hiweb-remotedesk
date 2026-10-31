package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.LocationChannel
import com.hiweb.hiwebremotedesk.rdp.LocationFix
import com.hiweb.hiwebremotedesk.rdp.LocationNumbers
import com.hiweb.hiwebremotedesk.rdp.LocationSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Канал геолокации MS-RDPEL: сжатые числа, рукопожатие SERVER_READY/CLIENT_READY, пакеты BASE_LOCATION3D, взвод и остановка. */
class LocationChannelTest {
    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun float4(v: Double) = ByteWriter().also { LocationNumbers.float4(it, v) }.toByteArray()

    private fun int4(v: Int) = ByteWriter().also { LocationNumbers.int4(it, v) }.toByteArray()

    private class FakeSource(var canStart: Boolean = true) : LocationSource {
        var feed: ((LocationFix) -> Unit)? = null
        var starts = 0
        var stops = 0

        override fun start(feed: (LocationFix) -> Unit): Boolean {
            starts++
            if (!canStart) return false
            this.feed = feed
            return true
        }

        override fun stop() {
            stops++
            feed = null
        }
    }

    /** Канал с записью всего, что он отправил серверу, и всех уведомлений о состоянии. */
    private class Rig(val source: FakeSource = FakeSource()) {
        val sent = ArrayList<ByteArray>()
        val states = ArrayList<Boolean>()
        val channel = LocationChannel(source, onState = { states += it })

        fun open() = channel.onOpen { sent += it }

        fun serverReady(version: Long, withFlags: Boolean = true) {
            val w = ByteWriter().u16le(1).u32le(if (withFlags) 14 else 10).u32le(version)
            if (withFlags) w.u32le(0L)
            channel.onMessage(w.toByteArray())
        }
    }

    @Test
    fun floatEncoding() {
        assertArrayEquals(hex(0x00), float4(0.0))
        assertArrayEquals(hex(0x01), float4(1.0))
        assertArrayEquals(hex(0x44, 0x05), float4(0.5))
        assertArrayEquals(hex(0x64, 0x0F), float4(-1.5))
        // 37,6173° = 376173 / 10^4: четыре байта, порядок 4.
        assertArrayEquals(hex(0xD0, 0x05, 0xBD, 0x6D), float4(37.6173))
    }

    @Test
    fun floatRoundTrip() {
        val values = listOf(
            0.0, 0.000001, 0.5, -0.5, 55.751244, 37.618423, -33.8688, 151.2093123, -179.99999, 180.0, 90.0, -90.0,
            12345.678, 3.5, 100.0, 67108863.0,
        )
        for (v in values) {
            val back = LocationNumbers.readFloat4(ByteReader(float4(v)))
            assertTrue("$v → $back", abs(back - v) <= 1e-5)
        }
        // Москва: порядок 6 — доли метра, ближе нужного.
        for (v in listOf(55.751244, 37.618423)) {
            assertTrue(abs(LocationNumbers.readFloat4(ByteReader(float4(v))) - v) <= 1e-6)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun floatTooLargeRejected() {
        float4(70_000_000.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun floatNotFiniteRejected() {
        float4(Double.NaN)
    }

    @Test
    fun intEncoding() {
        assertArrayEquals(hex(0x00), int4(0))
        assertArrayEquals(hex(0x05), int4(5))
        assertArrayEquals(hex(0x21), int4(-1))
        assertArrayEquals(hex(0x43, 0xE8), int4(1000))
        assertArrayEquals(hex(0x63, 0xE8), int4(-1000))
        assertArrayEquals(hex(0xDF, 0xFF, 0xFF, 0xFF), int4(0x1FFFFFFF))
        for (v in listOf(0, 1, -1, 31, 32, 8191, 8192, -2_000_000, 2_097_152, 0x1FFFFFFF, -0x1FFFFFFF)) {
            assertEquals(v, LocationNumbers.readInt4(ByteReader(int4(v))))
        }
    }

    @Test
    fun baseLocationVersion1() {
        val fix = LocationFix(0.5, -1.5, altitude = 5)
        assertArrayEquals(
            hex(0x03, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x44, 0x05, 0x64, 0x0F, 0x05),
            LocationChannel.encodeBase(fix, LocationChannel.VERSION_1),
        )
    }

    @Test
    fun baseLocationVersion2() {
        val fix = LocationFix(0.5, -1.5, altitude = 5, speed = 0.0, heading = 0.0, accuracy = 10.0, source = LocationChannel.SOURCE_GNSS)
        assertArrayEquals(
            hex(0x03, 0x00, 0x10, 0x00, 0x00, 0x00, 0x44, 0x05, 0x64, 0x0F, 0x05, 0x00, 0x00, 0x40, 0x0A, 0x03),
            LocationChannel.encodeBase(fix, LocationChannel.VERSION_2),
        )
    }

    @Test
    fun baseLocationFieldsDecode() {
        val fix = LocationFix(55.751244, 37.618423, altitude = 156, speed = 1.4, heading = 725.0, accuracy = 8.5, source = LocationChannel.SOURCE_WIFI)
        val bytes = LocationChannel.encodeBase(fix, LocationChannel.VERSION_2)
        val r = ByteReader(bytes)
        assertEquals(3, r.u16le())
        assertEquals(bytes.size.toLong(), r.u32le())
        assertEquals(55.751244, LocationNumbers.readFloat4(r), 1e-6)
        assertEquals(37.618423, LocationNumbers.readFloat4(r), 1e-6)
        assertEquals(156, LocationNumbers.readInt4(r))
        assertEquals(1.4, LocationNumbers.readFloat4(r), 1e-6)
        assertEquals(5.0, LocationNumbers.readFloat4(r), 1e-6) // курс приводится к 0…360
        assertEquals(8.5, LocationNumbers.readFloat4(r), 1e-6)
        assertEquals(LocationChannel.SOURCE_WIFI, r.u8())
        assertEquals(0, r.remaining)
    }

    @Test
    fun versionChoice() {
        assertEquals(LocationChannel.VERSION_2, LocationChannel.clientVersion(LocationChannel.VERSION_2))
        assertEquals(LocationChannel.VERSION_1, LocationChannel.clientVersion(LocationChannel.VERSION_1))
        assertEquals(LocationChannel.VERSION_2, LocationChannel.clientVersion(0x00030000L))
        assertEquals(LocationChannel.VERSION_1, LocationChannel.clientVersion(0x00000001L))
    }

    @Test
    fun handshakeAndUpdates() {
        val rig = Rig()
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        // Передача выключена: клиент геолокации не заявляет и датчики не включает.
        assertTrue(rig.sent.isEmpty())
        assertEquals(0, rig.source.starts)

        assertTrue(rig.channel.setEnabled(true))
        assertArrayEquals(hex(0x02, 0x00, 0x0E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00), rig.sent.single())
        assertEquals(1, rig.source.starts)
        assertTrue(rig.channel.active)
        assertEquals(listOf(true), rig.states)

        val fix = LocationFix(0.5, -1.5, altitude = 5, accuracy = 10.0, source = LocationChannel.SOURCE_GNSS)
        rig.source.feed!!(fix)
        assertEquals(2, rig.sent.size)
        assertArrayEquals(LocationChannel.encodeBase(fix, LocationChannel.VERSION_2), rig.sent[1])

        assertTrue(rig.channel.setEnabled(false))
        assertEquals(1, rig.source.stops)
        assertFalse(rig.channel.active)
        assertEquals(listOf(true, false), rig.states)
    }

    @Test
    fun armedBeforeServerReady() {
        val rig = Rig()
        assertTrue(rig.channel.setEnabled(true)) // канала ещё нет: взвод
        assertEquals(0, rig.source.starts)
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        assertEquals(1, rig.source.starts)
        assertEquals(1, rig.sent.size)
        assertEquals(listOf(true), rig.states)
    }

    @Test
    fun serverVersion1WithoutFlags() {
        val rig = Rig()
        rig.channel.setEnabled(true)
        rig.open()
        rig.serverReady(LocationChannel.VERSION_1, withFlags = false)
        assertArrayEquals(hex(0x02, 0x00, 0x0E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00), rig.sent.single())
        val fix = LocationFix(0.5, -1.5, altitude = 5)
        rig.source.feed!!(fix)
        assertArrayEquals(LocationChannel.encodeBase(fix, LocationChannel.VERSION_1), rig.sent[1])
    }

    @Test
    fun sourceCannotStart() {
        val rig = Rig(FakeSource(canStart = false))
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        assertFalse(rig.channel.setEnabled(true))
        assertFalse(rig.channel.active)
        assertTrue(rig.states.isEmpty())
    }

    @Test
    fun closeStopsTracking() {
        val rig = Rig()
        rig.channel.setEnabled(true)
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        rig.channel.onClose()
        assertEquals(1, rig.source.stops)
        assertFalse(rig.channel.ready)
        assertEquals(listOf(true, false), rig.states)
        // Windows открыла канал снова: рукопожатие повторяется, слежение запускается заново.
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        assertEquals(2, rig.source.starts)
        assertEquals(2, rig.sent.size) // по одному CLIENT_READY на каждое открытие
    }

    @Test
    fun noUpdatesAfterDisabled() {
        val rig = Rig()
        rig.channel.setEnabled(true)
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        val feed = rig.source.feed!!
        rig.channel.setEnabled(false)
        val before = rig.sent.size
        feed(LocationFix(1.0, 2.0)) // запоздавший отсчёт датчика
        assertEquals(before, rig.sent.size)
    }

    @Test
    fun releaseStopsSource() {
        val rig = Rig()
        rig.channel.setEnabled(true)
        rig.open()
        rig.serverReady(LocationChannel.VERSION_2)
        rig.channel.release()
        assertEquals(1, rig.source.stops)
        assertFalse(rig.channel.active)
    }
}
