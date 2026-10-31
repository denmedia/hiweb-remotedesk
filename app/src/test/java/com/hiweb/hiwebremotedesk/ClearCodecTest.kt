package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.ClearCodec
import com.hiweb.hiwebremotedesk.rdp.RdpException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ClearCodec на синтетических потоках: остаток, полосы с кэшем столбцов, глифы, подкодеки. */
class ClearCodecTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val black = 0xFF000000.toInt()

    private fun canvas(w: Int, h: Int, fill: Int = black) = IntArray(w * h) { fill }

    /** Составной поток: заголовок (флаги глифа и номер) и три слоя. */
    private fun stream(flags: Int = 0, seq: Int = 0, glyph: Int? = null, residual: ByteArray = ByteArray(0), bands: ByteArray = ByteArray(0), sub: ByteArray = ByteArray(0)): ByteArray {
        val w = ByteWriter().u8(flags).u8(seq)
        if (glyph != null) w.u16le(glyph)
        return w.u32le(residual.size).u32le(bands.size).u32le(sub.size).bytes(residual).bytes(bands).bytes(sub).toByteArray()
    }

    /** Прогон остатка: цвет B,G,R и длина (с продолжением для больших). */
    private fun run(r: Int, g: Int, b: Int, length: Int): ByteArray {
        val w = ByteWriter().u8(b).u8(g).u8(r)
        when {
            length < 0xFF -> w.u8(length)
            length < 0xFFFF -> w.u8(0xFF).u16le(length)
            else -> w.u8(0xFF).u16le(0xFFFF).u32le(length)
        }
        return w.toByteArray()
    }

    private fun band(xStart: Int, xEnd: Int, yStart: Int, yEnd: Int, bkg: Triple<Int, Int, Int>, vararg bars: ByteArray): ByteArray {
        val w = ByteWriter().u16le(xStart).u16le(xEnd).u16le(yStart).u16le(yEnd).u8(bkg.third).u8(bkg.second).u8(bkg.first)
        bars.forEach { w.bytes(it) }
        return w.toByteArray()
    }

    /** Столбец с промахом кэша: пиксели с отступа [yOn] до [yOff]. */
    private fun vbarMiss(yOn: Int, vararg pixels: Triple<Int, Int, Int>): ByteArray {
        val w = ByteWriter().u16le(yOn or ((yOn + pixels.size) shl 8))
        pixels.forEach { w.u8(it.third).u8(it.second).u8(it.first) }
        return w.toByteArray()
    }

    @Test
    fun residualFillsTheWholeAreaWithRuns() {
        val c = canvas(4, 2)
        ClearCodec().decode(stream(residual = run(10, 20, 30, 5) + run(1, 2, 3, 3)), 4, 2, c)
        assertEquals(List(5) { rgb(10, 20, 30) } + List(3) { rgb(1, 2, 3) }, c.toList())
    }

    @Test
    fun residualUsesExtendedRunLengths() {
        val c = canvas(300, 1)
        ClearCodec().decode(stream(residual = run(9, 8, 7, 300)), 300, 1, c)
        assertTrue(c.all { it == rgb(9, 8, 7) })
    }

    @Test
    fun residualThatDoesNotCoverTheAreaIsRejected() {
        try {
            ClearCodec().decode(stream(residual = run(1, 1, 1, 7)), 4, 2, canvas(4, 2))
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }

    @Test
    fun bandsDrawVerticalBarsOverTheBase() {
        val base = rgb(50, 50, 50)
        val c = canvas(4, 4, base)
        val red = Triple(255, 0, 0)
        val green = Triple(0, 255, 0)
        val bkg = Triple(0, 0, 255)
        // Столбцы x=1 и x=2: первый — промах кэша (пиксели на строках 1–2), второй — попадание в кэш столбцов (индекс 0).
        val bar0 = vbarMiss(1, red, green)
        val bar1 = ByteWriter().u16le(0x8000).toByteArray()
        ClearCodec().decode(stream(bands = band(1, 2, 0, 3, bkg, bar0, bar1)), 4, 4, c)
        val blue = rgb(0, 0, 255)
        for (x in 1..2) {
            assertEquals(listOf(blue, rgb(255, 0, 0), rgb(0, 255, 0), blue), (0 until 4).map { c[it * 4 + x] })
        }
        assertEquals(base, c[0]); assertEquals(base, c[3]) // вне полосы основа не тронута
    }

    @Test
    fun shortBarCacheHitReusesPixelsWithANewOffset() {
        val codec = ClearCodec()
        val red = Triple(255, 0, 0)
        val bkg = Triple(0, 0, 255)
        codec.decode(stream(bands = band(0, 0, 0, 3, bkg, vbarMiss(0, red))), 1, 4, canvas(1, 4))
        // Короткий столбец 0 из одного красного пикселя теперь берётся из кэша, но ставится на строку 2.
        val c = canvas(1, 4)
        val hit = ByteWriter().u16le(0x4000).u8(2).toByteArray()
        codec.decode(stream(bands = band(0, 0, 0, 3, bkg, hit)), 1, 4, c)
        val blue = rgb(0, 0, 255)
        assertEquals(listOf(blue, blue, rgb(255, 0, 0), blue), c.toList())
    }

    @Test
    fun cacheResetRestartsStorageFromTheFirstEntry() {
        val codec = ClearCodec()
        val bkg = Triple(0, 0, 0)
        codec.decode(stream(bands = band(0, 0, 0, 0, bkg, vbarMiss(0, Triple(1, 1, 1)))), 1, 1, canvas(1, 1))
        codec.decode(stream(bands = band(0, 0, 0, 0, bkg, vbarMiss(0, Triple(2, 2, 2)))), 1, 1, canvas(1, 1)) // запись 1
        // После сброса новая запись снова ложится в индекс 0 — обращение к нему даёт новый цвет.
        codec.decode(stream(flags = 0x04, bands = band(0, 0, 0, 0, bkg, vbarMiss(0, Triple(3, 3, 3)))), 1, 1, canvas(1, 1))
        val c = canvas(1, 1)
        codec.decode(stream(bands = band(0, 0, 0, 0, bkg, ByteWriter().u16le(0x8000).toByteArray())), 1, 1, c)
        assertEquals(rgb(3, 3, 3), c[0])
    }

    @Test
    fun glyphIsStoredAndLaterReplayedFromTheCache() {
        val codec = ClearCodec()
        val first = canvas(3, 1)
        codec.decode(stream(flags = 0x01, glyph = 5, residual = run(4, 5, 6, 2) + run(7, 8, 9, 1)), 3, 1, first)
        val replay = canvas(3, 1)
        // Попадание: ни одного слоя, только индекс (в потоке меньше 12 байт после него).
        codec.decode(byteArrayOf(0x03, 0x01, 5, 0), 3, 1, replay)
        assertArrayEquals(first, replay)
    }

    @Test
    fun glyphHitForUnknownIndexIsRejected() {
        try {
            ClearCodec().decode(byteArrayOf(0x03, 0x00, 9, 0), 3, 1, canvas(3, 1))
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }

    private fun sub(x: Int, y: Int, w: Int, h: Int, id: Int, body: ByteArray) =
        ByteWriter().u16le(x).u16le(y).u16le(w).u16le(h).u32le(body.size).u8(id).bytes(body).toByteArray()

    @Test
    fun uncompressedSubcodecPaintsItsRectangle() {
        val c = canvas(4, 3)
        val body = ByteWriter().u8(3).u8(2).u8(1).u8(6).u8(5).u8(4).toByteArray() // B,G,R ×2 → (1,2,3) и (4,5,6)
        ClearCodec().decode(stream(sub = sub(1, 2, 2, 1, 0, body)), 4, 3, c)
        assertEquals(rgb(1, 2, 3), c[2 * 4 + 1]); assertEquals(rgb(4, 5, 6), c[2 * 4 + 2])
        assertEquals(black, c[0])
    }

    @Test
    fun rlexSubcodecExpandsRunsAndSuites() {
        // Палитра из двух цветов, 1 бит на индекс. Прогон: 2 точки цвета 0, затем набор из двух цветов с 0-го.
        val palette = ByteWriter().u8(2).u8(0).u8(0).u8(255).u8(255).u8(0).u8(0).toByteArray() // (255,0,0) и (0,0,255)
        val body = palette + byteArrayOf(((1 shl 1) or 1).toByte(), 2)
        val c = canvas(2, 2)
        ClearCodec().decode(stream(sub = sub(0, 0, 2, 2, 2, body)), 2, 2, c)
        val r = rgb(255, 0, 0)
        val b = rgb(0, 0, 255)
        assertEquals(listOf(r, r, r, b), c.toList())
    }

    @Test
    fun nscodecSubcodecIsSkippedWithoutBreakingTheRest() {
        val logs = ArrayList<String>()
        val c = canvas(2, 1)
        val nsc = sub(0, 0, 1, 1, 1, byteArrayOf(1, 2, 3))
        val raw = sub(1, 0, 1, 1, 0, byteArrayOf(0, 0, 9))
        ClearCodec { logs += it }.decode(stream(sub = nsc + raw), 2, 1, c)
        assertEquals(rgb(9, 0, 0), c[1])
        assertEquals(1, logs.size)
    }

    @Test
    fun subcodecOutsideTheAreaIsRejected() {
        try {
            ClearCodec().decode(stream(sub = sub(3, 0, 2, 1, 0, ByteArray(6))), 4, 1, canvas(4, 1))
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }
}
