package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.AudioCodecs
import com.hiweb.hiwebremotedesk.rdp.G711
import com.hiweb.hiwebremotedesk.rdp.WaveFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * Кодеки звука. Кроме самопроверок, ADPCM сверялся с независимыми реализациями (разово, на синтетическом сигнале):
 * MS ADPCM совпадает с ffmpeg бит в бит в обе стороны, IMA — с классическим алгоритмом (Python audioop) бит в бит,
 * а ffmpeg разжимает поток нашего кодера обоих форматов с тем же отношением сигнал/шум, что и наш декодер.
 */
class AudioCodecsTest {
    private fun wave(tag: Int, channels: Int, rate: Int, bits: Int, align: Int, extra: ByteArray = ByteArray(0)) =
        WaveFormat(tag, channels, rate, rate.toLong() * channels * bits / 8, align, bits, extra)

    private fun ima(channels: Int, align: Int) = wave(AudioCodecs.IMA_ADPCM, channels, 16000, 4, align, byteArrayOf(0, 0))

    /** Два тона вместо чистой синусоиды: ошибка предсказания не нулевая, как у настоящего звука. */
    private fun tone(frames: Int, channels: Int): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (i in 0 until frames) for (c in 0 until channels) {
            val t = i / 16000.0
            val v = (9000 * sin(2 * PI * (440 + 220 * c) * t) + 3000 * sin(2 * PI * 1700 * t)).toInt()
            out[(i * channels + c) * 2] = v.toByte()
            out[(i * channels + c) * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun snr(ref: ByteArray, test: ByteArray): Double {
        var sig = 0.0
        var err = 0.0
        for (i in 0 until minOf(ref.size, test.size) / 2) {
            val a = AudioCodecs.s16(ref, 2 * i)
            val b = AudioCodecs.s16(test, 2 * i)
            sig += a.toDouble() * a
            err += (a - b).toDouble() * (a - b)
        }
        return 10 * log10(sig / maxOf(err, 1.0))
    }

    @Test
    fun g711_known_codes() {
        assertEquals(0xFF, G711.encodeMu(0))
        assertEquals(0, G711.decodeMu(0xFF))
        assertEquals(0xD5, G711.encodeA(0))
        assertEquals(8, G711.decodeA(0xD5))
        // Крайние значения не переполняют код и сохраняют знак.
        assertTrue(G711.decodeMu(G711.encodeMu(32767)) > 30000)
        assertTrue(G711.decodeMu(G711.encodeMu(-32768)) < -30000)
        assertTrue(G711.decodeA(G711.encodeA(32767)) > 30000)
        assertTrue(G711.decodeA(G711.encodeA(-32768)) < -30000)
    }

    @Test
    fun g711_roundtrip_error_is_within_one_step() {
        for (alaw in listOf(false, true)) {
            val codec = G711(alaw, 1)
            var s = -32768
            while (s <= 32767) {
                val pcm = byteArrayOf(s.toByte(), (s shr 8).toByte())
                val back = AudioCodecs.s16(codec.decode(codec.encode(pcm)), 0)
                assertTrue("alaw=$alaw $s -> $back", abs(back - s) <= abs(s) / 16 + 16)
                s += 37
            }
        }
    }

    @Test
    fun g711_packet_is_frames_per_packet_in_bytes() {
        assertEquals(160 * 2, G711(false, 2).packetBytes(160))
        assertEquals(0, G711(false, 2).packetBytes(0))
    }

    @Test
    fun ima_decodes_reference_vector_from_audioop() {
        // Эталон получен классическим IMA-алгоритмом (audioop.adpcm2lin, предсказание 1000, индекс 20).
        val block = byteArrayOf(0xE8.toByte(), 0x03, 20, 0) + byteArrayOf(
            0x21, 0x43, 0x65, 0x87.toByte(), 0xA9.toByte(), 0xCB.toByte(), 0xED.toByte(), 0x0F, 0x12, 0x34, 0x56, 0x78,
        )
        val codec = AudioCodecs.codec(ima(1, 16))!!
        val pcm = codec.decode(block)
        val expected = intArrayOf(
            1000, 1018, 1045, 1080, 1121, 1182, 1289, 1509, 1478, 1393, 1263, 1098, 904, 617, 119, -901, -756, -94, 266,
            1251, 2178, 3742, 6088, 5776, 10036,
        )
        assertEquals(expected.size * 2, pcm.size)
        assertEquals(expected.toList(), List(expected.size) { AudioCodecs.s16(pcm, 2 * it) })
    }

    @Test
    fun ima_roundtrip_mono_and_stereo() {
        for ((channels, align) in listOf(1 to 256, 2 to 512, 1 to 1024)) {
            val codec = AudioCodecs.codec(ima(channels, align))!!
            val spb = (align - 4 * channels) * 8 / (4 * channels) + 1
            val src = tone(spb * 4, channels)
            val packed = codec.encode(src)
            assertEquals("целые блоки", 4 * align, packed.size)
            val back = codec.decode(packed)
            assertEquals(src.size, back.size)
            // 4 бита на отсчёт при 16 кГц на этом сигнале дают около 25 дБ; точность алгоритма подтверждена эталонами (см. KDoc).
            assertTrue("IMA ${channels}ch/$align: SNR ${snr(src, back)}", snr(src, back) > 20)
        }
    }

    @Test
    fun ms_adpcm_roundtrip_mono_and_stereo() {
        for ((channels, align) in listOf(1 to 256, 2 to 512, 1 to 1024)) {
            val codec = AudioCodecs.codec(wave(AudioCodecs.MS_ADPCM, channels, 16000, 4, align))!!
            val spb = (align - 7 * channels) * 2 / channels + 2
            val src = tone(spb * 4, channels)
            val packed = codec.encode(src)
            assertEquals(4 * align, packed.size)
            val back = codec.decode(packed)
            assertEquals(src.size, back.size)
            assertTrue("MS ${channels}ch/$align: SNR ${snr(src, back)}", snr(src, back) > 30)
        }
    }

    @Test
    fun encoder_keeps_tail_between_calls_and_gives_same_bytes_as_one_call() {
        val f = ima(1, 256)
        val src = tone(505 * 3, 1)
        val whole = AudioCodecs.codec(f)!!.encode(src)
        val parts = AudioCodecs.codec(f)!!
        val out = java.io.ByteArrayOutputStream()
        var off = 0
        // 40 мс при 16 кГц = 1280 байт: блок в 505 кадров на такие куски не делится.
        while (off < src.size) {
            val n = minOf(1280, src.size - off)
            out.write(parts.encode(src.copyOfRange(off, off + n)))
            off += n
        }
        assertArrayEquals(whole, out.toByteArray())
        assertEquals(3 * 256, whole.size)
    }

    @Test
    fun decoder_survives_truncated_and_empty_data() {
        for (f in listOf(ima(2, 512), wave(AudioCodecs.MS_ADPCM, 2, 16000, 4, 512))) {
            val codec = AudioCodecs.codec(f)!!
            assertEquals(0, codec.decode(ByteArray(0)).size)
            assertEquals(0, codec.decode(ByteArray(3)).size)
            val block = codec.encode(tone(1000, 2))
            val cut = codec.decode(block.copyOf(block.size / 2 + 3))
            assertTrue(cut.isNotEmpty() && cut.size % 4 == 0)
        }
    }

    @Test
    fun ms_adpcm_takes_coefficients_from_extra_or_uses_standard() {
        assertNotNull(AudioCodecs.codec(wave(AudioCodecs.MS_ADPCM, 1, 16000, 4, 256)))
        // extra: отсчётов на блок, число пар, пары. Повреждённый (пар больше, чем данных) формат не принимается.
        val broken = wave(AudioCodecs.MS_ADPCM, 1, 16000, 4, 256, byteArrayOf(0, 1, 7, 0, 0, 1))
        assertNull(AudioCodecs.rank(broken, true))
        val custom = wave(AudioCodecs.MS_ADPCM, 1, 16000, 4, 256, byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0))
        assertEquals(1, AudioCodecs.rank(custom, true))
    }

    @Test
    fun rank_prefers_compressed_and_rejects_by_mode_and_parameters() {
        val pcm = wave(AudioCodecs.PCM, 2, 44100, 16, 4)
        val imaFormat = ima(1, 256)
        val ms = wave(AudioCodecs.MS_ADPCM, 1, 16000, 4, 256)
        val mu = wave(AudioCodecs.MULAW, 1, 8000, 8, 1)
        val alaw = wave(AudioCodecs.ALAW, 1, 8000, 8, 1)
        // Без опции — только PCM 16 бит.
        assertEquals(10, AudioCodecs.rank(pcm, false))
        for (f in listOf(imaFormat, ms, mu, alaw)) assertNull(AudioCodecs.rank(f, false))
        // С опцией: IMA < MS < µ-law < A-law < PCM.
        val ranks = listOf(imaFormat, ms, mu, alaw, pcm).map { AudioCodecs.rank(it, true)!! }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
        // PCM 8 бит, посторонние кодеки, 3 канала, частоты вне диапазона и неверный размер блока.
        assertNull(AudioCodecs.rank(wave(AudioCodecs.PCM, 1, 8000, 8, 1), true))
        assertNull(AudioCodecs.rank(wave(0xA106, 2, 44100, 16, 4), true))
        assertNull(AudioCodecs.rank(wave(AudioCodecs.PCM, 3, 44100, 16, 6), true))
        assertNull(AudioCodecs.rank(wave(AudioCodecs.PCM, 2, 96000, 16, 4), true))
        assertNull(AudioCodecs.rank(ima(1, 255), true))
        assertNull(AudioCodecs.rank(ima(1, 0), true))
        assertNull(AudioCodecs.rank(wave(AudioCodecs.IMA_ADPCM, 1, 16000, 8, 256), true))
    }
}
