package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFlingTest {
    private var v = 0f
    private var h = 0f
    private val steps = ArrayList<Float>()
    private val fling = ScrollFling { vertical, n ->
        if (vertical) { v += n; steps += n } else h += n
    }

    /** Пальцы двигаются с постоянной скоростью (щелчков в секунду); возвращает момент последнего отсчёта. */
    private fun swipe(speed: Float, frames: Int = 12, frameMs: Long = 8, horizontal: Boolean = false): Long {
        var t = 0L
        repeat(frames) {
            t += frameMs
            val d = speed * frameMs / 1000f
            fling.onSample(t, if (horizontal) 0f else d, if (horizontal) d else 0f)
        }
        return t
    }

    private fun runToEnd(from: Long, frameMs: Long = 16): Long {
        var now = from
        var guard = 0
        while (fling.step(now) && guard++ < 2000) now += frameMs
        return now
    }

    @Test
    fun speed_decays_and_is_zero_when_stopped() {
        val t = swipe(30f)
        fling.release(t + 8)
        val start = fling.speed
        assertTrue(start > 20f)
        fling.step(t + 8 + 200)
        fling.step(t + 8 + 400)
        assertTrue(fling.speed < start / 2f)
        fling.stop()
        assertEquals(0f, fling.speed, 0f)
    }

    @Test
    fun fast_swipe_continues_and_covers_about_speed_times_tau() {
        val t = swipe(30f)
        fling.release(t + 8)
        assertTrue(fling.isActive)
        runToEnd(t + 8)
        // 30 щелчков/с × 0.5 с = 15 щелчков, минус «хвост» ниже порога остановки
        assertEquals(15f, v, 1f)
        assertFalse(fling.isActive)
    }

    @Test
    fun inertia_decays_monotonically() {
        val t = swipe(40f)
        fling.release(t + 8)
        runToEnd(t + 8)
        assertTrue(steps.size > 10)
        for (i in 1 until steps.size) assertTrue("шаг $i: ${steps[i]} > ${steps[i - 1]}", steps[i] <= steps[i - 1] + 1e-4f)
    }

    @Test
    fun distance_does_not_depend_on_frame_rate() {
        val t = swipe(30f)
        fling.release(t + 8)
        runToEnd(t + 8, frameMs = 4)
        val fine = v
        v = 0f
        val t2 = swipe(30f)
        fling.release(t2 + 8)
        runToEnd(t2 + 8, frameMs = 33)
        assertEquals(fine, v, 0.5f)
    }

    @Test
    fun negative_and_horizontal_directions_are_preserved() {
        var t = swipe(-30f)
        fling.release(t + 8)
        runToEnd(t + 8)
        assertTrue("v=$v", v < -10f)
        assertEquals(0f, h, 0f)
        v = 0f
        t = swipe(25f, horizontal = true)
        fling.release(t + 8)
        runToEnd(t + 8)
        assertTrue("h=$h", h > 8f)
        assertEquals(0f, v, 0f)
    }

    @Test
    fun slow_scrolling_has_no_inertia() {
        val t = swipe(3f)
        fling.release(t + 8)
        assertFalse(fling.isActive)
    }

    @Test
    fun fingers_that_stopped_before_lifting_have_no_inertia() {
        val t = swipe(40f)
        fling.release(t + 120) // пальцы лежали неподвижно 120 мс
        assertFalse(fling.isActive)
    }

    @Test
    fun zero_motion_frames_before_lift_cancel_inertia() {
        var t = swipe(40f, frames = 6)
        repeat(14) { t += 8; fling.onSample(t, 0f, 0f) } // 112 мс покоя
        fling.release(t + 8)
        assertFalse(fling.isActive)
    }

    @Test
    fun new_touch_stops_inertia() {
        val t = swipe(30f)
        fling.release(t + 8)
        assertTrue(fling.isActive)
        fling.onSample(t + 100, 0.1f, 0f)
        assertFalse(fling.isActive)
        assertFalse(fling.step(t + 116))
    }

    @Test
    fun speed_is_capped() {
        val t = swipe(500f)
        fling.release(t + 8)
        runToEnd(t + 8)
        assertTrue("v=$v", v <= 31f) // 60 щелчков/с × 0.5 с
    }
}

class TouchpadInertiaTest {
    private val log = ArrayList<String>()
    private val out = object : TouchpadOutput {
        override fun moveCursor(dx: Float, dy: Float) = Unit
        override fun button(button: Int, down: Boolean) { log += "B$button${if (down) "+" else "-"}" }
        override fun wheel(vertical: Boolean, units: Int) { log += "${if (vertical) "V" else "H"}$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) = Unit
    }
    private val engine = TouchpadEngine(out)
    private var t = 0L

    private fun frame(vararg fingers: Pair<Float, Float>, dt: Long = 8) {
        t += dt
        val n = fingers.size
        engine.onFrame(t, IntArray(n) { it }, FloatArray(n) { fingers[it].first * 15f }, FloatArray(n) { fingers[it].second * 15f }, n, 0)
    }

    private fun lift() = frame()

    private fun wheelSum(vertical: Boolean = true) =
        log.filter { it.startsWith(if (vertical) "V" else "H") }.sumOf { it.drop(1).toInt() }

    /** Прокрутка двумя пальцами вниз со скоростью [mmPerFrame] мм за кадр. */
    private fun scrollDown(mmPerFrame: Float, frames: Int = 20) {
        frame(5f to 5f, 15f to 5f)
        for (i in 1..frames) frame(5f to (5f + mmPerFrame * i), 15f to (5f + mmPerFrame * i))
    }

    private fun runFling(from: Long): Long {
        var now = from
        var guard = 0
        while (engine.tick(now) && guard++ < 2000) now += 16
        return now
    }

    @Test
    fun touchpad_scroll_continues_by_inertia_after_lifting() {
        scrollDown(1f) // 1 мм/кадр ≈ 31 щелчок/с
        val before = wheelSum()
        lift()
        assertTrue(engine.isFlinging)
        runFling(t)
        val inertia = wheelSum() - before
        // Тачпад ослаблен вдвое: 31 щелчок/с × 0.5 с × 0.5 ≈ 7.7 щелчка (≈920 единиц)
        assertTrue("inertia=$inertia", inertia in 800..1000)
        assertFalse(engine.isFlinging)
    }

    @Test
    fun upward_scroll_gives_inertia_in_the_same_direction() {
        frame(5f to 60f, 15f to 60f)
        for (i in 1..20) frame(5f to (60f - i), 15f to (60f - i))
        val before = wheelSum()
        lift()
        runFling(t)
        assertTrue(wheelSum() - before in -1000..-800)
    }

    @Test
    fun slow_touchpad_scroll_has_no_inertia() {
        scrollDown(0.1f, frames = 60)
        lift()
        assertFalse(engine.isFlinging)
    }

    @Test
    fun resting_fingers_before_lift_have_no_inertia() {
        scrollDown(1f)
        repeat(20) { frame(5f to 25f, 15f to 25f) } // 160 мс неподвижно
        lift()
        assertFalse(engine.isFlinging)
    }

    @Test
    fun touching_the_pad_stops_inertia() {
        scrollDown(1f)
        lift()
        assertTrue(engine.isFlinging)
        engine.tick(t + 16)
        frame(30f to 30f) // новое касание
        assertFalse(engine.isFlinging)
    }

    @Test
    fun inertia_starts_when_one_of_two_fingers_lifts_first() {
        scrollDown(1f)
        frame(5f to 25f) // поднялся один палец
        assertTrue(engine.isFlinging)
        lift()
        assertTrue(engine.isFlinging) // конец касания не обрывает инерцию
    }

    @Test
    fun pinch_has_no_inertia() {
        frame(20f to 10f, 30f to 10f)
        for (i in 1..20) frame((20f - i) to 10f, (30f + i) to 10f)
        lift()
        assertFalse(engine.isFlinging)
    }

    @Test
    fun cancel_stops_inertia() {
        scrollDown(1f)
        lift()
        assertTrue(engine.isFlinging)
        engine.cancel()
        assertFalse(engine.isFlinging)
    }
}

class GestureInertiaTest {
    private val log = ArrayList<String>()
    private val gestures = TouchpadGestures(object : GestureOutput {
        override fun wheel(vertical: Boolean, units: Int) { log += "${if (vertical) "V" else "H"}$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) = Unit
    })

    private fun fastScroll(): Long {
        var t = 0L
        repeat(12) { t += 8; gestures.fingerScroll(t, 0.25f, 0f) }
        return t
    }

    @Test
    fun finger_scroll_release_starts_decaying_inertia() {
        val t = fastScroll()
        gestures.scrollRelease(t + 8)
        assertTrue(gestures.isFlinging)
        log.clear()
        var now = t + 8
        var guard = 0
        while (gestures.flingStep(now) && guard++ < 2000) now += 16
        val sum = log.sumOf { it.drop(1).toInt() }
        assertTrue("sum=$sum", sum in 1700..1950) // ≈ 31 щелчок/с × 0.5 с
        assertFalse(gestures.isFlinging)
    }

    @Test
    fun mouse_wheel_has_no_inertia() {
        gestures.scrollNotches(true, 5f)
        gestures.scrollRelease(100) // отсчётов пальцев не было
        assertFalse(gestures.isFlinging)
    }

    @Test
    fun cancel_all_stops_inertia_but_reset_keeps_it() {
        var t = fastScroll()
        gestures.scrollRelease(t + 8)
        gestures.reset()
        assertTrue(gestures.isFlinging)
        gestures.cancelAll()
        assertFalse(gestures.isFlinging)
        t = fastScroll()
        gestures.scrollRelease(t + 8)
        gestures.stopFling()
        assertFalse(gestures.isFlinging)
    }
}
