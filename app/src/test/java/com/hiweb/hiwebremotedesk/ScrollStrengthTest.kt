package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.StrengthLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthLevelsTest {
    @Test
    fun middle_level_is_the_default_and_equals_one() {
        assertEquals(1f, StrengthLevels.factor(StrengthLevels.DEFAULT), 1e-6f)
        assertEquals(100, StrengthLevels.percent(StrengthLevels.DEFAULT))
    }

    @Test
    fun extreme_levels_are_half_and_double() {
        assertEquals(0.5f, StrengthLevels.factor(StrengthLevels.MIN), 1e-6f)
        assertEquals(2f, StrengthLevels.factor(StrengthLevels.MAX), 1e-6f)
        assertEquals(50, StrengthLevels.percent(StrengthLevels.MIN))
        assertEquals(200, StrengthLevels.percent(StrengthLevels.MAX))
    }

    @Test
    fun levels_grow_monotonically_and_out_of_range_is_clamped() {
        val all = (StrengthLevels.MIN..StrengthLevels.MAX).map { StrengthLevels.factor(it) }
        for (i in 1 until all.size) assertTrue(all[i] > all[i - 1])
        assertEquals(StrengthLevels.factor(StrengthLevels.MIN), StrengthLevels.factor(-10), 0f)
        assertEquals(StrengthLevels.factor(StrengthLevels.MAX), StrengthLevels.factor(99), 0f)
    }
}

class ScrollStrengthTest {
    private val log = ArrayList<String>()
    private val gestures = TouchpadGestures(object : GestureOutput {
        override fun wheel(vertical: Boolean, units: Int) { log += "${if (vertical) "V" else "H"}$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) = Unit
    })

    private fun units() = log.sumOf { it.drop(1).toInt() }

    private fun flingTotal(): Int {
        var t = 0L
        repeat(12) { t += 8; gestures.fingerScroll(t, 0.25f, 0f) }
        gestures.scrollRelease(t + 8)
        log.clear()
        var now = t + 8
        var guard = 0
        while (gestures.flingStep(now) && guard++ < 3000) now += 16
        return units()
    }

    @Test
    fun scroll_strength_scales_finger_scroll_proportionally() {
        gestures.fingerScroll(8, 1f, 0f)
        val base = units()
        log.clear()
        gestures.scrollStrength = 2f
        gestures.fingerScroll(16, 1f, 0f)
        assertEquals(base * 2, units())
        log.clear()
        gestures.scrollStrength = 0.5f
        gestures.fingerScroll(24, 1f, 0f)
        assertEquals(base / 2, units())
    }

    @Test
    fun touchpad_source_gain_halves_scroll_compared_with_touch_screen() {
        gestures.fingerScroll(8, 1f, 0f) // сенсорный экран: коэффициент 1
        val touch = units()
        log.clear()
        gestures.fingerScroll(16, 1f, 0f, TouchpadGestures.TOUCHPAD_BASE_GAIN)
        assertEquals(touch / 2, units())
    }

    @Test
    fun mouse_wheel_is_not_affected_by_scroll_strength() {
        gestures.scrollStrength = 2f
        gestures.scrollNotches(true, 1f)
        assertEquals(120, units())
    }

    @Test
    fun inertia_strength_changes_how_far_it_coasts() {
        val normal = flingTotal()
        gestures.cancelAll()
        gestures.inertiaStrength = 2f
        val strong = flingTotal()
        gestures.cancelAll()
        gestures.inertiaStrength = 0.5f
        val weak = flingTotal()
        assertTrue("normal=$normal strong=$strong", strong > normal * 1.8f && strong < normal * 2.2f)
        assertTrue("normal=$normal weak=$weak", weak > normal * 0.4f && weak < normal * 0.6f)
    }

    @Test
    fun inertia_strength_does_not_change_the_scroll_itself() {
        gestures.inertiaStrength = 2f
        gestures.fingerScroll(8, 1f, 0f)
        val boosted = units()
        log.clear()
        gestures.inertiaStrength = 0.5f
        gestures.fingerScroll(16, 1f, 0f)
        assertEquals(boosted, units())
    }

    @Test
    fun scroll_strength_scales_inertia_distance_but_not_its_start_threshold() {
        val normal = flingTotal()
        gestures.cancelAll()
        gestures.scrollStrength = 0.5f
        // Те же движения пальцев: инерция запускается и пропорционально короче
        val halved = flingTotal()
        assertTrue("normal=$normal halved=$halved", halved > normal * 0.4f && halved < normal * 0.6f)
    }

    @Test
    fun engine_exposes_the_same_strengths_and_applies_them() {
        val out = object : TouchpadOutput {
            val wheel = ArrayList<Int>()
            override fun moveCursor(dx: Float, dy: Float) = Unit
            override fun button(button: Int, down: Boolean) = Unit
            override fun wheel(vertical: Boolean, units: Int) { wheel += units }
            override fun key(code: Int, extended: Boolean, down: Boolean) = Unit
        }
        fun scrollTotal(strength: Float): Int {
            out.wheel.clear()
            val engine = TouchpadEngine(out)
            engine.scrollStrength = strength
            var t = 0L
            fun frame(vararg f: Pair<Float, Float>) {
                t += 8
                engine.onFrame(t, IntArray(f.size) { it }, FloatArray(f.size) { f[it].first * 15f }, FloatArray(f.size) { f[it].second * 15f }, f.size, 0)
            }
            frame(5f to 5f, 15f to 5f)
            for (i in 1..20) frame(5f to (5f + i), 15f to (5f + i))
            return out.wheel.sum()
        }
        val normal = scrollTotal(1f)
        val strong = scrollTotal(2f)
        assertTrue("normal=$normal", normal > 0)
        assertTrue("normal=$normal strong=$strong", strong in (normal * 19 / 10)..(normal * 21 / 10))
    }
}
