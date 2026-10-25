package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.DriveChannel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Проверка канала rdpdr на синтетических PDU сервера (MS-RDPEFS); диск — обычная временная папка. */
class DriveChannelTest {
    private lateinit var dir: File
    private val sent = ArrayList<ByteArray>()
    private var completion = 0

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("drive-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun channel(readOnly: Boolean = false) = DriveChannel({ sent += it }, FileDriveStorage(dir, readOnly), "Tablet")

    private fun feed(ch: DriveChannel, pdu: ByteArray) {
        ch.onChannelData(ByteReader(ByteWriter().u32le(pdu.size).u32le(3).bytes(pdu).toByteArray()))
    }

    private fun core(packetId: Int) = ByteWriter().u16le(0x4472).u16le(packetId)

    private fun serverAnnounce(minor: Int = 0x0D, clientId: Long = 0xABCD) =
        core(0x496E).u16le(1).u16le(minor).u32le(clientId).toByteArray()

    private fun serverCaps(withDrive: Boolean = true): ByteArray {
        val w = core(0x5350).u16le(if (withDrive) 2 else 1).u16le(0)
        w.u16le(1).u16le(44).u32le(2).u32le(0).u32le(0).u16le(1).u16le(0x0D).u32le(0xFFFF).u32le(0).u32le(7).u32le(0).u32le(0).u32le(0)
        if (withDrive) w.u16le(4).u16le(8).u32le(2)
        return w.toByteArray()
    }

    private fun handshake(ch: DriveChannel, loggedOn: Boolean = true) {
        feed(ch, serverAnnounce())
        feed(ch, serverCaps())
        feed(ch, core(0x4343).u16le(1).u16le(0x0D).u32le(0xABCD).toByteArray())
        if (loggedOn) feed(ch, core(0x554C).toByteArray())
    }

    private fun packetId(pdu: ByteArray) = ByteReader(pdu, 2).u16le()

    // ------------------------------------------------------------------ запросы ввода-вывода

    private class Reply(val status: Long, val body: ByteArray)

    private fun irp(ch: DriveChannel, major: Int, minor: Int = 0, fileId: Int = 0, body: ByteArray = ByteArray(0)): Reply {
        sent.clear()
        val id = ++completion
        feed(ch, core(0x4952).u32le(1).u32le(fileId).u32le(id).u32le(major).u32le(minor).bytes(body).toByteArray())
        val r = sent.single()
        assertEquals(0x4943, packetId(r))
        val rd = ByteReader(r, 4)
        assertEquals(1L, rd.u32le())
        assertEquals(id.toLong(), rd.u32le())
        val status = rd.u32le()
        return Reply(status, r.copyOfRange(16, r.size))
    }

    private fun createBody(path: String, disposition: Int, options: Int = 0, access: Long = 0x120089) =
        ByteWriter().u32le(access).u64le(0).u32le(0).u32le(7).u32le(disposition).u32le(options)
            .u32le((path.length + 1) * 2).utf16(path).u16le(0).toByteArray()

    private fun create(ch: DriveChannel, path: String, disposition: Int, options: Int = 0, access: Long = 0x120089): Pair<Long, Int> {
        val r = irp(ch, 0, body = createBody(path, disposition, options, access))
        return r.status to if (r.body.size >= 4) ByteReader(r.body).u32le().toInt() else 0
    }

    private fun write(ch: DriveChannel, id: Int, offset: Long, data: ByteArray) =
        irp(ch, 4, fileId = id, body = ByteWriter().u32le(data.size).u64le(offset).zeros(20).bytes(data).toByteArray())

    private fun read(ch: DriveChannel, id: Int, offset: Long, length: Int) =
        irp(ch, 3, fileId = id, body = ByteWriter().u32le(length).u64le(offset).zeros(20).toByteArray())

    private fun query(ch: DriveChannel, id: Int, kind: Int) =
        irp(ch, 5, fileId = id, body = ByteWriter().u32le(kind).u32le(0).zeros(24).toByteArray())

    private fun setInfo(ch: DriveChannel, id: Int, kind: Int, payload: ByteArray) =
        irp(ch, 6, fileId = id, body = ByteWriter().u32le(kind).u32le(payload.size).zeros(24).bytes(payload).toByteArray())

    private fun directory(ch: DriveChannel, id: Int, kind: Int, initial: Boolean, path: String): Reply {
        val raw = if (path.isEmpty()) ByteArray(0) else (path + "\u0000").toByteArray(Charsets.UTF_16LE)
        return irp(ch, 0x0C, minor = 1, fileId = id, body = ByteWriter().u32le(kind).u8(if (initial) 1 else 0).u32le(raw.size).zeros(23).bytes(raw).toByteArray())
    }

    private fun names(ch: DriveChannel, id: Int, pattern: String): List<String> {
        val out = ArrayList<String>()
        var r = directory(ch, id, 3, true, pattern)
        while (r.status == 0L) {
            val rd = ByteReader(r.body)
            rd.u32le() // длина
            rd.skip(4 + 4 + 8 * 6 + 4)
            val len = rd.u32le().toInt()
            rd.skip(4 + 1 + 24)
            out += String(rd.bytes(len), Charsets.UTF_16LE)
            r = directory(ch, id, 3, false, "")
        }
        assertEquals(0x80000006L, r.status) // STATUS_NO_MORE_FILES
        return out
    }

    // ------------------------------------------------------------------ рукопожатие

    @Test
    fun handshake_replies_in_order_and_announces_drive_after_logon() {
        val ch = channel()
        feed(ch, serverAnnounce())
        assertEquals(listOf(0x4343, 0x434E), sent.map(::packetId))
        // Идентификатор клиента повторяет серверный, версия — минимальная из согласованных
        val confirm = ByteReader(sent[0], 4)
        assertEquals(1, confirm.u16le())
        assertEquals(0x0D, confirm.u16le())
        assertEquals(0xABCDL, confirm.u32le())
        // Имя клиента: Unicode, кодовая страница 0, длина с нулевым символом
        val name = ByteReader(sent[1], 4)
        assertEquals(1L, name.u32le())
        assertEquals(0L, name.u32le())
        val nameLen = name.u32le().toInt()
        assertEquals("ANDROID\u0000", String(name.bytes(nameLen), Charsets.UTF_16LE))

        sent.clear()
        feed(ch, serverCaps())
        assertEquals(listOf(0x4350), sent.map(::packetId))
        val caps = ByteReader(sent[0], 4)
        assertEquals(2, caps.u16le()) // общие возможности и диски
        caps.skip(2)
        assertEquals(1, caps.u16le())
        assertEquals(44, caps.u16le())
        caps.skip(4 + 4 + 4 + 2 + 2) // версия набора, osType, osVersion, версия протокола
        assertEquals(0xFFFFL, caps.u32le()) // ioCode1
        caps.skip(4)
        assertEquals(7L, caps.u32le()) // extendedPDU: удаление устройств, имя клиента, вход пользователя
        caps.skip(4 + 4 + 4)
        assertEquals(4, caps.u16le()) // набор возможностей дисков
        assertEquals(8, caps.u16le())

        sent.clear()
        feed(ch, core(0x4343).u16le(1).u16le(0x0D).u32le(0xABCD).toByteArray())
        assertTrue("до входа в Windows диск не объявляется", sent.isEmpty())

        feed(ch, core(0x554C).toByteArray())
        assertEquals(listOf(0x4441), sent.map(::packetId))
        val list = ByteReader(sent[0], 4)
        assertEquals(1L, list.u32le())
        assertEquals(8L, list.u32le())       // RDPDR_DTYP_FILESYSTEM
        list.u32le()                          // идентификатор устройства
        assertEquals("Tablet\u0000\u0000", String(list.bytes(8), Charsets.US_ASCII))
        val dataLen = list.u32le().toInt()
        assertEquals("Tablet\u0000", String(list.bytes(dataLen), Charsets.US_ASCII))
    }

    @Test
    fun drive_is_announced_once_even_if_logon_comes_first() {
        val ch = channel()
        feed(ch, serverAnnounce())
        feed(ch, core(0x554C).toByteArray())
        sent.clear()
        feed(ch, serverCaps())
        feed(ch, core(0x4343).u16le(1).u16le(0x0D).u32le(0xABCD).toByteArray())
        assertEquals(listOf(0x4350, 0x4441), sent.map(::packetId))
    }

    @Test
    fun reannounce_after_new_server_announce() {
        val ch = channel()
        handshake(ch)
        sent.clear()
        handshake(ch)
        assertTrue(sent.any { packetId(it) == 0x4441 })
    }

    @Test
    fun capabilities_omit_drive_set_when_server_has_none() {
        val ch = channel()
        feed(ch, serverAnnounce())
        sent.clear()
        feed(ch, serverCaps(withDrive = false))
        assertEquals(1, ByteReader(sent[0], 4).u16le())
    }

    @Test
    fun drive_name_is_ascii_without_forbidden_characters() {
        assertEquals("My_Disk", DriveChannel.deviceName("My Disk"))
        assertEquals("Tablet", DriveChannel.deviceName("Планшет"))
        assertEquals("a_b", DriveChannel.deviceName("a:b"))
    }

    // ------------------------------------------------------------------ файлы

    @Test
    fun create_write_read_roundtrip() {
        val ch = channel()
        handshake(ch)
        val (status, id) = create(ch, "\\note.txt", disposition = 2, access = 0x40000000L)
        assertEquals(0L, status)
        assertTrue(File(dir, "note.txt").exists())

        val written = write(ch, id, 0, "hello world".toByteArray())
        assertEquals(0L, written.status)
        assertEquals(11L, ByteReader(written.body).u32le())

        val r = read(ch, id, 6, 100)
        assertEquals(0L, r.status)
        val rd = ByteReader(r.body)
        assertEquals(5L, rd.u32le())
        assertEquals("world", String(rd.bytes(5)))

        // Чтение за концом файла возвращает ноль байт без ошибки
        assertEquals(0L, ByteReader(read(ch, id, 50, 10).body).u32le())
        assertEquals(0L, irp(ch, 2, fileId = id).status)
        assertEquals("hello world", File(dir, "note.txt").readText())
    }

    @Test
    fun opening_missing_file_fails_with_name_not_found() {
        val ch = channel()
        handshake(ch)
        assertEquals(0xC0000034L, create(ch, "\\nope.txt", disposition = 1).first)
        assertEquals(0xC000003AL, create(ch, "\\no\\nope.txt", disposition = 2).first)
    }

    @Test
    fun create_existing_file_with_create_disposition_collides() {
        File(dir, "a.txt").writeText("x")
        val ch = channel()
        handshake(ch)
        assertEquals(0xC0000035L, create(ch, "\\a.txt", disposition = 2).first)
    }

    @Test
    fun lookup_ignores_case() {
        File(dir, "Report.TXT").writeText("data")
        val ch = channel()
        handshake(ch)
        val (status, id) = create(ch, "\\report.txt", disposition = 1)
        assertEquals(0L, status)
        assertEquals("data", String(read(ch, id, 0, 10).body, 4, 4))
    }

    @Test
    fun overwrite_truncates_existing_file() {
        File(dir, "a.txt").writeText("long content")
        val ch = channel()
        handshake(ch)
        val (status, id) = create(ch, "\\a.txt", disposition = 5, access = 0x40000000L)
        assertEquals(0L, status)
        assertEquals(0L, File(dir, "a.txt").length())
        write(ch, id, 0, "ab".toByteArray())
        irp(ch, 2, fileId = id)
        assertEquals("ab", File(dir, "a.txt").readText())
    }

    @Test
    fun query_information_reports_size_and_directory_flag() {
        File(dir, "a.bin").writeBytes(ByteArray(1234))
        File(dir, "sub").mkdir()
        val ch = channel()
        handshake(ch)
        val (_, file) = create(ch, "\\a.bin", disposition = 1)
        val std = ByteReader(query(ch, file, 5).body)
        assertEquals(22L, std.u32le())
        assertEquals(1234L, std.u64le())
        assertEquals(1234L, std.u64le())
        std.u32le()
        assertEquals(0, std.u8()) // DeletePending
        assertEquals(0, std.u8()) // Directory

        val (_, folder) = create(ch, "\\sub", disposition = 1, options = 1)
        val dirStd = ByteReader(query(ch, folder, 5).body)
        dirStd.skip(4 + 8 + 8 + 4 + 1)
        assertEquals(1, dirStd.u8())

        val basic = query(ch, file, 4).body
        assertEquals(36L, ByteReader(basic).u32le())
        assertEquals(0x80L, ByteReader(basic, 4 + 32).u32le())
        val tag = query(ch, folder, 35).body
        assertEquals(0x10L, ByteReader(tag, 4).u32le())
    }

    @Test
    fun directory_listing_returns_every_entry_then_no_more_files() {
        File(dir, "a.txt").writeText("1")
        File(dir, "b.log").writeText("2")
        File(dir, "folder").mkdir()
        File(dir, "folder/inner.txt").writeText("3")
        val ch = channel()
        handshake(ch)
        val (_, root) = create(ch, "\\", disposition = 1, options = 1)
        assertEquals(listOf("a.txt", "b.log", "folder"), names(ch, root, "\\*").sorted())

        val (_, sub) = create(ch, "\\folder", disposition = 1, options = 1)
        assertEquals(listOf(".", "..", "inner.txt"), names(ch, sub, "\\folder\\*"))
    }

    @Test
    fun directory_listing_filters_by_pattern_and_reports_no_such_file() {
        File(dir, "a.txt").writeText("1")
        File(dir, "B.TXT").writeText("2")
        File(dir, "c.log").writeText("3")
        val ch = channel()
        handshake(ch)
        val (_, root) = create(ch, "\\", disposition = 1, options = 1)
        assertEquals(listOf("B.TXT", "a.txt"), names(ch, root, "\\*.txt").sorted())
        assertEquals(listOf("c.log"), names(ch, root, "\\c.log"))
        assertEquals(0xC000000FL, directory(ch, root, 3, true, "\\*.zip").status)
    }

    @Test
    fun other_directory_information_classes_have_expected_layout() {
        File(dir, "x.dat").writeBytes(ByteArray(7))
        val ch = channel()
        handshake(ch)
        val (_, root) = create(ch, "\\", disposition = 1, options = 1)
        val names = directory(ch, root, 12, true, "\\*") // FileNamesInformation
        val rd = ByteReader(names.body)
        assertEquals((12 + 10).toLong(), rd.u32le())
        rd.skip(8)
        assertEquals(10L, rd.u32le())
        assertEquals("x.dat", String(rd.bytes(10), Charsets.UTF_16LE))

        val full = directory(ch, root, 2, true, "\\*") // FileFullDirectoryInformation
        assertEquals((68 + 10).toLong(), ByteReader(full.body).u32le())
        val plain = directory(ch, root, 1, true, "\\*") // FileDirectoryInformation
        assertEquals((64 + 10).toLong(), ByteReader(plain.body).u32le())
        assertEquals(0xC00000BBL, directory(ch, root, 37, true, "\\*").status)
    }

    @Test
    fun rename_moves_file_between_folders() {
        File(dir, "a.txt").writeText("x")
        File(dir, "dest").mkdir()
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\a.txt", disposition = 1, access = 0x10000L)
        val target = "\\dest\\b.txt".toByteArray(Charsets.UTF_16LE)
        val payload = ByteWriter().u8(0).u8(0).u32le(target.size).bytes(target).toByteArray()
        assertEquals(0L, setInfo(ch, id, 10, payload).status)
        assertFalse(File(dir, "a.txt").exists())
        assertEquals("x", File(dir, "dest/b.txt").readText())
        irp(ch, 2, fileId = id)
    }

    @Test
    fun rename_onto_existing_file_requires_replace_flag() {
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\a.txt", disposition = 1, access = 0x10000L)
        val target = "\\b.txt".toByteArray(Charsets.UTF_16LE)
        fun payload(replace: Int) = ByteWriter().u8(replace).u8(0).u32le(target.size).bytes(target).toByteArray()
        assertEquals(0xC0000035L, setInfo(ch, id, 10, payload(0)).status)
        assertEquals(0L, setInfo(ch, id, 10, payload(1)).status)
        assertEquals("a", File(dir, "b.txt").readText())
    }

    @Test
    fun disposition_information_deletes_on_close() {
        File(dir, "gone.txt").writeText("x")
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\gone.txt", disposition = 1, access = 0x10000L)
        assertEquals(0L, setInfo(ch, id, 13, byteArrayOf(1)).status)
        assertTrue(File(dir, "gone.txt").exists())
        irp(ch, 2, fileId = id)
        assertFalse(File(dir, "gone.txt").exists())
    }

    @Test
    fun deleting_non_empty_directory_is_refused() {
        File(dir, "d").mkdir()
        File(dir, "d/f").writeText("x")
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\d", disposition = 1, options = 1, access = 0x10000L)
        assertEquals(0xC0000101L, setInfo(ch, id, 13, byteArrayOf(1)).status)
    }

    @Test
    fun end_of_file_information_resizes_file() {
        File(dir, "a.bin").writeBytes(ByteArray(100))
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\a.bin", disposition = 1, access = 0x40000000L)
        assertEquals(0L, setInfo(ch, id, 20, ByteWriter().u64le(10).toByteArray()).status)
        irp(ch, 2, fileId = id)
        assertEquals(10L, File(dir, "a.bin").length())
    }

    @Test
    fun create_directory_via_create_request() {
        val ch = channel()
        handshake(ch)
        assertEquals(0L, create(ch, "\\newdir", disposition = 2, options = 1).first)
        assertTrue(File(dir, "newdir").isDirectory)
    }

    // ------------------------------------------------------------------ ограничения

    @Test
    fun read_only_drive_rejects_writes_but_serves_reads() {
        File(dir, "a.txt").writeText("data")
        val ch = channel(readOnly = true)
        handshake(ch)
        assertEquals(0xC0000022L, create(ch, "\\new.txt", disposition = 2).first)
        assertEquals(0xC0000022L, create(ch, "\\a.txt", disposition = 5, access = 0x40000000L).first)
        assertEquals(0xC0000022L, create(ch, "\\a.txt", disposition = 1, access = 0x10000L).first)
        val (status, id) = create(ch, "\\a.txt", disposition = 1)
        assertEquals(0L, status)
        assertEquals(4L, ByteReader(read(ch, id, 0, 10).body).u32le())
        assertEquals(0xC0000022L, write(ch, id, 0, byteArrayOf(1)).status)
        assertEquals("data", File(dir, "a.txt").readText())
    }

    @Test
    fun paths_escaping_the_folder_are_rejected() {
        File(dir, "a.txt").writeText("x")
        val ch = channel()
        handshake(ch)
        assertEquals(0xC0000022L, create(ch, "\\..\\secret.txt", disposition = 2).first)
        assertEquals(0xC0000034L, create(ch, "\\a.txt:stream", disposition = 1).first)
    }

    @Test
    fun unknown_requests_and_handles_fail_cleanly() {
        val ch = channel()
        handshake(ch)
        assertEquals(0xC00000BBL, irp(ch, 0x0B).status) // SET_VOLUME_INFORMATION
        assertEquals(0xC0000008L, read(ch, 999, 0, 10).status)
        assertEquals(0xC0000001L, irp(ch, 2, fileId = 999).status)
        assertEquals(0L, irp(ch, 0x0E).status) // DEVICE_CONTROL
        assertEquals(0xC00000BBL, irp(ch, 0x0C, minor = 2).status) // NOTIFY_CHANGE_DIRECTORY
    }

    @Test
    fun volume_information_reports_size_label_and_read_only_flag() {
        val ch = channel(readOnly = true)
        handshake(ch)
        fun volume(kind: Int) = irp(ch, 0x0A, body = ByteWriter().u32le(kind).u32le(0).zeros(24).toByteArray()).body

        val size = ByteReader(volume(3))
        assertEquals(24L, size.u32le())
        assertEquals((1L shl 40) / 4096, size.u64le())
        assertEquals((1L shl 39) / 4096, size.u64le())
        assertEquals(8L, size.u32le())
        assertEquals(512L, size.u32le())

        val attr = ByteReader(volume(5))
        attr.u32le()
        assertTrue(attr.u32le() and 0x00080000L != 0L) // FILE_READ_ONLY_VOLUME
        attr.skip(4)
        assertEquals("NTFS\u0000", String(attr.bytes(attr.u32le().toInt()), Charsets.UTF_16LE))

        val label = ByteReader(volume(1))
        val labelBytes = label.u32le() - 17
        label.skip(8 + 4)
        assertEquals(labelBytes, label.u32le())
        label.skip(1)
        assertEquals("Tablet\u0000", String(label.bytes(labelBytes.toInt()), Charsets.UTF_16LE))

        assertEquals(32L, ByteReader(volume(7)).u32le())
        assertEquals(8L, ByteReader(volume(4)).u32le())
        assertEquals(0xC0000001L, irp(ch, 0x0A, body = ByteWriter().u32le(99).u32le(0).zeros(24).toByteArray()).status)
    }

    @Test
    fun fragmented_irp_is_reassembled() {
        val ch = channel()
        handshake(ch)
        sent.clear()
        val pdu = core(0x4952).u32le(1).u32le(0).u32le(77).u32le(0).u32le(0).bytes(createBody("\\x.txt", 2)).toByteArray()
        val half = pdu.size / 2
        ch.onChannelData(ByteReader(ByteWriter().u32le(pdu.size).u32le(1).bytes(pdu, 0, half).toByteArray()))
        assertTrue(sent.isEmpty())
        ch.onChannelData(ByteReader(ByteWriter().u32le(pdu.size).u32le(2).bytes(pdu, half, pdu.size - half).toByteArray()))
        assertEquals(1, sent.size)
        assertTrue(File(dir, "x.txt").exists())
    }

    @Test
    fun large_read_is_returned_whole() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        File(dir, "big.bin").writeBytes(data)
        val ch = channel()
        handshake(ch)
        val (_, id) = create(ch, "\\big.bin", disposition = 1)
        val r = read(ch, id, 1000, 200_000)
        assertEquals(200_000L, ByteReader(r.body).u32le())
        assertArrayEquals(data.copyOfRange(1000, 201_000), r.body.copyOfRange(4, r.body.size))
    }
}
