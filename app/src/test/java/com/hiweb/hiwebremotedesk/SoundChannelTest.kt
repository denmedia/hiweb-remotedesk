package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.AudioCodecs
import com.hiweb.hiwebremotedesk.rdp.AudioSink
import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.SoundChannel
import com.hiweb.hiwebremotedesk.rdp.WaveFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundChannelTest {
    private class Played(val rate: Int, val channels: Int, val pcm: ByteArray)

    private val sent = ArrayList<ByteArray>()
    private val played = ArrayList<Played>()
    private var pendingDone: (() -> Unit)? = null
    private val sink = object : AudioSink {
        override fun play(sampleRate: Int, channels: Int, pcm: ByteArray, done: () -> Unit) {
            played += Played(sampleRate, channels, pcm)
            pendingDone = done
        }

        override fun stop() {}
    }
    private var now = 1000L
    private val channel = SoundChannel({ sent += it }, sink, { now })

    private fun feed(data: ByteArray) {
        channel.onChannelData(ByteReader(ByteWriter().u32le(data.size).u32le(3).bytes(data).toByteArray()))
    }

    private fun pdu(type: Int, body: ByteArray) = ByteWriter().u8(type).u8(0).u16le(body.size).bytes(body).toByteArray()

    private fun format(tag: Int, channels: Int, rate: Int, bits: Int, extra: ByteArray = ByteArray(0), align: Int = channels * bits / 8) =
        ByteWriter().u16le(tag).u16le(channels).u32le(rate).u32le(rate * channels * bits / 8)
            .u16le(align).u16le(bits).u16le(extra.size).bytes(extra).toByteArray()

    private fun serverFormats(version: Int, vararg formats: ByteArray): ByteArray {
        val w = ByteWriter().u32le(7).u32le(0xFFFFFFFFL).u32le(0).u16le(0).u16le(formats.size).u8(0).u16le(version).u8(0)
        formats.forEach { w.bytes(it) }
        return pdu(7, w.toByteArray())
    }

    private fun startWithPcm() {
        // 0: AAC (отклоняется), 1: PCM 44100 стерео -> индекс 0 в списке клиента
        feed(serverFormats(8, format(0xA106, 2, 44100, 16, ByteArray(12)), format(1, 2, 44100, 16)))
        sent.clear()
    }

    @Test
    fun formats_reply_keeps_only_pcm_and_sends_quality_mode() {
        feed(serverFormats(8, format(0xA106, 2, 44100, 16, ByteArray(12)), format(1, 2, 44100, 16), format(1, 2, 22050, 8)))
        assertEquals(listOf(7, 12), sent.map { it[0].toInt() })
        val r = ByteReader(sent[0], 4)
        r.skip(4 + 4 + 4 + 2)
        assertEquals(1, r.u16le()) // осталась одна запись: PCM 16 бит
        r.skip(1)
        assertEquals(6, r.u16le()) // версия клиента ограничена 6
        r.skip(1)
        assertEquals(1, r.u16le()) // WAVE_FORMAT_PCM
        assertEquals(2, r.u16le())
        assertEquals(44100L, r.u32le())
    }

    @Test
    fun training_is_echoed() {
        startWithPcm()
        feed(pdu(6, ByteWriter().u16le(0x1234).u16le(0).toByteArray()))
        assertEquals(6, sent[0][0].toInt())
        assertEquals(0x1234, ByteReader(sent[0], 4).u16le())
    }

    @Test
    fun wave_info_and_wave_pdu_are_joined_and_confirmed_after_playback() {
        startWithPcm()
        val info = ByteWriter().u16le(500).u16le(0).u8(9).zeros(3).bytes(byteArrayOf(1, 2, 3, 4)).toByteArray()
        feed(pdu(2, info))
        assertEquals(0, played.size)
        feed(ByteWriter().zeros(4).bytes(byteArrayOf(5, 6, 7, 8)).toByteArray())
        assertEquals(1, played.size)
        assertEquals(44100, played[0].rate)
        assertEquals(2, played[0].channels)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), played[0].pcm)
        assertEquals(0, sent.size) // подтверждение только после того, как звук принят плеером

        now += 30
        pendingDone!!.invoke()
        assertEquals(5, sent[0][0].toInt())
        val r = ByteReader(sent[0], 4)
        assertEquals(530, r.u16le())
        assertEquals(9, r.u8())
    }

    @Test
    fun wave2_is_played_directly() {
        startWithPcm()
        val body = ByteWriter().u16le(10).u16le(0).u8(3).zeros(3).u32le(0).bytes(byteArrayOf(9, 9)).toByteArray()
        feed(pdu(13, body))
        assertArrayEquals(byteArrayOf(9, 9), played[0].pcm)
    }

    @Test
    fun unknown_format_is_confirmed_without_playing() {
        startWithPcm()
        val info = ByteWriter().u16le(0).u16le(7).u8(4).zeros(3).bytes(byteArrayOf(1, 2, 3, 4)).toByteArray()
        feed(pdu(2, info))
        feed(ByteWriter().zeros(4).bytes(byteArrayOf(5)).toByteArray())
        assertEquals(0, played.size)
        assertEquals(5, sent[0][0].toInt())
    }

    @Test
    fun without_option_compressed_formats_are_not_accepted() {
        feed(serverFormats(8, format(0x11, 2, 44100, 4, byteArrayOf(0, 0), align = 512), format(1, 2, 44100, 16)))
        val r = ByteReader(sent[0], 4)
        r.skip(4 + 4 + 4 + 2)
        assertEquals(1, r.u16le()) // только PCM
        r.skip(1 + 2 + 1)
        assertEquals(1, r.u16le())
    }

    @Test
    fun compressed_formats_are_listed_first_and_decoded_before_playback() {
        val comp = SoundChannel({ sent += it }, sink, { now }, compressed = true)
        val server = serverFormats(
            8,
            format(1, 2, 44100, 16),
            format(0xA106, 2, 44100, 16, ByteArray(12)),
            format(0x11, 2, 44100, 4, byteArrayOf(0, 0), align = 512),
        )
        comp.onChannelData(ByteReader(ByteWriter().u32le(server.size).u32le(3).bytes(server).toByteArray()))
        val r = ByteReader(sent[0], 4)
        r.skip(4 + 4 + 4 + 2)
        assertEquals(2, r.u16le()) // IMA ADPCM и PCM; AAC отброшен
        r.skip(1 + 2 + 1)
        assertEquals(0x11, r.u16le()) // сжатый формат — первым, у клиента он номер 0
        r.skip(2 + 4 + 4 + 2 + 2 + 2 + 2)
        assertEquals(1, r.u16le()) // за ним PCM — номер 1

        // Блок ADPCM, номер формата 0: плеер получает разжатый PCM (505 кадров стерео) в той же частоте.
        val imaWave = WaveFormat(0x11, 2, 44100, 0, 512, 4, byteArrayOf(0, 0))
        val pcm = ByteArray(505 * 4) { (it * 7).toByte() }
        val packed = AudioCodecs.codec(imaWave)!!.encode(pcm)
        assertEquals(512, packed.size)
        val body = ByteWriter().u16le(10).u16le(0).u8(1).zeros(3).u32le(0).bytes(packed).toByteArray()
        val pdu = ByteWriter().u8(13).u8(0).u16le(body.size).bytes(body).toByteArray()
        comp.onChannelData(ByteReader(ByteWriter().u32le(pdu.size).u32le(3).bytes(pdu).toByteArray()))
        assertEquals(1, played.size)
        assertEquals(44100, played[0].rate)
        assertEquals(2, played[0].channels)
        assertEquals(pcm.size, played[0].pcm.size)

        // Номер 1 — PCM, он играет как есть.
        val raw = ByteWriter().u16le(11).u16le(1).u8(2).zeros(3).u32le(0).bytes(byteArrayOf(5, 6, 7, 8)).toByteArray()
        val rawPdu = ByteWriter().u8(13).u8(0).u16le(raw.size).bytes(raw).toByteArray()
        comp.onChannelData(ByteReader(ByteWriter().u32le(rawPdu.size).u32le(3).bytes(rawPdu).toByteArray()))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), played[1].pcm)
    }
}
