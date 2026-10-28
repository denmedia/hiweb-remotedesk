package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.data.StrengthLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthLevelsTest {
    @Test
    fun default_is_one_hundred_percent_and_equals_one() {
        assertEquals(100, StrengthLevels.DEFAULT)
        assertEquals(1f, StrengthLevels.factor(StrengthLevels.DEFAULT), 1e-6f)
    }

    @Test
    fun extreme_values_are_half_and_double() {
        assertEquals(0.5f, StrengthLevels.factor(StrengthLevels.MIN), 1e-6f)
        assertEquals(2f, StrengthLevels.factor(StrengthLevels.MAX), 1e-6f)
        assertEquals(StrengthLevels.factor(StrengthLevels.MIN), StrengthLevels.factor(-10), 0f)
        assertEquals(StrengthLevels.factor(StrengthLevels.MAX), StrengthLevels.factor(999), 0f)
    }

    @Test
    fun snap_gives_multiples_of_ten_inside_the_range() {
        assertEquals(60, StrengthLevels.snap(59.46f))
        assertEquals(100, StrengthLevels.snap(104f))
        assertEquals(110, StrengthLevels.snap(105f))
        assertEquals(StrengthLevels.MIN, StrengthLevels.snap(10f))
        assertEquals(StrengthLevels.MAX, StrengthLevels.snap(300f))
        for (p in StrengthLevels.MIN..StrengthLevels.MAX) assertEquals(0, StrengthLevels.snap(p.toFloat()) % StrengthLevels.STEP)
    }

    @Test
    fun hundred_percent_is_the_owners_tuned_base() {
        assertEquals(0.84f, StrengthLevels.inertiaFactor(100), 0.001f)
        // Мышь: прежний уровень 2 из версии 3 (4 · 2^(-3/4) исходной единицы, на экране было 59%).
        assertEquals(4f * Math.pow(2.0, -0.75).toFloat(), StrengthLevels.mouseFactor(100), 1e-5f)
    }
}

class StrengthScaleMigrationTest {
    @Test
    fun the_tuned_mouse_level_becomes_one_hundred_percent() {
        assertEquals(100, StrengthLevels.mouseFromLevel(2, 3))
        assertEquals(80, StrengthLevels.mouseFromLevel(1, 3))
        assertEquals(170, StrengthLevels.mouseFromLevel(5, 3))
        assertEquals(StrengthLevels.MAX, StrengthLevels.mouseFromLevel(9, 3))
    }

    @Test
    fun mouse_speed_is_preserved_within_rounding_for_every_version() {
        // Фактическая скорость в исходных единицах: уровень · опорная величина версии.
        val bases = mapOf(1 to 1f, 2 to 2f, 3 to 4f)
        for ((version, base) in bases) {
            for (level in 1..9) {
                val before = base * Math.pow(2.0, (level - 5) / 4.0).toFloat()
                val percent = StrengthLevels.mouseFromLevel(level, version)
                if (percent == StrengthLevels.MIN || percent == StrengthLevels.MAX) continue // прижато к краю ползунка
                val after = StrengthLevels.mouseFactor(percent)
                assertTrue("v$version level $level: $before vs $after", Math.abs(after - before) / before <= 0.06f)
            }
        }
    }

    @Test
    fun inertia_and_scroll_levels_keep_their_values() {
        assertEquals(100, StrengthLevels.inertiaFromLevel(5, 2))
        assertEquals(100, StrengthLevels.inertiaFromLevel(4, 1))
        assertEquals(120, StrengthLevels.inertiaFromLevel(5, 1))
        assertEquals(100, StrengthLevels.percentFromLevel(5))
        assertEquals(StrengthLevels.MIN, StrengthLevels.percentFromLevel(1))
        assertEquals(StrengthLevels.MAX, StrengthLevels.percentFromLevel(9))
        assertEquals(80, StrengthLevels.percentFromLevel(4))
    }

    @Test
    fun migrated_values_stay_inside_the_slider_and_on_the_step() {
        for (version in 1..3) for (level in 1..9) {
            for (p in listOf(StrengthLevels.mouseFromLevel(level, version), StrengthLevels.inertiaFromLevel(level, version), StrengthLevels.percentFromLevel(level))) {
                assertTrue(p in StrengthLevels.MIN..StrengthLevels.MAX)
                assertEquals(0, p % StrengthLevels.STEP)
            }
        }
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
