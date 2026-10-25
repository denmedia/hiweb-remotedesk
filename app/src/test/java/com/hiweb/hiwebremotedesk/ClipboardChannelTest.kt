package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.ClipboardChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardChannelTest {
    private val sent = ArrayList<ByteArray>()
    private var remoteText: String? = null
    private val channel = ClipboardChannel({ sent += it }, { remoteText = it })

    /** Одно сообщение сервера как единственный (FIRST|LAST) фрагмент канала. */
    private fun feed(type: Int, flags: Int, body: ByteArray, chunkFlags: Int = 3) {
        val pdu = ByteWriter().u16le(type).u16le(flags).u32le(body.size).bytes(body).toByteArray()
        val data = ByteWriter().u32le(pdu.size).u32le(chunkFlags).bytes(pdu).toByteArray()
        channel.onChannelData(ByteReader(data))
    }

    private fun type(pdu: ByteArray) = ByteReader(pdu).u16le()
    private fun flags(pdu: ByteArray) = ByteReader(pdu, 2).u16le()
    private fun body(pdu: ByteArray) = pdu.copyOfRange(8, pdu.size)

    private fun serverCaps(flags: Int = 2) = ByteWriter().u16le(1).u16le(0).u16le(1).u16le(12).u32le(2).u32le(flags).toByteArray()

    @Test
    fun monitor_ready_sends_caps_then_format_list_with_text() {
        channel.localText = "привет"
        feed(7, 0, serverCaps())
        feed(1, 0, ByteArray(0))
        assertEquals(listOf(7, 2), sent.map(::type))
        // Список форматов: CF_UNICODETEXT (13) и пустое длинное имя
        assertEquals("0d000000" + "0000", body(sent[1]).joinToString("") { "%02x".format(it) })
        assertTrue(channel.isReady)
    }

    @Test
    fun data_request_returns_utf16_text_with_crlf_and_terminator() {
        channel.localText = "a\nб"
        feed(7, 0, serverCaps())
        feed(1, 0, ByteArray(0))
        sent.clear()
        feed(4, 0, ByteWriter().u32le(13).toByteArray())
        assertEquals(5, type(sent[0]))
        assertEquals(1, flags(sent[0])) // CB_RESPONSE_OK
        val text = String(body(sent[0]), Charsets.UTF_16LE)
        assertEquals("a\r\nб\u0000", text)
    }

    @Test
    fun data_request_without_text_fails_gracefully() {
        feed(7, 0, serverCaps())
        feed(1, 0, ByteArray(0))
        sent.clear()
        feed(4, 0, ByteWriter().u32le(13).toByteArray())
        assertEquals(2, flags(sent[0])) // CB_RESPONSE_FAIL
    }

    @Test
    fun remote_copy_is_requested_and_delivered() {
        feed(7, 0, serverCaps())
        feed(1, 0, ByteArray(0))
        sent.clear()
        // Сервер: список форматов (длинные имена) с CF_UNICODETEXT и CF_TEXT
        val list = ByteWriter().u32le(13).u16le(0).u32le(1).u16le(0).toByteArray()
        feed(2, 0, list)
        assertEquals(listOf(3, 4), sent.map(::type)) // ответ на список и запрос данных
        assertEquals(13, ByteReader(body(sent[1])).u32le().toInt())

        feed(5, 1, ByteWriter().utf16("строка 1\r\nстрока 2").u16le(0).toByteArray())
        assertEquals("строка 1\nстрока 2", remoteText)
    }

    @Test
    fun fragmented_pdu_is_reassembled() {
        feed(7, 0, serverCaps())
        feed(1, 0, ByteArray(0))
        sent.clear()
        val text = ByteWriter().utf16("часть").u16le(0).toByteArray()
        val pdu = ByteWriter().u16le(5).u16le(1).u32le(text.size).bytes(text).toByteArray()
        val half = pdu.size / 2
        val first = ByteWriter().u32le(pdu.size).u32le(1).bytes(pdu, 0, half).toByteArray()
        val last = ByteWriter().u32le(pdu.size).u32le(2).bytes(pdu, half, pdu.size - half).toByteArray()
        channel.onChannelData(ByteReader(first))
        assertEquals(null, remoteText)
        channel.onChannelData(ByteReader(last))
        assertEquals("часть", remoteText)
    }
}
