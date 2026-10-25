package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.ClipboardChannel
import com.hiweb.hiwebremotedesk.rdp.LocalFile
import com.hiweb.hiwebremotedesk.rdp.RangeReader
import com.hiweb.hiwebremotedesk.rdp.RdpException
import com.hiweb.hiwebremotedesk.rdp.RemoteFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Передача файлов через буфер обмена (MS-RDPECLIP: FileGroupDescriptorW и FileContents) на синтетических PDU. */
class ClipboardFilesTest {
    private val sent = CopyOnWriteArrayList<ByteArray>()
    private var remoteFiles: List<RemoteFile>? = null
    private var remoteFilesCalls = 0
    private var remoteText: String? = null

    private fun channel(files: Boolean = true, text: Boolean = true) = ClipboardChannel(
        send = { sent += it },
        onRemoteText = { remoteText = it },
        textEnabled = text,
        filesEnabled = files,
        onRemoteFiles = { remoteFiles = it; remoteFilesCalls++ },
    )

    private fun feed(ch: ClipboardChannel, type: Int, flags: Int, body: ByteArray) {
        val pdu = ByteWriter().u16le(type).u16le(flags).u32le(body.size).bytes(body).toByteArray()
        ch.onChannelData(ByteReader(ByteWriter().u32le(pdu.size).u32le(3).bytes(pdu).toByteArray()))
    }

    private fun type(pdu: ByteArray) = ByteReader(pdu).u16le()
    private fun flags(pdu: ByteArray) = ByteReader(pdu, 2).u16le()
    private fun body(pdu: ByteArray) = pdu.copyOfRange(8, pdu.size)

    private fun serverCaps(flags: Int) = ByteWriter().u16le(1).u16le(0).u16le(1).u16le(12).u32le(2).u32le(flags).toByteArray()

    private fun ready(ch: ClipboardChannel, serverFlags: Int = 0x2 or 0x4 or 0x8 or 0x20) {
        feed(ch, 7, 0, serverCaps(serverFlags))
        feed(ch, 1, 0, ByteArray(0))
        sent.clear()
    }

    /** Список форматов с длинными именами. */
    private fun formatList(vararg formats: Pair<Int, String>): ByteArray {
        val w = ByteWriter()
        for ((id, name) in formats) w.u32le(id).utf16(name).u16le(0)
        return w.toByteArray()
    }

    private fun descriptor(path: String, size: Long, directory: Boolean = false, flags: Int = 0x64 or 0x20): ByteArray {
        val name = path.toByteArray(Charsets.UTF_16LE)
        return ByteWriter().u32le(flags).zeros(32).u32le(if (directory) 0x10 else 0x80).zeros(16)
            .u64le(132_000_000_000_000_000L).u32le((size ushr 32).toInt()).u32le(size.toInt())
            .bytes(name).zeros(520 - name.size).toByteArray()
    }

    private fun descriptorList(vararg entries: ByteArray): ByteArray {
        val w = ByteWriter().u32le(entries.size)
        entries.forEach { w.bytes(it) }
        return w.toByteArray()
    }

    // ------------------------------------------------------------------ согласование возможностей

    @Test
    fun capabilities_advertise_file_flags_only_when_enabled() {
        val on = channel(files = true)
        feed(on, 7, 0, serverCaps(0x2 or 0x4))
        feed(on, 1, 0, ByteArray(0))
        assertEquals(0x2L or 0x4L or 0x8L or 0x20L, ByteReader(body(sent[0]), 12).u32le())
        assertTrue(on.filesActive)

        sent.clear()
        val off = channel(files = false)
        feed(off, 7, 0, serverCaps(0x2 or 0x4))
        feed(off, 1, 0, ByteArray(0))
        assertEquals(0x2L, ByteReader(body(sent[0]), 12).u32le())
        assertFalse(off.filesActive)
    }

    @Test
    fun files_are_inactive_when_server_lacks_stream_fileclip() {
        val ch = channel()
        ready(ch, serverFlags = 0x2)
        assertFalse(ch.filesActive)
        assertFalse(ch.offerFiles(listOf(LocalFile("a.txt", false, 1, 0))))
    }

    // ------------------------------------------------------------------ с ПК на планшет

    @Test
    fun remote_file_list_is_requested_and_parsed() {
        val ch = channel()
        ready(ch)
        feed(ch, 2, 0, formatList(0xC0A1 to "FileGroupDescriptorW", 0xC0A2 to "FileContents"))
        assertEquals(listOf(3, 4), sent.map(::type))
        assertEquals(0xC0A1L, ByteReader(body(sent[1])).u32le())

        val list = descriptorList(
            descriptor("docs", 0, directory = true),
            descriptor("docs\\report.pdf", 5_000_000_000L),
            descriptor("..\\evil.txt", 1),
            descriptor("a.txt", 3),
        )
        feed(ch, 5, 1, list)
        val files = remoteFiles!!
        // Небезопасный путь отброшен, но номера остальных сохранены: по ним запрашивается содержимое
        assertEquals(listOf(0, 1, 3), files.map { it.index })
        assertEquals(listOf("docs", "docs/report.pdf", "a.txt"), files.map { it.path })
        assertEquals(listOf(true, false, false), files.map { it.isDirectory })
        assertEquals(5_000_000_000L, files[1].size)
        assertEquals("report.pdf", files[1].name)
        assertTrue(files[1].modifiedMs > 0)
    }

    @Test
    fun text_copy_after_files_clears_the_file_offer() {
        val ch = channel()
        ready(ch)
        feed(ch, 2, 0, formatList(0xC0A1 to "FileGroupDescriptorW"))
        feed(ch, 5, 1, descriptorList(descriptor("a.txt", 3)))
        assertNotNull(remoteFiles)
        feed(ch, 2, 0, formatList(13 to ""))
        assertNull(remoteFiles)
        feed(ch, 5, 1, ByteWriter().utf16("текст").u16le(0).toByteArray())
        assertEquals("текст", remoteText)
    }

    @Test
    fun file_formats_are_ignored_when_feature_is_off() {
        val ch = channel(files = false)
        ready(ch, serverFlags = 0x2 or 0x4)
        feed(ch, 2, 0, formatList(0xC0A1 to "FileGroupDescriptorW", 13 to ""))
        // Запрашивается текст, а не список файлов
        assertEquals(13L, ByteReader(body(sent[1])).u32le())
        assertEquals(0, remoteFilesCalls)
    }

    @Test
    fun text_is_ignored_when_text_sync_is_off() {
        val ch = channel(text = false)
        ready(ch)
        feed(ch, 2, 0, formatList(13 to ""))
        assertEquals(listOf(3), sent.map(::type)) // только подтверждение списка, данные не запрашиваются
        ch.replaceLocalText("секрет")
        assertEquals(1, sent.count { type(it) == 2 })
        assertEquals(0, body(sent.last { type(it) == 2 }).size) // пустой список форматов
    }

    @Test
    fun remote_file_ranges_are_requested_and_delivered() {
        val ch = channel()
        ready(ch)
        val result = AtomicReference<ByteArray>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                result.set(ch.readRemoteFile(index = 2, position = 0x1_0000_0010L, length = 5))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        worker.start()
        val request = awaitSent(8)
        val r = ByteReader(body(request))
        val streamId = r.u32le()
        assertEquals(2L, r.u32le())          // номер файла
        assertEquals(2L, r.u32le())          // FILECONTENTS_RANGE
        assertEquals(0x10L, r.u32le())       // позиция, младшая часть
        assertEquals(1L, r.u32le())          // позиция, старшая часть
        assertEquals(5L, r.u32le())
        assertEquals(24, body(request).size) // без идентификатора блокировки

        feed(ch, 9, 1, ByteWriter().u32le(streamId.toInt()).bytes("hello".toByteArray()).toByteArray())
        worker.join(5000)
        assertNull(failure.get())
        assertEquals("hello", String(result.get()))
    }

    @Test
    fun remote_file_size_is_read_as_64_bit() {
        val ch = channel()
        ready(ch)
        val result = AtomicReference<Long>()
        val worker = Thread { result.set(ch.remoteFileSize(1)) }
        worker.start()
        val request = awaitSent(8)
        val r = ByteReader(body(request))
        val streamId = r.u32le()
        r.u32le()
        assertEquals(1L, r.u32le()) // FILECONTENTS_SIZE
        assertEquals(8L, ByteReader(body(request), 20).u32le())
        feed(ch, 9, 1, ByteWriter().u32le(streamId.toInt()).u64le(7_000_000_000L).toByteArray())
        worker.join(5000)
        assertEquals(7_000_000_000L, result.get())
    }

    @Test
    fun refused_remote_range_raises_error() {
        val ch = channel()
        ready(ch)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                ch.readRemoteFile(0, 0, 10)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        worker.start()
        val streamId = ByteReader(body(awaitSent(8))).u32le().toInt()
        feed(ch, 9, 2, ByteWriter().u32le(streamId).toByteArray()) // CB_RESPONSE_FAIL
        worker.join(5000)
        assertTrue(failure.get() is RdpException)
    }

    @Test
    fun closing_the_channel_aborts_waiting_requests() {
        val ch = channel()
        ready(ch)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                ch.readRemoteFile(0, 0, 10)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        worker.start()
        awaitSent(8)
        ch.close()
        worker.join(5000)
        assertTrue(failure.get() is RdpException)
    }

    private fun awaitSent(type: Int): ByteArray {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            sent.firstOrNull { type(it) == type }?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("PDU типа $type не отправлен")
    }

    // ------------------------------------------------------------------ с планшета на ПК

    private class MemoryFile(private val data: ByteArray) {
        var closed = false
        fun open() = object : RangeReader {
            override fun read(position: Long, length: Int): ByteArray {
                if (position >= data.size) return ByteArray(0)
                return data.copyOfRange(position.toInt(), minOf(data.size.toLong(), position + length).toInt())
            }

            override fun close() {
                closed = true
            }
        }
    }

    private val photo = MemoryFile(ByteArray(300) { it.toByte() })

    private fun offered() = listOf(
        LocalFile("Папка", true, 0, 0),
        LocalFile("Папка/фото.jpg", false, 300, 1_700_000_000_000L, photo::open),
    )

    @Test
    fun offered_files_are_announced_with_both_formats() {
        val ch = channel()
        ready(ch)
        assertTrue(ch.offerFiles(offered()))
        val list = sent.single { type(it) == 2 }
        val r = ByteReader(body(list))
        val formats = ArrayList<Pair<Int, String>>()
        while (r.remaining >= 6) {
            val id = r.u32le().toInt()
            val name = StringBuilder()
            while (true) {
                val c = r.u16le()
                if (c == 0) break
                name.append(c.toChar())
            }
            formats += id to name.toString()
        }
        assertEquals(listOf("FileGroupDescriptorW", "FileContents"), formats.map { it.second })
    }

    @Test
    fun descriptor_request_returns_file_list() {
        val ch = channel()
        ready(ch)
        ch.offerFiles(offered())
        val id = ByteReader(body(sent.single { type(it) == 2 })).u32le()
        sent.clear()
        feed(ch, 4, 0, ByteWriter().u32le(id).toByteArray())
        val response = sent.single()
        assertEquals(5, type(response))
        assertEquals(1, flags(response))
        val data = body(response)
        assertEquals(4 + 2 * 592, data.size)
        val r = ByteReader(data)
        assertEquals(2L, r.u32le())
        // Первый элемент — папка с атрибутом DIRECTORY и русским именем
        assertEquals(0x4064L, r.u32le()) // атрибуты, время записи, размер, индикатор хода
        r.skip(32)
        assertEquals(0x10L, r.u32le())
        r.skip(16 + 8 + 8)
        assertEquals("Папка", String(r.bytes(10), Charsets.UTF_16LE))
        r.skip(510)
        // Второй: файл с размером и путём через обратную косую черту
        r.skip(4 + 32)
        assertEquals(0x80L, r.u32le())
        r.skip(16)
        assertTrue(r.u64le() > 116_444_736_000_000_000L)
        assertEquals(0L, r.u32le())
        assertEquals(300L, r.u32le())
        val name = String(r.bytes(520), Charsets.UTF_16LE).trimEnd('\u0000')
        assertEquals("Папка\\фото.jpg", name)
    }

    @Test
    fun contents_requests_are_answered_from_local_files() {
        val ch = channel()
        ready(ch)
        ch.offerFiles(offered())
        sent.clear()

        fun request(stream: Int, index: Int, flags: Int, position: Long, length: Int, clipDataId: Boolean = false): ByteArray {
            val w = ByteWriter().u32le(stream).u32le(index).u32le(flags).u32le(position.toInt()).u32le((position ushr 32).toInt()).u32le(length)
            if (clipDataId) w.u32le(9)
            sent.clear()
            feed(ch, 8, 0, w.toByteArray())
            return sent.single()
        }

        val size = request(11, 1, 1, 0, 8)
        assertEquals(9, type(size))
        assertEquals(1, flags(size))
        val sizeBody = ByteReader(body(size))
        assertEquals(11L, sizeBody.u32le())
        assertEquals(300L, sizeBody.u64le())

        val range = request(12, 1, 2, 100, 50, clipDataId = true)
        val rangeBody = body(range)
        assertEquals(12L, ByteReader(rangeBody).u32le())
        assertArrayEquals(ByteArray(50) { (100 + it).toByte() }, rangeBody.copyOfRange(4, rangeBody.size))

        // Хвост файла короче запрошенного
        val tail = body(request(13, 1, 2, 290, 100))
        assertEquals(10, tail.size - 4)

        // Отказы: несуществующий файл, папка, неизвестный режим
        assertEquals(2, flags(request(14, 7, 2, 0, 10)))
        assertEquals(2, flags(request(15, 0, 2, 0, 10)))
        assertEquals(2, flags(request(16, 1, 0, 0, 10)))
        val failedSize = request(17, 7, 1, 0, 8)
        assertEquals(2, flags(failedSize))
        assertEquals(17L, ByteReader(body(failedSize)).u32le())
    }

    @Test
    fun new_text_replaces_the_file_offer_and_closes_readers() {
        val ch = channel()
        ready(ch)
        ch.offerFiles(offered())
        feed(ch, 8, 0, ByteWriter().u32le(1).u32le(1).u32le(2).u32le(0).u32le(0).u32le(10).toByteArray())
        assertFalse(photo.closed)
        sent.clear()
        ch.replaceLocalText("новый текст")
        assertTrue(photo.closed)
        val announced = ByteReader(body(sent.single { type(it) == 2 }))
        assertEquals(13L, announced.u32le())
        // Содержимое файлов больше не отдаётся
        sent.clear()
        feed(ch, 8, 0, ByteWriter().u32le(2).u32le(1).u32le(2).u32le(0).u32le(0).u32le(10).toByteArray())
        assertEquals(2, flags(sent.single()))
    }

    @Test
    fun withdrawing_the_offer_returns_to_text() {
        val ch = channel()
        ready(ch)
        ch.localText = "abc"
        ch.offerFiles(offered())
        sent.clear()
        ch.offerFiles(null)
        assertEquals(13L, ByteReader(body(sent.single { type(it) == 2 })).u32le())
    }

    @Test
    fun offering_files_hides_remote_file_prompt() {
        val ch = channel()
        ready(ch)
        feed(ch, 2, 0, formatList(0xC0A1 to "FileGroupDescriptorW"))
        feed(ch, 5, 1, descriptorList(descriptor("a.txt", 3)))
        assertNotNull(remoteFiles)
        ch.offerFiles(offered())
        assertNull(remoteFiles)
    }

    @Test
    fun descriptor_codec_roundtrip_through_decoder() {
        // Закодированный планшетом список читается тем же разборщиком, что и список от ПК
        val ch = channel()
        ready(ch)
        ch.offerFiles(offered())
        val id = ByteReader(body(sent.single { type(it) == 2 })).u32le()
        sent.clear()
        feed(ch, 4, 0, ByteWriter().u32le(id).toByteArray())
        val encoded = body(sent.single())

        val receiver = channel()
        ready(receiver)
        feed(receiver, 2, 0, formatList(0xC0A1 to "FileGroupDescriptorW"))
        feed(receiver, 5, 1, encoded)
        val decoded = remoteFiles!!
        assertEquals(listOf("Папка", "Папка/фото.jpg"), decoded.map { it.path })
        assertEquals(listOf(true, false), decoded.map { it.isDirectory })
        assertEquals(300L, decoded[1].size)
        assertEquals(1_700_000_000_000L, decoded[1].modifiedMs)
    }
}
