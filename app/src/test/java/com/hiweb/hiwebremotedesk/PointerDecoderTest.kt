package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.ByteReader
import com.hiweb.hiwebremotedesk.rdp.ByteWriter
import com.hiweb.hiwebremotedesk.rdp.PointerDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerDecoderTest {
    private fun newPointer(bpp: Int, w: Int, h: Int, xor: ByteArray, and: ByteArray) = ByteWriter()
        .u16le(bpp).u16le(3).u16le(1).u16le(2).u16le(w).u16le(h).u16le(and.size).u16le(xor.size)
        .bytes(xor).bytes(and).toByteArray()

    @Test
    fun alphaCursorKeepsAlphaAndFlipsRows() {
        // 2x2, 32 bpp (пиксель = 0xAARRGGBB): нижняя строка в данных идёт первой.
        val xor = ByteWriter()
            .u32le(0x80FF0000L).u32le(0x00000000L)   // нижняя строка: полупрозрачный красный, прозрачный
            .u32le(0xFF00FF00L).u32le(0xFF0000FFL)   // верхняя строка
            .toByteArray()
        val p = PointerDecoder.parse(ByteReader(newPointer(32, 2, 2, xor, ByteArray(4))), true)
        assertEquals(3, p.cacheIndex)
        assertEquals(1, p.image.hotX)
        assertEquals(1, p.image.hotY) // 2 ограничено размером курсора
        assertNull(p.image.invert)
        assertEquals(0xFF00FF00.toInt(), p.image.argb[0])
        assertEquals(0xFF0000FF.toInt(), p.image.argb[1])
        assertEquals(0x80FF0000.toInt(), p.image.argb[2])
        assertEquals(0, p.image.argb[3] ushr 24)
    }

    @Test
    fun monoCursorUsesAndMaskForTransparencyAndInversion() {
        // 8x2, 1 bpp. Строки по 2 байта, снизу вверх.
        // Нижняя строка (y=1): and=1, xor=0 → прозрачно.
        // Верхняя строка (y=0): пиксель 0: and=0,xor=1 → белый; пиксель 1: and=1,xor=1 → инверсия; пиксель 2: and=0,xor=0 → чёрный.
        val xor = byteArrayOf(0, 0, 0b1100_0000.toByte(), 0)
        val and = byteArrayOf(0xFF.toByte(), 0, 0b0100_0000, 0)
        val img = PointerDecoder.parse(ByteReader(newPointer(1, 8, 2, xor, and)), true).image
        assertEquals(0xFFFFFFFF.toInt(), img.argb[0])
        assertEquals(0, img.argb[1])
        assertEquals(0xFF000000.toInt(), img.argb[2])
        val inv = img.invert
        assertNotNull(inv)
        assertTrue(inv!![1])
        assertTrue(!inv[0] && !inv[2])
        assertEquals(0, img.argb[8]) // нижняя строка прозрачна
    }

    @Test
    fun colorPointerIs24Bit() {
        // 1x1, 24 bpp: строка 3 байта + выравнивание до 4; маска 2 байта.
        val w = ByteWriter().u16le(0).u16le(0).u16le(0).u16le(1).u16le(1).u16le(2).u16le(4)
            .bytes(byteArrayOf(0x10, 0x20, 0x30, 0)).bytes(byteArrayOf(0, 0)).toByteArray()
        val img = PointerDecoder.parse(ByteReader(w), false).image
        assertArrayEquals(intArrayOf(0xFF302010.toInt()), img.argb)
    }
}
