package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.agent.AgentInstaller
import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.DriveChannel
import com.hiweb.hiwebremotedesk.rdp.DvcHandler
import com.hiweb.hiwebremotedesk.rdp.DynamicChannel
import com.hiweb.hiwebremotedesk.rdp.MemoryDriveStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Транспорт для HiWeb Agent: временный диск с установщиком (rdpdr) и длинные сообщения динамического канала. */
class AgentPlumbingTest {
    private val sent = ArrayList<ByteArray>()

    private fun feedStatic(onData: (ByteReader) -> Unit, pdu: ByteArray) =
        onData(ByteReader(ByteWriter().u32le(pdu.size).u32le(3).bytes(pdu).toByteArray()))

    private fun core(packetId: Int) = ByteWriter().u16le(0x4472).u16le(packetId)
    private fun packetId(pdu: ByteArray) = ByteReader(pdu, 2).u16le()

    private fun handshake(ch: DriveChannel) {
        feedStatic(ch::onChannelData, core(0x496E).u16le(1).u16le(0x0D).u32le(7).toByteArray())
        val caps = core(0x5350).u16le(2).u16le(0)
        caps.u16le(1).u16le(44).u32le(2).u32le(0).u32le(0).u16le(1).u16le(0x0D).u32le(0xFFFF).u32le(0).u32le(7).u32le(0).u32le(0).u32le(0)
        caps.u16le(4).u16le(8).u32le(2)
        feedStatic(ch::onChannelData, caps.toByteArray())
        feedStatic(ch::onChannelData, core(0x4343).u16le(1).u16le(0x0D).u32le(7).toByteArray())
        feedStatic(ch::onChannelData, core(0x554C).toByteArray())
    }

    @Test
    fun attachedDriveIsAnnouncedReadableAndRemoved() {
        val ch = DriveChannel({ sent += it }, null, "Tablet")
        handshake(ch)
        assertTrue("без папки устройства дисков не объявляется", sent.none { packetId(it) == 0x4441 })

        val exe = ByteArray(5000) { (it * 7).toByte() }
        sent.clear()
        ch.attach(AgentInstaller.DRIVE_ID, AgentInstaller.DRIVE_NAME, MemoryDriveStorage(mapOf(AgentInstaller.EXE_NAME to exe)))
        val announce = sent.single()
        assertEquals(0x4441, packetId(announce))
        val r = ByteReader(announce, 4)
        assertEquals(1L, r.u32le())
        assertEquals(8L, r.u32le()) // файловая система
        assertEquals(2L, r.u32le())
        assertEquals("HiWeb", String(r.bytes(8), Charsets.US_ASCII).trimEnd('\u0000'))
        assertTrue(ch.isAnnounced(2))

        // Открываем \HiWebAgent.exe на устройстве 2 и читаем середину файла.
        val path = "\\HiWebAgent.exe"
        val createBody = ByteWriter().u32le(0x120089).u64le(0).u32le(0).u32le(7).u32le(1).u32le(0x40)
            .u32le((path.length + 1) * 2).utf16(path).u16le(0).toByteArray()
        val created = irp(ch, device = 2, major = 0, body = createBody)
        assertEquals(0L, created.first)
        val fileId = ByteReader(created.second).u32le().toInt()
        val read = irp(ch, device = 2, major = 3, fileId = fileId, body = ByteWriter().u32le(100).u64le(4000).zeros(20).toByteArray())
        assertEquals(0L, read.first)
        val rd = ByteReader(read.second)
        assertEquals(100L, rd.u32le())
        assertArrayEquals(exe.copyOfRange(4000, 4100), rd.bytes(100))

        // Запись на диск установщика запрещена.
        val write = irp(ch, device = 2, major = 0, body = ByteWriter().u32le(0x40000000).u64le(0).u32le(0).u32le(7).u32le(2).u32le(0x40)
            .u32le(10).utf16("\\x.t").u16le(0).toByteArray())
        assertEquals(0xC0000022L, write.first)

        sent.clear()
        ch.detach(2)
        val remove = sent.single()
        assertEquals(0x444D, packetId(remove))
        val rr = ByteReader(remove, 4)
        assertEquals(1L, rr.u32le())
        assertEquals(2L, rr.u32le())
        assertFalse(ch.isAnnounced(2))
        assertEquals(0xC000000EL, irp(ch, device = 2, major = 3, fileId = fileId, body = ByteWriter().u32le(1).u64le(0).zeros(20).toByteArray()).first)
    }

    @Test
    fun driveAttachedBeforeLogonIsAnnouncedAfterIt() {
        val ch = DriveChannel({ sent += it }, null, "Tablet")
        ch.attach(2, "HiWeb", MemoryDriveStorage(mapOf("a.exe" to ByteArray(1))))
        assertTrue(sent.isEmpty())
        handshake(ch)
        assertEquals(1, sent.count { packetId(it) == 0x4441 })
    }

    private fun irp(ch: DriveChannel, device: Int, major: Int, fileId: Int = 0, body: ByteArray): Pair<Long, ByteArray> {
        sent.clear()
        feedStatic(ch::onChannelData, core(0x4952).u32le(device).u32le(fileId).u32le(1).u32le(major).u32le(0).bytes(body).toByteArray())
        val r = sent.single()
        assertEquals(0x4943, packetId(r))
        assertEquals(device.toLong(), ByteReader(r, 4).u32le())
        return ByteReader(r, 12).u32le() to r.copyOfRange(16, r.size)
    }

    // ------------------------------------------------------------------ динамический канал

    private class Recorder : DvcHandler {
        var write: ((ByteArray) -> Unit)? = null
        val messages = ArrayList<ByteArray>()
        var closed = false
        override fun onOpen(write: (ByteArray) -> Unit) { this.write = write }
        override fun onMessage(data: ByteArray) { messages += data }
        override fun onClose() { closed = true }
    }

    private fun feedDvc(ch: DynamicChannel, pdu: ByteArray) = feedStatic(ch::onChannelData, pdu)

    @Test
    fun longMessagesAreFragmentedWithinChunkLimit() {
        val handler = Recorder()
        val ch = DynamicChannel({ sent += it }, mapOf("HiWeb::Agent" to handler), log = {})
        feedDvc(ch, ByteWriter().u8(0x10).u8(5).bytes("HiWeb::Agent\u0000".toByteArray()).toByteArray()) // CREATE, id 5 (1 байт)
        sent.clear()

        val msg = ByteArray(4000) { it.toByte() }
        handler.write!!(msg)
        assertTrue(sent.size >= 3)
        assertTrue(sent.all { it.size <= DynamicChannel.MAX_PDU })
        // Первый — DATA_FIRST с полной длиной (4 байта), дальше DATA; склеиваем обратно.
        assertEquals(0x28, sent[0][0].toInt() and 0xFF)
        assertEquals(5, sent[0][1].toInt())
        assertEquals(4000L, ByteReader(sent[0], 2).u32le())
        val joined = ByteWriter().bytes(sent[0], 6, sent[0].size - 6)
        for (p in sent.drop(1)) {
            assertEquals(0x30, p[0].toInt() and 0xFF)
            joined.bytes(p, 2, p.size - 2)
        }
        assertArrayEquals(msg, joined.toByteArray())

        // Короткое сообщение — одним DATA.
        sent.clear()
        handler.write!!(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(0x30, 5, 1, 2, 3), sent.single())

        // Сервер собирает наши фрагменты так же, как мы его: проверяем приём DATA_FIRST + DATA.
        val first = ByteWriter().u8(0x24).u8(5).u16le(6).bytes(byteArrayOf(9, 8, 7)).toByteArray()
        feedDvc(ch, first)
        feedDvc(ch, ByteWriter().u8(0x30).u8(5).bytes(byteArrayOf(6, 5, 4)).toByteArray())
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5, 4), handler.messages.single())
    }

    @Test
    fun serverCloseIsAnsweredAndReported() {
        val handler = Recorder()
        val ch = DynamicChannel({ sent += it }, mapOf("HiWeb::Agent" to handler), log = {})
        feedDvc(ch, ByteWriter().u8(0x10).u8(5).bytes("HiWeb::Agent\u0000".toByteArray()).toByteArray())
        sent.clear()
        feedDvc(ch, byteArrayOf(0x40, 5))
        assertArrayEquals(byteArrayOf(0x40, 5), sent.single())
        assertTrue(handler.closed)
    }

    @Test
    fun versionComparison() {
        assertTrue(AgentInstaller.isOutdated("1.0.0", "1.0.1"))
        assertTrue(AgentInstaller.isOutdated("1.9", "1.10"))
        assertFalse(AgentInstaller.isOutdated("1.0.0", "1.0"))
        assertFalse(AgentInstaller.isOutdated("2.0.0", "1.5.0"))
    }
}
