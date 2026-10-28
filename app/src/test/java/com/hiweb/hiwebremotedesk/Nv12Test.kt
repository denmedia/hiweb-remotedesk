package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Nv12Test {
    // Кадр 4×2: Y = 0..7, одна строка UV из двух пар (10,11) и (20,21).
    private val frame = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 20, 21)

    @Test
    fun zero_degrees_returns_same_frame() {
        assertSame(frame, Nv12.rotate(frame, 4, 2, 0))
    }

    @Test
    fun rotate_180_reverses_pixels_and_uv_pairs() {
        val out = Nv12.rotate(frame, 4, 2, 180)
        assertArrayEquals(byteArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 20, 21, 10, 11), out)
    }

    @Test
    fun rotate_90_clockwise_makes_portrait_frame() {
        // Исходные строки: 0123 / 4567. По часовой стрелке получается 2×4: 40 / 51 / 62 / 73.
        val out = Nv12.rotate(frame, 4, 2, 90)
        assertArrayEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21), out)
    }

    @Test
    fun rotate_270_clockwise_makes_portrait_frame() {
        // Против часовой: 3 7 / 2 6 / 1 5 / 0 4; UV-пары идут в порядке (20,21), (10,11).
        val out = Nv12.rotate(frame, 4, 2, 270)
        assertArrayEquals(byteArrayOf(3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11), out)
    }

    @Test
    fun four_quarter_turns_restore_the_frame() {
        var out = frame
        var w = 4
        var h = 2
        repeat(4) {
            out = Nv12.rotate(out, w, h, 90)
            val t = w; w = h; h = t
        }
        assertArrayEquals(frame, out)
    }
}
