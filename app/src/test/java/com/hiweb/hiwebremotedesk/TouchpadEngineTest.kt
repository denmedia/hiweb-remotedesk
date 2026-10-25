package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadEngineTest {
    private val log = ArrayList<String>()
    private var cursorX = 0f
    private var cursorY = 0f

    private val out = object : TouchpadOutput {
        override fun moveCursor(dx: Float, dy: Float) { cursorX += dx; cursorY += dy }
        override fun button(button: Int, down: Boolean) {
            log += "${listOf("L", "R", "M")[button]}${if (down) "+" else "-"}"
        }
        override fun wheel(vertical: Boolean, units: Int) { log += "${if (vertical) "V" else "H"}$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) {
            log += "${if (down) "+" else "-"}${code.toString(16)}${if (extended) "e" else ""}"
        }
    }
    private val engine = TouchpadEngine(out)
    private var t = 0L

    /** Кадр: пальцы в миллиметрах (оси совпадают с осями экрана при TouchpadAxes() по умолчанию). */
    private fun frame(vararg fingers: Pair<Float, Float>, buttons: Int = 0, dt: Long = 8) {
        t += dt
        val n = fingers.size
        val ids = IntArray(n) { it }
        val xs = FloatArray(n) { fingers[it].first * 15f }
        val ys = FloatArray(n) { fingers[it].second * 15f }
        engine.onFrame(t, ids, xs, ys, n, buttons)
    }

    private fun lift() = frame()

    @Test
    fun one_finger_moves_cursor_in_screen_direction() {
        frame(10f to 10f)
        for (i in 1..10) frame((10f + i) to 10f)
        assertTrue("x=$cursorX", cursorX > 50f)
        assertEquals(0f, cursorY, 0.001f)
        lift()
        assertTrue(log.isEmpty()) // длинное движение — не тап
    }

    @Test
    fun faster_movement_travels_farther_per_millimetre() {
        val slow = TouchpadEngine(object : TouchpadOutput by out { override fun moveCursor(dx: Float, dy: Float) { cursorX += dx } })
        slow.onFrame(0, intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f), 1, 0)
        slow.onFrame(100, intArrayOf(0), floatArrayOf(150f), floatArrayOf(0f), 1, 0) // 10 мм за 100 мс
        val slowDist = cursorX
        cursorX = 0f
        slow.onFrame(200, intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f), 0, 0)
        slow.onFrame(300, intArrayOf(0), floatArrayOf(0f), floatArrayOf(0f), 1, 0)
        slow.onFrame(310, intArrayOf(0), floatArrayOf(150f), floatArrayOf(0f), 1, 0) // 10 мм за 10 мс
        assertTrue("fast=$cursorX slow=$slowDist", cursorX > slowDist * 1.5f)
    }

    @Test
    fun axes_calibration_swaps_and_flips() {
        engine.axes = TouchpadAxes(swap = true, signH = 1, signV = -1)
        frame(0f to 0f)
        frame(0f to 10f) // сырой Y → горизонталь экрана
        assertTrue(cursorX > 0f)
        cursorX = 0f; cursorY = 0f
        frame(0f to 10f) // без изменений
        frame(10f to 10f) // сырой X → вертикаль экрана с обратным знаком
        assertTrue(cursorY < 0f)
    }

    @Test
    fun quick_tap_is_left_click() {
        frame(5f to 5f); frame(5.2f to 5.1f, dt = 40); lift()
        assertEquals(listOf("L+", "L-"), log)
    }

    @Test
    fun two_finger_tap_is_right_click_and_three_is_middle() {
        frame(5f to 5f); frame(5f to 5f, 15f to 5f, dt = 20); lift()
        assertEquals(listOf("R+", "R-"), log)
        log.clear()
        frame(5f to 5f, 10f to 5f, 15f to 5f); lift()
        assertEquals(listOf("M+", "M-"), log)
    }

    @Test
    fun long_touch_is_not_a_tap() {
        frame(5f to 5f); frame(5f to 5f, dt = 400); lift()
        assertTrue(log.isEmpty())
    }

    @Test
    fun physical_click_uses_finger_count_and_suppresses_tap() {
        frame(5f to 5f)
        frame(5f to 5f, buttons = 1)
        frame(5f to 5f, buttons = 0)
        lift()
        assertEquals(listOf("L+", "L-"), log)
        log.clear()
        frame(5f to 5f, 15f to 5f)
        frame(5f to 5f, 15f to 5f, buttons = 1)
        frame(5f to 5f, 15f to 5f, buttons = 0)
        lift()
        assertEquals(listOf("R+", "R-"), log)
    }

    @Test
    fun two_finger_scroll_follows_fingers_like_macos() {
        // Пальцы едут вниз по экрану: контент вниз, колесо вверх (положительные единицы)
        frame(5f to 5f, 15f to 5f)
        for (i in 1..12) frame(5f to (5f + i), 15f to (5f + i))
        lift()
        val v = log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() }
        assertTrue("v=$v", v > 0)
        assertTrue(log.none { it.startsWith("+") }) // без Ctrl — это не масштаб
    }

    @Test
    fun two_finger_scroll_up_gives_negative_wheel_and_left_gives_positive_horizontal() {
        frame(5f to 20f, 15f to 20f)
        for (i in 1..12) frame(5f to (20f - i), 15f to (20f - i))
        lift()
        assertTrue(log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() } < 0)
        log.clear()
        frame(30f to 5f, 40f to 5f)
        for (i in 1..24) frame((30f - i) to 5f, (40f - i) to 5f)
        lift()
        // Горизонтальный свайп двумя пальцами — навигация, а не горизонтальная прокрутка: пальцы влево — вперёд.
        assertTrue(log.none { it.startsWith("H") })
        assertEquals(listOf("+38", "+4de", "-4de", "-38"), log.filter { it.startsWith("+") || it.startsWith("-") })
    }

    @Test
    fun scroll_with_slightly_converging_fingers_stays_a_scroll() {
        // Пальцы идут вниз и чуть сближаются (20% от сдвига): это прокрутка, а не щипок
        frame(5f to 5f, 15f to 5f)
        for (i in 1..15) frame((5f + 0.1f * i) to (5f + i), (15f - 0.1f * i) to (5f + i))
        lift()
        assertTrue(log.none { it.startsWith("+") })
        assertTrue(log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() } > 0)
    }

    @Test
    fun scroll_continues_after_a_third_finger_flickers() {
        frame(5f to 30f, 15f to 30f)
        for (i in 1..8) frame(5f to (30f - i), 15f to (30f - i))
        frame(5f to 22f, 15f to 22f, 25f to 22f) // касание третьим пальцем
        // Снова два пальца; расстояние между ними «гуляет», но это всё ещё прокрутка вверх
        for (i in 1..12) frame((5f - 0.3f * i) to (22f - i), (15f + 0.3f * i) to (22f - i))
        lift()
        assertTrue(log.none { it.startsWith("+") })
        val v = log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() }
        assertTrue("v=$v", v < -150) // прокрутка продолжилась и после «моргания»
    }

    @Test
    fun finger_settling_at_touchdown_does_not_start_a_pinch() {
        // В первых кадрах расстояние меняется на 1.5 мм, затем пальцы идут параллельно вверх
        frame(5f to 20f, 15f to 20f)
        frame(5.75f to 20f, 14.25f to 20f)
        for (i in 1..15) frame(5.75f to (20f - i), 14.25f to (20f - i))
        lift()
        assertTrue(log.none { it.startsWith("+") })
        assertTrue(log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() } < 0)
    }

    @Test
    fun pinch_holds_ctrl_and_zooms_in_when_fingers_spread() {
        frame(20f to 10f, 30f to 10f)
        for (i in 1..10) frame((20f - i) to 10f, (30f + i) to 10f)
        lift()
        assertEquals("+1d", log.first())
        assertEquals("-1d", log.last())
        assertTrue(log.any { it.startsWith("V") && it.drop(1).toInt() > 0 })
    }

    @Test
    fun three_finger_swipe_up_opens_task_view_without_tablet_clicks() {
        frame(10f to 40f, 20f to 40f, 30f to 40f)
        for (i in 1..10) frame(10f to (40f - 3 * i), 20f to (40f - 3 * i), 30f to (40f - 3 * i))
        lift()
        assertEquals(listOf("+5be", "+f", "-f", "-5be"), log)
    }

    @Test
    fun four_finger_swipe_left_switches_desktop_right() {
        val xs = listOf(40f, 50f, 60f, 70f)
        frame(*xs.map { it to 20f }.toTypedArray())
        for (i in 1..10) frame(*xs.map { (it - 3 * i) to 20f }.toTypedArray())
        lift()
        assertEquals(listOf("+1d", "+5be", "+4de", "-4de", "-5be", "-1d"), log)
    }

    @Test
    fun lifting_fingers_one_by_one_after_swipe_does_not_move_cursor_or_click() {
        frame(10f to 40f, 20f to 40f, 30f to 40f)
        for (i in 1..10) frame(10f to (40f - 3 * i), 20f to (40f - 3 * i), 30f to (40f - 3 * i))
        frame(10f to 10f, 20f to 10f)
        frame(10f to 10f)
        frame(10f to 5f)
        lift()
        assertEquals(0f, cursorX, 0.001f)
        assertEquals(0f, cursorY, 0.001f)
        assertEquals(listOf("+5be", "+f", "-f", "-5be"), log)
    }

    @Test
    fun calibration_detects_swapped_axes() {
        val cal = TouchpadCalibrator()
        // Шаг 1: «вправо» дало движение по сырой оси Y в положительную сторону
        var done = false
        for (i in 1..30) done = cal.onDelta(0f, 15f) || done
        assertTrue(done)
        assertEquals(TouchpadCalibrator.Step.DOWN, cal.step)
        cal.onLift()
        // Шаг 2: «вниз» дало движение по сырой оси X в отрицательную сторону
        done = false
        for (i in 1..30) done = cal.onDelta(-15f, 0f) || done
        assertTrue(done)
        val axes = cal.result
        assertNotNull(axes)
        assertEquals(TouchpadAxes(swap = true, signH = 1, signV = -1), axes)
        // Проверка на вектор: вправо по экрану = +Y сырой, вниз по экрану = -X сырой
        assertEquals(10f, axes!!.screenDx(0f, 10f), 0.001f)
        assertEquals(10f, axes.screenDy(-10f, 0f), 0.001f)
    }

    @Test
    fun calibration_rejects_second_swipe_on_the_same_axis() {
        val cal = TouchpadCalibrator()
        repeat(30) { cal.onDelta(15f, 0f) }
        cal.onLift()
        repeat(30) { cal.onDelta(-15f, 0f) } // та же ось X — не принимается
        assertEquals(TouchpadCalibrator.Step.DOWN, cal.step)
        assertEquals(null, cal.result)
        cal.onLift()
        repeat(30) { cal.onDelta(0f, 15f) }
        assertEquals(TouchpadCalibrator.Step.DONE, cal.step)
        assertEquals(TouchpadAxes(swap = false, signH = 1, signV = 1), cal.result)
    }

    @Test
    fun physical_click_held_by_one_finger_second_finger_drags_cursor() {
        frame(10f to 10f)
        frame(10f to 10f, buttons = 1)
        frame(10f to 10f, buttons = 1)
        frame(10f to 10f, 30f to 10f, buttons = 1)
        for (i in 1..10) frame(10f to 10f, (30f + i) to 10f, buttons = 1)
        assertTrue("x=$cursorX", cursorX > 50f)
        assertTrue(log.none { it.startsWith("V") || it.startsWith("H") })
        lift()
        assertEquals(listOf("L+", "L-"), log)
    }

    @Test
    fun resting_finger_then_second_finger_moves_holds_left_button_and_drags() {
        frame(10f to 10f)
        repeat(50) { frame(10f to 10f, dt = 10) } // палец лежит ~0.5 с
        frame(10f to 10f, 30f to 10f)
        assertTrue(log.isEmpty()) // просто касание вторым пальцем кнопку не нажимает
        for (i in 1..10) frame(10f to 10f, (30f + i) to 10f)
        assertEquals(listOf("L+"), log)
        assertTrue("x=$cursorX", cursorX > 50f)
        lift()
        assertEquals(listOf("L+", "L-"), log)
    }

    @Test
    fun drag_continues_after_second_finger_is_lifted_and_replaced() {
        frame(10f to 10f)
        repeat(50) { frame(10f to 10f, dt = 10) }
        frame(10f to 10f, 30f to 10f)
        for (i in 1..6) frame(10f to 10f, (30f + i) to 10f)
        frame(10f to 10f) // второй палец поднят
        val before = cursorX
        frame(10f to 10f, 30f to 20f)
        for (i in 1..6) frame(10f to 10f, (30f + i) to 20f)
        assertTrue(cursorX > before)
        assertEquals(listOf("L+"), log) // кнопка не отпускалась
        lift()
        assertEquals(listOf("L+", "L-"), log)
    }

    @Test
    fun two_fingers_landing_together_still_scroll_not_drag() {
        frame(5f to 20f, 15f to 20f)
        for (i in 1..12) frame(5f to (20f - i), 15f to (20f - i))
        lift()
        assertTrue(log.none { it == "L+" })
        assertTrue(log.any { it.startsWith("V") })
    }
}
