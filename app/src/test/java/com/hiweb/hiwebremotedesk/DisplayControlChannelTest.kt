package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.DisplayControlChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayControlChannelTest {
    private fun caps() = ByteWriter().u32le(5).u32le(20).u32le(16).u32le(8192).u32le(8192).toByteArray()

    @Test
    fun monitorLayoutMatchesSpecLayout() {
        val r = ByteReader(DisplayControlChannel.monitorLayout(1200, 1920, 185, 296, 150))
        assertEquals(2L, r.u32le())      // DISPLAYCONTROL_PDU_TYPE_MONITOR_LAYOUT
        assertEquals(56L, r.u32le())     // длина всего PDU
        assertEquals(40L, r.u32le())     // MonitorLayoutSize
        assertEquals(1L, r.u32le())      // NumMonitors
        assertEquals(1L, r.u32le())      // PRIMARY
        assertEquals(0L, r.u32le()); assertEquals(0L, r.u32le())
        assertEquals(1200L, r.u32le()); assertEquals(1920L, r.u32le())
        assertEquals(185L, r.u32le()); assertEquals(296L, r.u32le())
        assertEquals(0L, r.u32le())      // ориентация
        assertEquals(150L, r.u32le()); assertEquals(100L, r.u32le())
        assertEquals(0, r.remaining)
    }

    @Test
    fun requestBeforeCapsIsSentAfterCaps() {
        val sent = ArrayList<ByteArray>()
        val ch = DisplayControlChannel()
        ch.onOpen { sent += it }
        assertTrue(ch.requestSize(1600, 1920, 0, 0, 100))
        assertTrue(ch.requestSize(1200, 1920, 0, 0, 100)) // последний запрос заменяет прежний
        assertTrue(sent.isEmpty())
        ch.onMessage(caps())
        assertEquals(1, sent.size)
        val r = ByteReader(sent[0], 28)
        assertEquals(1200L, r.u32le())
    }

    @Test
    fun requestAfterCapsIsSentImmediately() {
        val sent = ArrayList<ByteArray>()
        val ch = DisplayControlChannel()
        ch.onOpen { sent += it }
        ch.onMessage(caps())
        assertTrue(ch.requestSize(1920, 1200, 0, 0, 100))
        assertEquals(1, sent.size)
    }

    @Test
    fun rejectsOddOrOutOfRangeSizes() {
        val ch = DisplayControlChannel()
        assertFalse(ch.requestSize(1201, 1920, 0, 0, 100))
        assertFalse(ch.requestSize(100, 1920, 0, 0, 100))
        assertFalse(ch.requestSize(1200, 9000, 0, 0, 100))
    }
}
