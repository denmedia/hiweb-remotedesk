package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.AudinChannel
import com.hiweb.hiwebremotedesk.rdp.AudioSource
import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Обмен клиента канала audin (MS-RDPEAI) с сервером: форматы, открытие, смена формата и данные. */
class AudinChannelTest {
    private val sent = ArrayList<ByteArray>()
    private var feed: ((ByteArray) -> Unit)? = null
    private var startedFormat: Pair<Int, Int>? = null

    private val source = object : AudioSource {
        override fun start(sampleRate: Int, channels: Int, feed: (ByteArray) -> Unit): Boolean {
            startedFormat = sampleRate to channels
            this@AudinChannelTest.feed = feed
            return true
        }

        override fun stop() {
            feed = null
            startedFormat = null
        }
    }

    private val channel = AudinChannel(source)

    private fun pcmFormat(channels: Int, rate: Int, bits: Int = 16) =
        ByteWriter().u16le(1).u16le(channels).u32le(rate).u32le(rate.toLong() * channels * bits / 8)
            .u16le(channels * bits / 8).u16le(bits).u16le(0).toByteArray()

    private fun aacFormat(channels: Int, rate: Int) =
        ByteWriter().u16le(0xA106).u16le(channels).u32le(rate).u32le(16000)
            .u16le(4).u16le(16).u16le(0).toByteArray()

    private fun formatsPdu(vararg formats: ByteArray): ByteArray {
        val w = ByteWriter().u8(0x02).u32le(formats.size).u32le(0)
        formats.forEach { w.bytes(it) }
        return w.toByteArray()
    }

    private fun openPdu(framesPerPacket: Int, format: Int) =
        ByteWriter().u8(0x03).u32le(framesPerPacket).u32le(format).toByteArray()

    private fun formatChangePdu(format: Int) = ByteWriter().u8(0x07).u32le(format).toByteArray()

    /** Ответы канала на FORMATS — ровно два PDU: DATA_INCOMING и список форматов. */
    private var formatsReply = emptyList<ByteArray>()

    private fun startSession(vararg formats: ByteArray) {
        channel.onOpen { sent += it }
        channel.onMessage(ByteWriter().u8(0x01).u32le(2).toByteArray())
        sent.clear()
        channel.onMessage(formatsPdu(*formats))
        formatsReply = sent.toList()
        sent.clear()
    }

    @Test
    fun version_is_answered_with_client_version() {
        channel.onOpen { sent += it }
        channel.onMessage(ByteWriter().u8(0x01).u32le(2).toByteArray())
        assertEquals(1, sent.size)
        assertEquals(0x01, sent[0][0].toInt())
        assertEquals(2L, ByteReader(sent[0], 1).u32le())
    }

    @Test
    fun newer_server_version_is_ignored() {
        channel.onOpen { sent += it }
        channel.onMessage(ByteWriter().u8(0x01).u32le(3).toByteArray())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun formats_keep_only_pcm16_and_are_preceded_by_data_incoming() {
        startSession(aacFormat(2, 44100), pcmFormat(2, 44100), pcmFormat(1, 48000), pcmFormat(2, 96000))
        assertEquals(2, formatsReply.size)
        assertArrayEquals(byteArrayOf(0x05), formatsReply[0])
        val r = ByteReader(formatsReply[1], 1)
        assertEquals(2L, r.u32le()) // AAC и 96 кГц отброшены
        assertEquals(9L + 2 * 18, r.u32le()) // cbSizeFormatsPacket: заголовок + два формата
        assertEquals(1, r.u16le()) // WAVE_FORMAT_PCM
        assertEquals(2, r.u16le())
        assertEquals(44100L, r.u32le())
        assertEquals(1, ByteReader(formatsReply[1], 1 + 4 + 4 + 18).u16le())
    }

    @Test
    fun open_replies_with_format_change_then_result_and_starts_capture_only_when_enabled() {
        startSession(pcmFormat(1, 44100))
        // Микрофон ещё выключен: сервер открывает канал, но захват не начинается.
        channel.onMessage(openPdu(480, 0))
        assertEquals(listOf(0x07, 0x04), sent.map { it[0].toInt() })
        assertEquals(null, startedFormat)
        assertArrayEquals(ByteWriter().u8(0x04).u32le(0).toByteArray(), sent[1])

        assertTrue(channel.setEnabled(true))
        assertEquals(44100 to 1, startedFormat)
    }

    @Test
    fun enabled_before_open_starts_capture_on_open() {
        startSession(pcmFormat(2, 48000))
        channel.setEnabled(true)
        assertEquals(null, startedFormat)
        channel.onMessage(openPdu(0, 0))
        assertEquals(48000 to 2, startedFormat)
    }

    @Test
    fun pcm_is_sent_in_data_incoming_and_data_pairs_split_by_frames_per_packet() {
        startSession(pcmFormat(1, 8000))
        channel.setEnabled(true)
        channel.onMessage(openPdu(80, 0)) // 80 кадров = 160 байт при 16 битах моно
        sent.clear()
        feed!!.invoke(ByteArray(400) { it.toByte() })
        // 400 байт = 2.5 блока: 160 + 160 + 80.
        assertEquals(6, sent.size)
        assertEquals(listOf(0x05, 0x06, 0x05, 0x06, 0x05, 0x06), sent.map { it[0].toInt() })
        assertEquals(161, sent[1].size)
        assertEquals(161, sent[3].size)
        assertEquals(81, sent[5].size)
        assertArrayEquals(ByteArray(160) { it.toByte() }, ByteReader(sent[1], 1).bytes(160))
        assertArrayEquals(ByteArray(80) { (it + 320).toByte() }, ByteReader(sent[5], 1).bytes(80))
    }

    @Test
    fun disabled_microphone_sends_nothing() {
        startSession(pcmFormat(1, 8000))
        channel.setEnabled(true)
        channel.onMessage(openPdu(80, 0))
        val send = feed!!
        channel.setEnabled(false)
        sent.clear()
        send(ByteArray(160))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun format_change_restarts_capture_in_new_format() {
        startSession(pcmFormat(1, 44100), pcmFormat(1, 16000))
        channel.setEnabled(true)
        channel.onMessage(openPdu(320, 0))
        assertEquals(44100 to 1, startedFormat)
        sent.clear()
        channel.onMessage(formatChangePdu(1))
        assertEquals(16000 to 1, startedFormat)
        assertEquals(listOf(0x07), sent.map { it[0].toInt() })
        assertEquals(1L, ByteReader(sent[0], 1).u32le())
    }

    @Test
    fun unknown_format_index_fails_open() {
        startSession(pcmFormat(1, 44100))
        channel.onMessage(openPdu(320, 5))
        assertEquals(0x04, sent[0][0].toInt())
        assertEquals(1L, ByteReader(sent[0], 1).u32le()) // RESULT_ERROR
    }

    @Test
    fun close_stops_capture_and_enable_waits_for_next_open() {
        startSession(pcmFormat(1, 44100))
        channel.setEnabled(true)
        channel.onMessage(openPdu(320, 0))
        assertTrue(startedFormat != null)
        channel.onClose()
        assertEquals(null, startedFormat)
        // Windows закрывает канал после записи: включение между записями не отказ, а ожидание следующего OPEN.
        assertTrue(channel.setEnabled(true))
        assertEquals(null, startedFormat)
    }

    @Test
    fun enabled_before_channel_exists_survives_until_open() {
        // Кнопку нажали до того, как программа на ПК начала запись: канала ещё нет.
        assertTrue(channel.setEnabled(true))
        assertEquals(null, startedFormat)
        startSession(pcmFormat(1, 44100))
        channel.onMessage(openPdu(320, 0))
        assertEquals(44100 to 1, startedFormat)
    }
}
