package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.RdpException
import com.hiweb.hiwebremotedesk.rdp.RfxProgressive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Progressive: преобразования, SRL и сырые биты уточнения, сборка тайлов и обрезка по прямоугольникам региона. */
class RfxProgressiveTest {
    private val gray = 0xFF808080.toInt()

    private class Blit(val tile: IntArray, val srcX: Int, val srcY: Int, val w: Int, val h: Int, val dx: Int, val dy: Int)

    private fun decoder(out: MutableList<Blit>) = RfxProgressive { tile, sx, sy, w, h, dx, dy -> out += Blit(tile, sx, sy, w, h, dx, dy) }

    // ── обратное преобразование ──────────────────────────────────────────────────────────────────

    @Test
    fun flatLowBandGivesFlatTileInBothLayouts() {
        // Только LL3: в обычной раскладке 64 значения с 4032, в extrapolate 81 значение с 4015.
        for ((extrapolate, ll, count) in listOf(Triple(false, 4032, 64), Triple(true, 4015, 81))) {
            val buf = ShortArray(4096)
            for (i in 0 until count) buf[ll + i] = 700
            decoder(ArrayList()).transform(buf, extrapolate)
            assertTrue("extrapolate=$extrapolate", buf.all { it.toInt() == 700 })
        }
    }

    @Test
    fun highBandsAddDetailAroundTheFlatLevel() {
        val buf = ShortArray(4096)
        for (i in 0 until 81) buf[4015 + i] = 100
        buf[0] = 40 // один коэффициент HL1 должен изменить картинку, но не всё поровну
        decoder(ArrayList()).transform(buf, true)
        assertTrue(buf.any { it.toInt() != 100 })
        assertTrue(buf.count { it.toInt() == 100 } > 3000)
    }

    // ── SRL и сырые биты ─────────────────────────────────────────────────────────────────────────

    private fun srl(vararg bytes: Int) = RfxProgressive.Srl(RfxProgressive.BitSource(ByteArray(bytes.size) { bytes[it].toByte() }, 0, bytes.size))

    @Test
    fun rawBitsAreReadMostSignificantFirstAndPastTheEndGiveZeros() {
        val bits = RfxProgressive.BitSource(byteArrayOf(0xA0.toByte()), 0, 1)
        assertEquals(5, bits.bits(3)) // 101
        assertEquals(0, bits.bits(5)) // остаток байта
        assertEquals(0, bits.bits(4)) // за концом данных
    }

    @Test
    fun srlZeroRunThenNegativeUnitValue() {
        // 0 → не меньше двух нулей (k=1); потом 1,0 (нулей 0), знак 1, один бит значения → −1.
        val s = srl(0x50) // 0101 0000
        assertEquals(0, s.read(1))
        assertEquals(0, s.read(1))
        assertEquals(-1, s.read(1))
    }

    @Test
    fun srlMagnitudeIsUnaryForWiderValues() {
        // 1, k-бит 0 (нулей нет), знак 0, унарная часть 0,1 → величина 2 при numBits=2.
        assertEquals(2, srl(0x88).read(2))
        // Знак минус: 1,0,1 затем 0,1.
        assertEquals(-2, srl(0xA8).read(2))
    }

    // ── потоки блоков ────────────────────────────────────────────────────────────────────────────

    private fun quant(v: Int) = ByteArray(5) { ((v shl 4) or v).toByte() }

    /** Регион с одним прямоугольником и тайлами; [tiles] — готовые блоки тайлов. */
    private fun region(rect: IntArray, extrapolate: Boolean, progressiveQuants: List<Int>, tiles: List<ByteArray>): ByteArray {
        val tileData = ByteWriter()
        tiles.forEach { tileData.bytes(it) }
        val body = ByteWriter().u8(64).u16le(1).u8(1).u8(progressiveQuants.size).u8(if (extrapolate) 1 else 0).u16le(tiles.size).u32le(tileData.size)
        rect.forEach { body.u16le(it) }
        body.bytes(quant(6))
        progressiveQuants.forEach { q -> body.u8(0).bytes(quant(q)).bytes(quant(q)).bytes(quant(q)) }
        body.bytes(tileData.toByteArray())
        return ByteWriter().u16le(0xCCC4).u32le(body.size + 6).bytes(body.toByteArray()).toByteArray()
    }

    private fun block(type: Int, body: ByteArray) = ByteWriter().u16le(type).u32le(body.size + 6).bytes(body).toByteArray()

    /** Тайл SIMPLE/FIRST без данных: все коэффициенты нулевые — серый тайл. */
    private fun emptyTile(first: Boolean, x: Int, y: Int, quality: Int = 0): ByteArray {
        val w = ByteWriter().u8(0).u8(0).u8(0).u16le(x).u16le(y).u8(0)
        if (first) w.u8(quality)
        w.u16le(0).u16le(0).u16le(0).u16le(0)
        return block(if (first) 0xCCC6 else 0xCCC5, w.toByteArray())
    }

    private fun emptyUpgrade(x: Int, y: Int, quality: Int): ByteArray {
        val w = ByteWriter().u8(0).u8(0).u8(0).u16le(x).u16le(y).u8(quality)
        repeat(6) { w.u16le(0) }
        return block(0xCCC7, w.toByteArray())
    }

    @Test
    fun simpleTileIsClippedToTheRegionRectangle() {
        val out = ArrayList<Blit>()
        // Тайл (1,0) занимает x 64..128, y 0..64; прямоугольник региона — x 70..90, y 10..40.
        decoder(out).decode(region(intArrayOf(70, 10, 20, 30), false, emptyList(), listOf(emptyTile(false, 1, 0))))
        val b = out.single()
        assertEquals(70, b.dx); assertEquals(10, b.dy); assertEquals(20, b.w); assertEquals(30, b.h)
        assertEquals(6, b.srcX); assertEquals(10, b.srcY) // смещение внутри тайла
        assertEquals(gray, b.tile[b.srcY * 64 + b.srcX])
    }

    @Test
    fun tileOutsideTheRegionRectangleIsNotEmitted() {
        val out = ArrayList<Blit>()
        decoder(out).decode(region(intArrayOf(0, 0, 10, 10), false, emptyList(), listOf(emptyTile(false, 3, 3))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun firstThenUpgradeProducesTheSameFlatTile() {
        val out = ArrayList<Blit>()
        val d = decoder(out)
        // Прогрессивные значения: качество 0 — 3 (грубее), качество 1 — 0 (полное).
        val quants = listOf(3, 0)
        d.decode(region(intArrayOf(0, 0, 64, 64), true, quants, listOf(emptyTile(true, 0, 0, quality = 0))))
        d.decode(region(intArrayOf(0, 0, 64, 64), true, quants, listOf(emptyUpgrade(0, 0, quality = 1))))
        assertEquals(2, out.size)
        assertEquals(gray, out[1].tile[0])
        assertEquals(gray, out[1].tile[64 * 64 - 1])
    }

    @Test
    fun upgradeWithoutFirstPassIsRejected() {
        try {
            decoder(ArrayList()).decode(region(intArrayOf(0, 0, 64, 64), true, listOf(0), listOf(emptyUpgrade(0, 0, quality = 0))))
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }

    @Test
    fun upgradeCannotLowerQuality() {
        val d = decoder(ArrayList())
        val quants = listOf(0, 3) // качество 1 грубее качества 0
        d.decode(region(intArrayOf(0, 0, 64, 64), true, quants, listOf(emptyTile(true, 0, 0, quality = 0))))
        try {
            d.decode(region(intArrayOf(0, 0, 64, 64), true, quants, listOf(emptyUpgrade(0, 0, quality = 1))))
            fail("ожидалась ошибка")
        } catch (_: RdpException) {
        }
    }

    @Test
    fun channelPaintsProgressiveTilesOnTheSurface() {
        val rig = Rig(avcEnabled = false)
        rig.feed(rig.createSurface(1, 200, 100), rig.mapSurface(1, 0, 0))
        rig.feed(rig.startFrame(1), rig.endFrame(1))
        rig.bitmaps.clear()
        rig.feed(
            rig.startFrame(2),
            rig.wire2(1, 0x0009, region(intArrayOf(70, 10, 20, 30), false, emptyList(), listOf(emptyTile(false, 1, 0)))),
            rig.endFrame(2),
        )
        val b = rig.bitmaps.single()
        assertEquals(70, b.left); assertEquals(10, b.top); assertEquals(20, b.w); assertEquals(30, b.h)
        assertTrue(b.argb.all { it == gray })
    }
}
