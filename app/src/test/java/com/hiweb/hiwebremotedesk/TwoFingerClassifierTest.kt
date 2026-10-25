package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.TwoFingerClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwoFingerClassifierTest {
    private val c = TwoFingerClassifier(slop = 20f).apply { begin(100f, 100f, 400f, 300f) }

    @Test
    fun undecidedWhileWithinSlop() {
        assertNull(c.update(100f, 100f, 410f, 305f))
    }

    @Test
    fun secondFingerMovesAlone_isDrag() {
        assertEquals(Kind.DRAG, c.update(101f, 99f, 440f, 300f))
    }

    @Test
    fun bothFingersMove_isScroll() {
        assertEquals(Kind.SCROLL, c.update(100f, 130f, 400f, 332f))
    }

    @Test
    fun firstFingerMovesAlone_isScroll() {
        assertEquals(Kind.SCROLL, c.update(100f, 140f, 400f, 300f))
    }

    @Test
    fun slightlyAheadSecondFinger_waitsInsteadOfGuessingDrag() {
        // Второй палец прошёл порог, но первый почти догнал его: ждём, чтобы не принять прокрутку за перетаскивание.
        assertNull(c.update(100f, 112f, 400f, 322f))
        assertEquals(Kind.SCROLL, c.update(100f, 122f, 400f, 330f))
    }

    @Test
    fun jitterOfHeldFingerStillDrag() {
        assertEquals(Kind.DRAG, c.update(104f, 103f, 400f, 360f))
    }

    @Test
    fun fingersMovingApart_isPinch() {
        // Пальцы разошлись в разные стороны: центр почти не сдвинулся, расстояние выросло.
        assertEquals(Kind.PINCH, c.update(60f, 80f, 440f, 320f))
    }

    @Test
    fun fingersMovingTogether_isPinch() {
        assertEquals(Kind.PINCH, c.update(140f, 120f, 360f, 280f))
    }

    @Test
    fun parallelMove_isNotPinch() {
        assertEquals(Kind.SCROLL, c.update(100f, 160f, 400f, 360f))
    }
}
