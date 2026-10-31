package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.PenFrame
import com.hiweb.hiwebremotedesk.rdp.PenPoint
import com.hiweb.hiwebremotedesk.rdp.RdpeiChannel
import com.hiweb.hiwebremotedesk.rdp.RdpeiFlags
import com.hiweb.hiwebremotedesk.rdp.RdpeiInts
import com.hiweb.hiwebremotedesk.rdp.TouchFrame
import com.hiweb.hiwebremotedesk.rdp.TouchPoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Канал RDPEI: сжатые целые, рукопожатие SC_READY/CS_READY, кадры касаний и пера. */
class RdpeiChannelTest {
    private fun enc(block: (ByteWriter) -> Unit) = ByteWriter().also(block).toByteArray()

    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun compressedIntegers() {
        // TWO_BYTE_UNSIGNED
        assertArrayEquals(hex(0x00), enc { RdpeiInts.u2(it, 0) })
        assertArrayEquals(hex(0x7F), enc { RdpeiInts.u2(it, 0x7F) })
        assertArrayEquals(hex(0x80, 0x80), enc { RdpeiInts.u2(it, 0x80) })
        assertArrayEquals(hex(0xFF, 0xFF), enc { RdpeiInts.u2(it, 0x7FFF) })
        // TWO_BYTE_SIGNED
        assertArrayEquals(hex(0x45), enc { RdpeiInts.s2(it, -5) })
        assertArrayEquals(hex(0x80, 0x40), enc { RdpeiInts.s2(it, 0x40) })
        // FOUR_BYTE_UNSIGNED
        assertArrayEquals(hex(0x3F), enc { RdpeiInts.u4(it, 0x3F) })
        assertArrayEquals(hex(0x40, 0x40), enc { RdpeiInts.u4(it, 0x40) })
        assertArrayEquals(hex(0x80, 0x40, 0x00), enc { RdpeiInts.u4(it, 0x4000) })
        assertArrayEquals(hex(0xC0, 0x40, 0x00, 0x00), enc { RdpeiInts.u4(it, 0x400000) })
        // FOUR_BYTE_SIGNED
        assertArrayEquals(hex(0x21), enc { RdpeiInts.s4(it, -1) })
        assertArrayEquals(hex(0x01), enc { RdpeiInts.s4(it, 1) })
        assertArrayEquals(hex(0x43, 0xE8), enc { RdpeiInts.s4(it, 1000) })
        assertArrayEquals(hex(0x63, 0xE8), enc { RdpeiInts.s4(it, -1000) })
        // EIGHT_BYTE_UNSIGNED
        assertArrayEquals(hex(0x1F), enc { RdpeiInts.u8(it, 0x1F) })
        assertArrayEquals(hex(0x20, 0x20), enc { RdpeiInts.u8(it, 0x20) })
        assertArrayEquals(hex(0x40, 0x20, 0x00), enc { RdpeiInts.u8(it, 0x2000) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooLargeIntegerRejected() {
        enc { RdpeiInts.u2(it, 0x8000) }
    }

    private class Wire {
        val sent = ArrayList<ByteArray>()
        var states = ArrayList<Pair<Boolean, Boolean>>()
    }

    private fun open(w: Wire): RdpeiChannel {
        val ch = RdpeiChannel(onState = { ok, pen -> w.states += ok to pen })
        ch.onOpen { w.sent += it }
        return ch
    }

    private fun scReady(version: Long, features: Long? = null): ByteArray {
        val body = ByteWriter().u32le(version)
        if (features != null) body.u32le(features)
        val b = body.toByteArray()
        return ByteWriter().u16le(1).u32le(6 + b.size).bytes(b).toByteArray()
    }

    @Test
    fun handshakeAnswersCsReady() {
        val w = Wire()
        val ch = open(w)
        assertFalse(ch.ready)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3, RdpeiChannel.SC_PEN))
        assertTrue(ch.ready)
        assertTrue(ch.penSupported)
        assertEquals(listOf(true to true), w.states)
        val pdu = w.sent.single()
        // eventId 2, длина 16, флаги = перо, версия 3.0, десять касаний
        assertArrayEquals(
            hex(0x02, 0x00, 0x10, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00, 0x0A, 0x00),
            pdu,
        )
    }

    @Test
    fun oldServerWithoutPen() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_1))
        assertTrue(ch.ready)
        assertFalse(ch.penSupported)
        // версия сервера повторяется, флаги пустые
        assertArrayEquals(
            hex(0x02, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x0A, 0x00),
            w.sent.single(),
        )
        assertFalse(ch.sendPen(listOf(PenFrame(1, PenPoint(0, 0, RdpeiFlags.DOWN, 0, 0)))))
    }

    @Test
    fun touchPduLayout() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3))
        w.sent.clear()
        val flags = RdpeiFlags.DOWN or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT
        assertTrue(ch.sendTouch(listOf(TouchFrame(1000, listOf(TouchPoint(0, 100, -2, flags))))))
        val pdu = w.sent.single()
        // eventId 3, длина, encodeTime 0, один кадр, один контакт, смещение 0,
        // id 0, поля 0, x=100 (0x40 0x64 → s4: c=1), y=-2 (0x22), флаги 0x19
        assertArrayEquals(
            hex(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x40, 0x64, 0x22, 0x19),
            pdu.copyOf(16).also { it[2] = 0; it[3] = 0 },
        )
        assertEquals(pdu.size, (pdu[2].toInt() and 0xFF) or ((pdu[3].toInt() and 0xFF) shl 8))
    }

    @Test
    fun frameOffsetInMicroseconds() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3))
        w.sent.clear()
        val p = listOf(TouchPoint(0, 1, 1, RdpeiFlags.UPDATE or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT))
        ch.sendTouch(listOf(TouchFrame(5000, p)))
        ch.sendTouch(listOf(TouchFrame(5008, p)))
        // второй кадр: 8 мс = 8000 мкс → EIGHT_BYTE: 0x20 | (8000 >> 8 = 0x1F), 0x40
        val second = w.sent[1]
        // header(6) + encodeTime(1) + frameCount(1) + contactCount(1) = 9 → смещение с индекса 9
        assertEquals(0x3F, second[9].toInt() and 0xFF)
        assertEquals(0x40, second[10].toInt() and 0xFF)
        // у первого кадра смещение нулевое
        assertEquals(0x00, w.sent[0][9].toInt() and 0xFF)
    }

    @Test
    fun suspendStopsInput() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3))
        val frame = listOf(TouchFrame(1, listOf(TouchPoint(0, 1, 1, RdpeiFlags.DOWN))))
        ch.onMessage(hex(0x04, 0x00, 0x06, 0x00, 0x00, 0x00))
        assertTrue(ch.suspended)
        assertFalse(ch.sendTouch(frame))
        ch.onMessage(hex(0x05, 0x00, 0x06, 0x00, 0x00, 0x00))
        assertFalse(ch.suspended)
        assertTrue(ch.sendTouch(frame))
    }

    @Test
    fun closeReportsNotReady() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3, RdpeiChannel.SC_PEN))
        ch.onClose()
        assertFalse(ch.ready)
        assertEquals(listOf(true to true, false to false), w.states)
        assertFalse(ch.sendTouch(listOf(TouchFrame(1, listOf(TouchPoint(0, 1, 1, RdpeiFlags.DOWN))))))
    }

    @Test
    fun penPduLayout() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3, RdpeiChannel.SC_PEN))
        w.sent.clear()
        val flags = RdpeiFlags.UPDATE or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT
        ch.sendPen(listOf(PenFrame(10, PenPoint(10, 20, flags, RdpeiFlags.PEN_BARREL, 512))))
        val pdu = w.sent.single()
        // eventId 8; encodeTime 0; кадров 1; контактов 1; смещение 0; id 0; поля = penFlags|pressure = 3;
        // x=10 (0x0A), y=20 (0x14), флаги 0x1A, penFlags 1, нажим 512 (0x42 0x00)
        assertArrayEquals(
            hex(0x08, 0x00, 0, 0, 0, 0, 0x00, 0x01, 0x01, 0x00, 0x00, 0x03, 0x0A, 0x14, 0x1A, 0x01, 0x42, 0x00),
            pdu.also { it[2] = 0; it[3] = 0 },
        )
    }

    @Test
    fun penTiltIsEncodedAfterPressure() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3, RdpeiChannel.SC_PEN))
        w.sent.clear()
        val flags = RdpeiFlags.UPDATE or RdpeiFlags.INRANGE
        ch.sendPen(listOf(PenFrame(10, PenPoint(10, 20, flags, 0, 512, tiltX = 30, tiltY = -20))))
        // поля = нажим|TILTX|TILTY = 0x1A; после нажима: наклон по X 30 (0x1E) и по Y −20 (знак 0x40 | 20 = 0x54)
        assertArrayEquals(
            hex(0x08, 0x00, 0, 0, 0, 0, 0x00, 0x01, 0x01, 0x00, 0x00, 0x1A, 0x0A, 0x14, 0x0A, 0x42, 0x00, 0x1E, 0x54),
            w.sent.single().also { it[2] = 0; it[3] = 0 },
        )
    }

    @Test
    fun penWithoutTiltOmitsTiltFields() {
        val w = Wire()
        val ch = open(w)
        ch.onMessage(scReady(RdpeiChannel.VERSION_3, RdpeiChannel.SC_PEN))
        w.sent.clear()
        ch.sendPen(listOf(PenFrame(10, PenPoint(10, 20, RdpeiFlags.UPDATE or RdpeiFlags.INRANGE, 0, 512))))
        // поля = только нажим (0x02): ни наклона, ни penFlags
        assertArrayEquals(
            hex(0x08, 0x00, 0, 0, 0, 0, 0x00, 0x01, 0x01, 0x00, 0x00, 0x02, 0x0A, 0x14, 0x0A, 0x42, 0x00),
            w.sent.single().also { it[2] = 0; it[3] = 0 },
        )
    }
}
