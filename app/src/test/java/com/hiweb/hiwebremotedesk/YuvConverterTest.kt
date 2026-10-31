package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.YuvConverter
import com.hiweb.hiwebremotedesk.rdp.YuvPlanes
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/** Быстрый (построчный, многопоточный) перевод YUV в ARGB даёт то же, что прямая формула по точкам. */
class YuvConverterTest {
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun reference(p: YuvPlanes, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val w = right - left
        val out = IntArray(w * (bottom - top))
        for (y in top until bottom) for (x in left until right) {
            val yy = (p.y.get(y * p.yStride + x).toInt() and 0xFF) shl 8
            val u = (p.u.get((y shr 1) * p.uStride + (x shr 1) * p.uPixel).toInt() and 0xFF) - 128
            val v = (p.v.get((y shr 1) * p.vStride + (x shr 1) * p.vPixel).toInt() and 0xFF) - 128
            out[(y - top) * w + x - left] = (0xFF shl 24) or (clamp((yy + 403 * v) shr 8) shl 16) or
                (clamp((yy - 48 * u - 120 * v) shr 8) shl 8) or clamp((yy + 475 * u) shr 8)
        }
        return out
    }

    private fun randomPlanar(w: Int, h: Int, pad: Int, seed: Long): YuvPlanes {
        val rnd = Random(seed)
        val ys = w + pad
        val cs = w / 2 + pad
        fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
        return YuvPlanes(w, h, ByteBuffer.wrap(bytes(ys * h)), ys, ByteBuffer.wrap(bytes(cs * (h / 2))), cs, 1, ByteBuffer.wrap(bytes(cs * (h / 2))), cs, 1)
    }

    /** NV12: цветность чересстрочно в одном буфере (V — со сдвигом на байт), как отдаёт аппаратный декодер. */
    private fun randomSemiPlanar(w: Int, h: Int, pad: Int, seed: Long): YuvPlanes {
        val rnd = Random(seed)
        val ys = w + pad
        val ytab = ByteArray(ys * h).also { rnd.nextBytes(it) }
        val uv = ByteArray(ys * (h / 2)).also { rnd.nextBytes(it) }
        val v = ByteBuffer.wrap(uv, 1, uv.size - 1).slice()
        return YuvPlanes(w, h, ByteBuffer.wrap(ytab), ys, ByteBuffer.wrap(uv), ys, 2, v, ys, 2)
    }

    @Test
    fun largeAreaSplitAcrossThreadsMatchesTheReference() {
        for (planes in listOf(randomPlanar(640, 480, 16, 1), randomSemiPlanar(640, 480, 32, 2))) {
            assertArrayEquals(reference(planes, 0, 0, 640, 480), YuvConverter.toArgb(planes, 0, 0, 640, 480))
        }
    }

    @Test
    fun oddOffsetsAndSizesMatchTheReference() {
        val planes = randomSemiPlanar(512, 400, 16, 3)
        // Нечётные левый край и верхняя строка, нечётные ширина и высота — цветность делится на строки и столбцы по-разному.
        assertArrayEquals(reference(planes, 33, 17, 501, 391), YuvConverter.toArgb(planes, 33, 17, 501, 391))
        assertArrayEquals(reference(planes, 1, 1, 6, 4), YuvConverter.toArgb(planes, 1, 1, 6, 4))
    }

    @Test
    fun writingIntoAWiderBufferKeepsNeighbours() {
        val planes = randomPlanar(640, 480, 0, 4)
        val dst = IntArray(700 * 500) { 0x12345678 }
        YuvConverter.toArgbInto(planes, 10, 20, 410, 320, dst, 5 * 700 + 7, 700)
        val expected = reference(planes, 10, 20, 410, 320)
        for (row in 0 until 300) for (col in 0 until 400) {
            org.junit.Assert.assertEquals(expected[row * 400 + col], dst[(5 + row) * 700 + 7 + col])
        }
        org.junit.Assert.assertEquals(0x12345678, dst[5 * 700 + 6]) // слева от области не тронуто
        org.junit.Assert.assertEquals(0x12345678, dst[5 * 700 + 7 + 400])
    }
}
