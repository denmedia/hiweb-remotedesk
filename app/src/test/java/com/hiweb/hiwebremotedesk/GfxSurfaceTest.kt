package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Поверхности RDPEGFX: заливка, копирование, кэш, несжатые пиксели, вывод на экран в конце кадра. */
class GfxSurfaceTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()

    /** Всё нарисованное на экран, собранное в картинку [w]×[h] (ARGB). */
    private fun screen(rig: Rig, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (b in rig.bitmaps) for (row in 0 until b.h) for (col in 0 until b.w) out[(b.top + row) * w + b.left + col] = b.argb[row * b.w + col]
        return out
    }

    private fun rig(w: Int = 32, h: Int = 32, ox: Int = 0, oy: Int = 0): Rig = Rig(avcEnabled = false).also {
        it.feed(it.createSurface(1, w, h), it.mapSurface(1, ox, oy))
        it.feed(it.startFrame(0), it.endFrame(0))
        it.bitmaps.clear()
    }

    @Test
    fun nothingReachesTheScreenBeforeEndFrame() {
        val r = rig()
        r.feed(r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 8, 8)))
        assertTrue(r.bitmaps.isEmpty())
        r.feed(r.endFrame(1))
        assertEquals(1, r.bitmaps.size)
    }

    @Test
    fun solidFillPaintsOnlyTheRectangleAtTheMappedOrigin() {
        val r = rig(ox = 100, oy = 50)
        r.feed(r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(4, 6, 12, 10)), r.endFrame(1))
        val b = r.bitmaps.single()
        assertEquals(104, b.left); assertEquals(56, b.top); assertEquals(8, b.w); assertEquals(4, b.h)
        assertTrue(b.argb.all { it == red })
    }

    @Test
    fun solidFillTakesColorFromBgrBytes() {
        val r = rig()
        r.feed(r.startFrame(1), r.solidFill(1, 0x0000FF, intArrayOf(0, 0, 2, 2)), r.endFrame(1))
        assertEquals(blue, r.bitmaps.single().argb[0])
    }

    @Test
    fun surfaceToSurfaceCopiesToEveryPointAndKeepsTheSource() {
        val r = rig()
        r.feed(
            r.startFrame(1), r.solidFill(1, 0x00FF00, intArrayOf(0, 0, 4, 4)),
            r.surfaceToSurface(1, 1, intArrayOf(0, 0, 4, 4), intArrayOf(10, 0), intArrayOf(20, 20)),
            r.endFrame(1),
        )
        val px = screen(r, 32, 32)
        assertEquals(green, px[0]); assertEquals(green, px[10]); assertEquals(green, px[20 * 32 + 20]); assertEquals(green, px[23 * 32 + 23])
        assertEquals(0, px[5]) // между копиями ничего не рисовалось
    }

    @Test
    fun surfaceToSurfaceInsideOneSurfaceHandlesOverlap() {
        val r = rig()
        // Полосы по колонкам: x=0 красная, x=1 зелёная, x=2 синяя, x=3 красная; сдвигаем блок 0..4 на одну точку вправо.
        r.feed(
            r.startFrame(1),
            r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 1, 1), intArrayOf(3, 0, 4, 1)),
            r.solidFill(1, 0x00FF00, intArrayOf(1, 0, 2, 1)),
            r.solidFill(1, 0x0000FF, intArrayOf(2, 0, 3, 1)),
            r.surfaceToSurface(1, 1, intArrayOf(0, 0, 4, 1), intArrayOf(1, 0)),
            r.endFrame(1),
        )
        val px = screen(r, 32, 32)
        assertEquals(listOf(red, red, green, blue, red), px.take(5))
    }

    @Test
    fun cacheKeepsAnImageAfterTheSurfaceChanges() {
        val r = rig()
        r.feed(
            r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 4, 4)),
            r.surfaceToCache(1, 7, intArrayOf(0, 0, 4, 4)),
            r.solidFill(1, 0x00FF00, intArrayOf(0, 0, 4, 4)),
            r.cacheToSurface(7, 1, intArrayOf(8, 8)),
            r.endFrame(1),
        )
        val px = screen(r, 32, 32)
        assertEquals(green, px[0])           // исходное место закрашено заново
        assertEquals(red, px[8 * 32 + 8])    // а образ из кэша остался красным
        assertEquals(red, px[11 * 32 + 11])
    }

    @Test
    fun evictedCacheEntryDrawsNothing() {
        val r = rig()
        r.feed(
            r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 4, 4)),
            r.surfaceToCache(1, 7, intArrayOf(0, 0, 4, 4)), r.evict(7),
            r.cacheToSurface(7, 1, intArrayOf(8, 8)), r.endFrame(1),
        )
        assertEquals(0, screen(r, 32, 32)[8 * 32 + 8]) // на месте образа ничего не нарисовано
    }

    @Test
    fun uncompressedPixelsAreBgrxAtTheDestinationRectangle() {
        val r = rig()
        val data = ByteWriter()
        repeat(2 * 2) { data.u8(0x10).u8(0x20).u8(0x30).u8(0xFF) } // B=0x10 G=0x20 R=0x30
        r.feed(r.startFrame(1), r.wire1(1, 0x0000, data.toByteArray(), left = 5, top = 6, right = 7, bottom = 8), r.endFrame(1))
        val b = r.bitmaps.single()
        assertEquals(5, b.left); assertEquals(6, b.top); assertEquals(2, b.w); assertEquals(2, b.h)
        assertEquals(0xFF302010.toInt(), b.argb[0])
    }

    @Test
    fun drawingBeyondTheSurfaceIsClipped() {
        val r = rig()
        r.feed(r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(28, 28, 60, 60)), r.endFrame(1))
        val b = r.bitmaps.single()
        assertEquals(28, b.left); assertEquals(4, b.w); assertEquals(4, b.h)
    }

    @Test
    fun resetGraphicsDropsSurfacesAndReportsTheNewDesktopSize() {
        val r = rig()
        r.feed(r.resetGraphics(1200, 1920))
        assertEquals(listOf(1200 to 1920), r.sizes)
        // Поверхности больше нет: заливка в неё ничего не рисует.
        r.feed(r.startFrame(2), r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 4, 4)), r.endFrame(2))
        assertTrue(r.bitmaps.isEmpty())
    }

    @Test
    fun everyFrameIsAcknowledgedWithItsIdAndRunningTotal() {
        val r = rig()
        r.feed(r.startFrame(11), r.endFrame(11), r.startFrame(12), r.endFrame(12))
        val acks = r.sent.drop(1).map { ByteReader(it, 8) }.map { Triple(it.u32le(), it.u32le(), it.u32le()) }
        // Первые два подтверждения — кадры из rig(), затем 11 и 12.
        assertEquals(listOf(11L, 12L), acks.takeLast(2).map { it.second })
        assertEquals(listOf(2L, 3L), acks.takeLast(2).map { it.third }.map { it })
    }

    @Test
    fun classicProfileDoesNotOfferVersion10() {
        val r = Rig(avcEnabled = false, classic = true)
        val rd = ByteReader(r.sent[0], 8)
        val n = rd.u16le()
        assertEquals(2, n)
        val versions = List(n) { val v = rd.u32le(); rd.u32le(); rd.u32le(); v }
        assertTrue(versions.none { it >= 0x000A0000L })
    }

    @Test
    fun mappingAloneDoesNotPaintTheSurfaceBlack() {
        // Монтирование без рисования ничего не выводит: иначе при смене размера чёрный кадр затирает прежнюю картинку.
        val r = Rig(avcEnabled = false)
        r.feed(r.createSurface(1, 32, 32), r.mapSurface(1, 0, 0))
        r.feed(r.startFrame(1), r.endFrame(1))
        assertTrue(r.bitmaps.isEmpty())
    }

    @Test
    fun drawingBeforeMappingIsShownOnceTheSurfaceIsMapped() {
        val r = Rig(avcEnabled = false)
        r.feed(r.createSurface(1, 32, 32))
        r.feed(r.startFrame(1), r.solidFill(1, 0xFF0000, intArrayOf(0, 0, 8, 8)), r.endFrame(1))
        assertTrue(r.bitmaps.isEmpty())
        r.feed(r.mapSurface(1, 10, 20))
        r.feed(r.startFrame(2), r.endFrame(2))
        val b = r.bitmaps.single()
        assertEquals(10, b.left); assertEquals(20, b.top); assertEquals(8, b.w)
        assertEquals(red, b.argb[0])
    }

    @Test
    fun regionsAreHandedOverWithoutCopyingAndWithTheSurfaceStride() {
        val r = Rig(avcEnabled = false, useRegions = true)
        r.feed(r.createSurface(1, 32, 24), r.mapSurface(1, 100, 50))
        r.feed(r.startFrame(1), r.solidFill(1, 0x00FF00, intArrayOf(4, 6, 12, 10)), r.endFrame(1))
        val g = r.regions.single()
        assertEquals(6 * 32 + 4, g.offset); assertEquals(32, g.stride)
        assertEquals(104, g.left); assertEquals(56, g.top); assertEquals(8, g.w); assertEquals(4, g.h)
        assertEquals(green, g.first)
        assertTrue(r.bitmaps.isEmpty()) // копия не делалась
    }
}
