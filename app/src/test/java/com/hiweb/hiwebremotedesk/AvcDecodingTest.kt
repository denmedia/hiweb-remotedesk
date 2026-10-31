package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.Avc420Stream
import com.hiweb.hiwebremotedesk.rdp.Avc444Stream
import com.hiweb.hiwebremotedesk.rdp.AvcDecoder
import com.hiweb.hiwebremotedesk.rdp.AvcDecoderFactory
import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.GfxChannel
import com.hiweb.hiwebremotedesk.rdp.RdpException
import com.hiweb.hiwebremotedesk.rdp.YuvConverter
import com.hiweb.hiwebremotedesk.rdp.YuvPlanes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

/** H.264 в графическом канале: разбор потока AVC420, перевод YUV в ARGB и обработка команд с подставным декодером. */
class AvcDecodingTest {
    // ── поток AVC420 ──────────────────────────────────────────────────────────────────────────────

    private fun avc420(rects: List<IntArray>, bitstream: ByteArray): ByteArray {
        val w = ByteWriter().u32le(rects.size)
        rects.forEach { r -> r.forEach { w.u16le(it) } }
        rects.forEach { _ -> w.u8(22).u8(78) } // QP и качество: клиенту не нужны
        return w.bytes(bitstream).toByteArray()
    }

    @Test
    fun streamSplitsRegionsFromBitstream() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val s = Avc420Stream.parse(avc420(listOf(intArrayOf(0, 0, 64, 32), intArrayOf(16, 48, 80, 96)), nal))
        assertEquals(2, s.regions.size)
        assertEquals(64, s.regions[0].width); assertEquals(32, s.regions[0].height)
        assertEquals(16, s.regions[1].left); assertEquals(48, s.regions[1].top)
        assertEquals(80, s.regions[1].right); assertEquals(96, s.regions[1].bottom)
        assertArrayEquals(nal, s.bitstream)
    }

    @Test
    fun streamWithoutRegionsKeepsWholeBitstream() {
        val s = Avc420Stream.parse(avc420(emptyList(), byteArrayOf(9, 9)))
        assertTrue(s.regions.isEmpty())
        assertArrayEquals(byteArrayOf(9, 9), s.bitstream)
    }

    @Test
    fun streamRespectsOffsetAndEnd() {
        val body = avc420(listOf(intArrayOf(1, 2, 3, 4)), byteArrayOf(7))
        val padded = byteArrayOf(0x55, 0x55) + body + byteArrayOf(0x66)
        val s = Avc420Stream.parse(padded, 2, 2 + body.size)
        assertArrayEquals(byteArrayOf(7), s.bitstream)
    }

    @Test
    fun streamWithImpossibleRegionCountIsRejected() {
        // Заявлено 1000 прямоугольников, а данных на один: не должно ни выделять память, ни читать за границу.
        val bad = ByteWriter().u32le(1000).u16le(0).u16le(0).u16le(16).u16le(16).u8(1).u8(1).toByteArray()
        try {
            Avc420Stream.parse(bad)
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }

    // ── YUV → ARGB ────────────────────────────────────────────────────────────────────────────────

    /** Кадр 4:2:0 из раздельных плоскостей, одинаковый по всей картинке. */
    private fun flat(w: Int, h: Int, y: Int, u: Int, v: Int, pad: Int = 0): YuvPlanes {
        val yStride = w + pad
        val cw = w / 2 + pad
        val yb = ByteArray(yStride * h) { y.toByte() }
        val ub = ByteArray(cw * (h / 2)) { u.toByte() }
        val vb = ByteArray(cw * (h / 2)) { v.toByte() }
        return YuvPlanes(w, h, ByteBuffer.wrap(yb), yStride, ByteBuffer.wrap(ub), cw, 1, ByteBuffer.wrap(vb), cw, 1)
    }

    private fun channels(argb: Int) = intArrayOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    @Test
    fun grayscaleKeepsLumaAndIsOpaque() {
        for (level in intArrayOf(0, 1, 100, 200, 255)) {
            val px = YuvConverter.toArgb(flat(16, 16, level, 128, 128), 0, 0, 16, 16)
            assertTrue(px.all { it ushr 24 == 0xFF })
            px.forEach { assertArrayEquals(intArrayOf(level, level, level), channels(it)) }
        }
    }

    @Test
    fun primariesFollowBt709FullRange() {
        // Прямое преобразование RGB→YUV для BT.709 (полный диапазон), 256-ичное округление как у FreeRDP.
        fun yuv(r: Int, g: Int, b: Int): IntArray {
            val y = (54 * r + 183 * g + 18 * b) shr 8
            val u = ((-29 * r - 99 * g + 128 * b) shr 8) + 128
            val v = ((128 * r - 116 * g - 12 * b) shr 8) + 128
            return intArrayOf(y, u, v)
        }
        val cases = listOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 0), intArrayOf(40, 120, 200))
        for (c in cases) {
            val (y, u, v) = yuv(c[0], c[1], c[2]).toList()
            val got = channels(YuvConverter.toArgb(flat(16, 16, y, u, v), 0, 0, 1, 1)[0])
            for (i in 0..2) assertTrue("канал $i у ${c.toList()}: ${got.toList()}", kotlin.math.abs(got[i] - c[i]) <= 4)
        }
    }

    @Test
    fun outOfRangeResultsAreClamped() {
        val px = YuvConverter.toArgb(flat(16, 16, 255, 255, 255), 0, 0, 2, 2)
        assertEquals(0xFF, channels(px[0])[0]) // красный выходит за 255
        val dark = YuvConverter.toArgb(flat(16, 16, 0, 0, 0), 0, 0, 2, 2)
        assertEquals(0, channels(dark[0])[0]) // красный уходит ниже нуля
    }

    @Test
    fun interleavedChromaAndStridePaddingAreRead() {
        // Полуплоскостной формат (NV12): U и V через байт в одном буфере, строки с запасом.
        val w = 16
        val h = 16
        val stride = 32
        val yb = ByteArray(stride * h) { 128.toByte() }
        val uv = ByteArray(stride * (h / 2))
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            uv[row * stride + col * 2] = (if (col < 4) 90 else 160).toByte()     // U
            uv[row * stride + col * 2 + 1] = (if (row < 4) 70 else 180).toByte() // V
        }
        val buf = ByteBuffer.wrap(uv)
        val planes = YuvPlanes(w, h, ByteBuffer.wrap(yb), stride, buf, stride, 2, ByteBuffer.wrap(uv, 1, uv.size - 1).slice(), stride, 2)
        val px = YuvConverter.toArgb(planes, 0, 0, w, h)
        // Левая верхняя четверть: U=90, V=70 → синий ниже серого, красный ниже серого.
        val tl = channels(px[0])
        val br = channels(px[(h - 1) * w + (w - 1)])
        assertTrue(tl[2] < 128 && tl[0] < 128)
        assertTrue(br[2] > 128 && br[0] > 128)
    }

    @Test
    fun chromaIsSharedBy2x2BlocksAndSubRegionsMatchTheWhole() {
        val w = 16
        val h = 16
        val yb = ByteArray(w * h) { ((it * 7) and 0xFF).toByte() }
        val ub = ByteArray(w * h / 4) { ((it * 13) and 0xFF).toByte() }
        val vb = ByteArray(w * h / 4) { ((it * 29) and 0xFF).toByte() }
        val planes = YuvPlanes(w, h, ByteBuffer.wrap(yb), w, ByteBuffer.wrap(ub), w / 2, 1, ByteBuffer.wrap(vb), w / 2, 1)
        val whole = YuvConverter.toArgb(planes, 0, 0, w, h)
        // Вырезка с нечётным левым краем и нечётной верхней строкой должна совпасть с тем же куском целого кадра.
        val part = YuvConverter.toArgb(planes, 3, 5, 12, 14)
        for (row in 0 until 9) for (col in 0 until 9) assertEquals(whole[(5 + row) * w + 3 + col], part[row * 9 + col])
    }

    @Test
    fun regionOutsideTheFrameIsRefused() {
        try {
            YuvConverter.toArgb(flat(16, 16, 0, 128, 128), 0, 0, 32, 16)
            fail("ожидалась ошибка")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── канал: подставной декодер ────────────────────────────────────────────────────────────────

    private fun advertisedCaps(rig: Rig): List<Pair<Long, Long>> {
        val r = ByteReader(rig.sent[0], 8)
        val n = r.u16le()
        return List(n) { val v = r.u32le(); r.u32le(); v to r.u32le() }
    }

    @Test
    fun withHardwareDecoderOnlyVersion81WithAvc420IsOffered() {
        val caps = advertisedCaps(Rig())
        assertEquals(listOf(0x00080004L to 0x02L, 0x00080105L to 0x12L), caps)
    }

    @Test
    fun withoutDecoderAvcStaysDisabled() {
        val caps = advertisedCaps(Rig(avcEnabled = false))
        assertEquals(0x000A0002L to 0x22L, caps.last())
        assertTrue(caps.none { it.second and 0x10L != 0L })
    }

    @Test
    fun avc420FrameGoesToDecoderAndRegionsReturnAsBitmapsAtSurfaceOrigin() {
        val rig = Rig()
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 5, 6)
        rig.feed(
            rig.createSurface(1, 100, 50), rig.mapSurface(1, 10, 20),
            rig.wire1(1, 0x000B, avc420(listOf(intArrayOf(16, 8, 48, 24), intArrayOf(0, 40, 100, 50)), nal)),
            rig.endFrame(7),
        )
        val d = rig.decoders.single()
        assertEquals(112, d.width); assertEquals(64, d.height) // размер кадра — поверхность, округлённая до 16
        assertArrayEquals(nal, d.inputs.single().first)
        rig.bitmaps.clear() // начальная заливка при монтировании поверхности не в счёт
        // Картинки ещё нет: декодер не ответил.
        // Кадр подтверждается сразу, не дожидаясь декодера.
        assertEquals(2, rig.sent.size)

        // Декодер вернул кадр 112x64, залитый красным.
        d.onFrame(flat(112, 64, 54, 102, 240), d.inputs.single().second)
        assertEquals(2, rig.bitmaps.size)
        val a = rig.bitmaps[0]
        assertEquals(10 + 16, a.left); assertEquals(20 + 8, a.top); assertEquals(32, a.w); assertEquals(16, a.h)
        assertEquals(32 * 16, a.argb.size)
        assertTrue(channels(a.argb[0])[0] > 200) // красный
        // Вторая область выходит за ширину поверхности (100): обрезается по ней, а не по кадру 112.
        val b = rig.bitmaps[1]
        assertEquals(10, b.left); assertEquals(20 + 40, b.top); assertEquals(100, b.w); assertEquals(10, b.h)
    }

    @Test
    fun oneDecoderPerSurfaceAndItIsReleasedWithTheSurface() {
        val rig = Rig()
        val frame = avc420(listOf(intArrayOf(0, 0, 16, 16)), byteArrayOf(1))
        rig.feed(rig.createSurface(1, 64, 64), rig.mapSurface(1, 0, 0), rig.wire1(1, 0x000B, frame), rig.wire1(1, 0x000B, frame))
        assertEquals(1, rig.creates)
        assertEquals(2, rig.decoders.single().inputs.size)
        rig.feed(rig.deleteSurface(1))
        assertTrue(rig.decoders.single().released)
    }

    @Test
    fun closingTheChannelReleasesDecoders() {
        val rig = Rig()
        rig.feed(rig.createSurface(1, 64, 64), rig.mapSurface(1, 0, 0), rig.wire1(1, 0x000B, avc420(emptyList(), byteArrayOf(1))))
        rig.channel.onClose()
        assertTrue(rig.decoders.single().released)
    }

    @Test
    fun frameForUnmappedSurfaceIsNotDrawn() {
        val rig = Rig()
        rig.feed(rig.createSurface(1, 64, 64), rig.wire1(1, 0x000B, avc420(listOf(intArrayOf(0, 0, 16, 16)), byteArrayOf(1))))
        val d = rig.decoders.single()
        d.onFrame(flat(64, 64, 128, 128, 128), d.inputs.single().second)
        assertTrue(rig.bitmaps.isEmpty())
    }

    @Test
    fun failedDecoderCreationIsNotRetriedOnEveryFrame() {
        val rig = Rig(createFails = true)
        val frame = avc420(listOf(intArrayOf(0, 0, 16, 16)), byteArrayOf(1))
        rig.feed(rig.createSurface(1, 64, 64), rig.mapSurface(1, 0, 0), rig.wire1(1, 0x000B, frame), rig.wire1(1, 0x000B, frame))
        assertEquals(1, rig.creates)
        assertTrue(rig.bitmaps.isEmpty())
    }

    @Test
    fun avcFrameWithoutFactoryIsIgnored() {
        val rig = Rig(avcEnabled = false)
        rig.feed(rig.createSurface(1, 64, 64), rig.mapSurface(1, 0, 0), rig.wire1(1, 0x000B, avc420(emptyList(), byteArrayOf(1))))
        assertTrue(rig.bitmaps.isEmpty())
        assertFalse(rig.decoders.isNotEmpty())
    }

    @Test
    fun damagedAvcPduDoesNotBreakTheChannel() {
        val rig = Rig()
        // Битый поток (заявлено больше прямоугольников, чем данных), затем нормальная команда: канал живёт дальше.
        rig.feed(rig.createSurface(1, 64, 64), rig.mapSurface(1, 0, 0), rig.wire1(1, 0x000B, ByteWriter().u32le(50).toByteArray()))
        rig.feed(rig.wire1(1, 0x000B, avc420(listOf(intArrayOf(0, 0, 16, 16)), byteArrayOf(1))))
        assertEquals(1, rig.decoders.single().inputs.size)
    }

    // ── AVC444 ────────────────────────────────────────────────────────────────────────────────────

    /** Команда AVC444: слово (LC и размер первого потока), первый поток и при LC=0 второй. */
    private fun avc444(lc: Int, first: ByteArray, second: ByteArray? = null): ByteArray {
        val w = ByteWriter().u32le((lc.toLong() shl 30) or first.size.toLong()).bytes(first)
        if (second != null) w.bytes(second)
        return w.toByteArray()
    }

    @Test
    fun avc444WithLumaOnlyKeepsTheMainView() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 9)
        val s = Avc444Stream.parse(avc444(1, avc420(listOf(intArrayOf(0, 0, 16, 16)), nal)))
        assertEquals(1, s.lc)
        assertFalse(s.hasChroma)
        assertArrayEquals(nal, s.luma!!.bitstream)
        assertEquals(16, s.luma!!.regions.single().width)
    }

    @Test
    fun avc444WithBothViewsBoundsTheFirstStreamBySizeFromTheHeader() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(7, 7, 7, 7)
        val s = Avc444Stream.parse(avc444(0, avc420(emptyList(), a), avc420(emptyList(), b)))
        assertTrue(s.hasChroma)
        assertArrayEquals(a, s.luma!!.bitstream) // второй поток в первый не попадает
    }

    @Test
    fun avc444ChromaOnlyHasNoMainView() {
        val s = Avc444Stream.parse(avc444(2, avc420(emptyList(), byteArrayOf(5))))
        assertEquals(null, s.luma)
    }

    @Test
    fun avc444WithInvalidLcOrOversizedFirstStreamIsRejected() {
        for (bad in listOf(
            ByteWriter().u32le(3L shl 30).toByteArray(),
            ByteWriter().u32le(500L).u32le(0).toByteArray(),
        )) {
            try {
                Avc444Stream.parse(bad)
                fail("ожидалась ошибка")
            } catch (_: RdpException) {
            }
        }
    }

    @Test
    fun channelDecodesTheMainViewOfAvc444AndSkipsTheAuxiliaryOne() {
        val rig = Rig()
        val main = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val aux = byteArrayOf(0, 0, 0, 1, 0x41, 2)
        val region = listOf(intArrayOf(0, 0, 32, 16))
        rig.feed(rig.createSurface(1, 64, 32), rig.mapSurface(1, 0, 0))
        rig.feed(
            rig.wire1(1, 0x000F, avc444(1, avc420(region, main))),
            rig.wire1(1, 0x000F, avc444(0, avc420(region, main), avc420(region, aux))),
            rig.wire1(1, 0x000E, avc444(2, avc420(region, aux))),
        )
        val d = rig.decoders.single()
        // Основной вид — оба раза; чисто дополнительный (LC=2) и второй поток при LC=0 декодеру не передаются.
        assertEquals(2, d.inputs.size)
        d.inputs.forEach { assertArrayEquals(main, it.first) }
    }

    @Test
    fun windowsProfileOffersVersion103WithThinClientFlagAndGnomeDoesNot() {
        val windows = advertisedCaps(Rig(classic = true))
        assertEquals(listOf(0x00080004L, 0x00080105L, 0x000A0301L), windows.map { it.first })
        assertEquals(0x42L, windows.last().second) // малый кэш и «тонкий клиент»
        val gnome = advertisedCaps(Rig(classic = false))
        assertTrue(gnome.none { it.first >= 0x000A0000L })
    }
}
