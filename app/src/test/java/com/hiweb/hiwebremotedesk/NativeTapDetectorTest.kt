package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Щелчок в нативном режиме: агенту сообщается только короткое касание без сдвига. */
class NativeTapDetectorTest {
    private val tap = NativeTapDetector(500)

    @Test
    fun shortTouchInPlaceIsTap() {
        tap.down(100f, 100f, 1_000)
        tap.move(103f, 102f, 10f)
        assertTrue(tap.up(1_200))
    }

    @Test
    fun movedTouchIsNotTap() {
        tap.down(100f, 100f, 1_000)
        tap.move(130f, 100f, 10f)
        tap.move(100f, 100f, 10f) // вернулся на место — всё равно прокрутка
        assertFalse(tap.up(1_100))
    }

    @Test
    fun longPressIsNotTap() {
        tap.down(100f, 100f, 1_000)
        assertFalse(tap.up(1_500))
    }

    @Test
    fun cancelledOrSecondFingerIsNotTap() {
        tap.down(100f, 100f, 1_000)
        tap.cancel()
        assertFalse(tap.up(1_100))
    }

    @Test
    fun upWithoutDownIsNotTap() {
        assertFalse(tap.up(1_000))
        tap.down(0f, 0f, 2_000)
        assertTrue(tap.up(2_050))
        assertFalse(tap.up(2_060)) // повторный отпуск того же жеста
    }
}
