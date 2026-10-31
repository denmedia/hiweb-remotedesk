package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.PenFrame
import com.hiweb.hiwebremotedesk.rdp.RdpeiFlags
import com.hiweb.hiwebremotedesk.rdp.TouchFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Нативный ввод: номера контактов, обязательный отпуск, отмена, наведение и касание пера. */
class NativeTouchEngineTest {
    private val touch = ArrayList<TouchFrame>()
    private val pen = ArrayList<PenFrame>()
    private val engine = NativeTouchEngine(object : NativeTouchOutput {
        override fun touch(frames: List<TouchFrame>) { touch += frames }
        override fun pen(frames: List<PenFrame>) { pen += frames }
    })

    private val down = RdpeiFlags.DOWN or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT
    private val update = RdpeiFlags.UPDATE or RdpeiFlags.INRANGE or RdpeiFlags.INCONTACT

    @Test
    fun tapIsDownThenUp() {
        engine.down(7, 10, 20, 100)
        engine.flush()
        engine.up(7, 10, 20, 150)
        engine.flush()
        assertEquals(2, touch.size)
        val d = touch[0].points.single()
        assertEquals(0, d.id)
        assertEquals(down, d.flags)
        assertEquals(10 to 20, d.x to d.y)
        assertEquals(RdpeiFlags.UP, touch[1].points.single().flags)
        assertEquals(0, engine.fingers)
    }

    @Test
    fun androidIdsMapToSmallContactNumbersAndAreReused() {
        engine.down(5, 1, 1, 1)
        engine.down(9, 2, 2, 2)
        engine.flush()
        assertEquals(listOf(0, 1), touch.map { it.points.single().id })
        engine.up(5, 1, 1, 3)
        engine.down(11, 3, 3, 4)
        engine.flush()
        // освободившийся номер 0 занимает новый палец
        assertEquals(0, touch.last().points.single().id)
    }

    @Test
    fun stylusAsTouchDoesNotCollideWithFingers() {
        // Перо в режиме «касание пальцем» идёт под номером Android + 1000 — рядом с пальцем 0 это два разных контакта.
        engine.down(0, 1, 1, 1)
        engine.down(1000, 2, 2, 2)
        engine.flush()
        assertEquals(listOf(0, 1), touch.map { it.points.single().id })
        engine.up(1000, 2, 2, 3)
        engine.flush()
        assertEquals(1, engine.fingers)
        assertEquals(1, touch.last().points.single().id)
    }

    @Test
    fun onlyMovedFingersAreSent() {
        engine.down(1, 10, 10, 1)
        engine.down(2, 50, 50, 2)
        engine.flush()
        touch.clear()
        engine.move(3, listOf(NativePointer(1, 10, 10), NativePointer(2, 55, 50)))
        engine.flush()
        val f = touch.single()
        assertEquals(1, f.points.size)
        assertEquals(1, f.points.single().id)
        assertEquals(update, f.points.single().flags)
        touch.clear()
        // ничего не сдвинулось — кадра нет
        engine.move(4, listOf(NativePointer(1, 10, 10), NativePointer(2, 55, 50)))
        engine.flush()
        assertTrue(touch.isEmpty())
    }

    @Test
    fun eleventhFingerIsIgnoredAndItsUpToo() {
        for (i in 0 until 11) engine.down(i, i, i, 1L + i)
        engine.flush()
        assertEquals(10, touch.size)
        assertFalse(engine.down(99, 0, 0, 50))
        touch.clear()
        engine.up(99, 0, 0, 60)
        engine.flush()
        assertTrue(touch.isEmpty())
    }

    @Test
    fun cancelAllReleasesEverything() {
        engine.down(1, 10, 10, 1)
        engine.down(2, 20, 20, 2)
        engine.flush()
        touch.clear()
        engine.cancelAll(5)
        val f = touch.single()
        assertEquals(listOf(0, 1), f.points.map { it.id })
        assertTrue(f.points.all { it.flags == (RdpeiFlags.UP or RdpeiFlags.CANCELED) })
        assertEquals(0, engine.fingers)
        // повторная отмена ничего не шлёт
        touch.clear()
        engine.cancelAll(6)
        assertTrue(touch.isEmpty())
    }

    @Test
    fun framesAreBatchedUntilFlush() {
        engine.down(1, 0, 0, 1)
        engine.move(2, listOf(NativePointer(1, 5, 5)))
        engine.move(3, listOf(NativePointer(1, 9, 9)))
        assertTrue(touch.isEmpty())
        engine.flush()
        assertEquals(3, touch.size)
    }

    @Test
    fun penHoverTouchLiftLeave() {
        engine.pen(1, 10, 10, false, 0f, false, false)   // вошло в зону
        engine.pen(2, 11, 10, false, 0f, false, false)   // наведение
        engine.pen(3, 12, 10, true, 0.5f, false, false)  // касание
        engine.pen(4, 12, 10, false, 0f, false, false)   // поднято, ещё над экраном
        engine.penLeave(5)
        engine.flush()
        assertEquals(
            listOf(
                RdpeiFlags.DOWN or RdpeiFlags.INRANGE,
                RdpeiFlags.UPDATE or RdpeiFlags.INRANGE,
                update,
                RdpeiFlags.UPDATE or RdpeiFlags.INRANGE,
                RdpeiFlags.UP,
            ),
            pen.map { it.point.flags },
        )
        assertEquals(512, pen[2].point.pressure)
        assertEquals(0, pen[1].point.pressure)
        assertFalse(engine.penActive)
    }

    @Test
    fun penTouchWithoutHoverStartsWithDownInContact() {
        engine.pen(1, 0, 0, true, 1f, false, false)
        engine.flush()
        assertEquals(down, pen.single().point.flags)
        assertEquals(1024, pen.single().point.pressure)
    }

    @Test
    fun penButtonAndEraserFlags() {
        engine.pen(1, 0, 0, true, 0.2f, true, false)
        engine.pen(2, 0, 0, true, 0.2f, false, true)
        engine.flush()
        assertEquals(RdpeiFlags.PEN_BARREL, pen[0].point.penFlags)
        assertEquals(RdpeiFlags.PEN_INVERTED or RdpeiFlags.PEN_ERASER, pen[1].point.penFlags)
    }

    @Test
    fun penTiltPassesThrough() {
        engine.pen(1, 0, 0, true, 0.5f, false, false, tiltX = 12, tiltY = -34)
        engine.flush()
        assertEquals(12 to -34, pen.single().point.tiltX to pen.single().point.tiltY)
    }

    @Test
    fun androidTiltBecomesAxisTilt() {
        // Перо стоит прямо: наклона нет.
        assertEquals(listOf(0, 0), PenTilt.fromAndroid(0f, 1.2f).toList())
        // 45° верхним концом к верху экрана: по Y минус, по X ноль.
        assertEquals(listOf(0, -45), PenTilt.fromAndroid(Math.PI.toFloat() / 4, 0f).toList())
        // 45° верхним концом вправо (π/2 по часовой): по X плюс.
        assertEquals(listOf(45, 0), PenTilt.fromAndroid(Math.PI.toFloat() / 4, Math.PI.toFloat() / 2).toList())
        // Почти лежащее перо не выходит за ±90.
        val flat = PenTilt.fromAndroid(Math.PI.toFloat() / 2, Math.PI.toFloat())
        assertEquals(0, flat[0])
        assertEquals(true, flat[1] in -90..90)
    }

    @Test
    fun penCancelEndsContact() {
        engine.pen(1, 3, 4, true, 0.3f, false, false)
        engine.penCancel(2)
        engine.penCancel(3)   // второй раз — ничего
        engine.flush()
        assertEquals(2, pen.size)
        assertEquals(RdpeiFlags.UP or RdpeiFlags.CANCELED, pen[1].point.flags)
        assertEquals(3 to 4, pen[1].point.x to pen[1].point.y)
    }
}
