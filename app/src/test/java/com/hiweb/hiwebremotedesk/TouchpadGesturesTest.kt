package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadGesturesTest {
    private val log = ArrayList<String>()
    private val gestures = TouchpadGestures(object : GestureOutput {
        override fun wheel(vertical: Boolean, units: Int) { log += "${if (vertical) "V" else "H"}$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) {
            log += "${if (down) "+" else "-"}${code.toString(16)}${if (extended) "e" else ""}"
        }
    })

    @Test
    fun fractional_scroll_accumulates_instead_of_being_dropped() {
        // Один щелчок = 120 единиц; мелкие приращения по 0.3 щелчка копятся.
        repeat(4) { gestures.scrollNotches(true, 0.3f) }
        val sum = log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() }
        assertEquals(144, sum) // 4 × 0.3 щелчка = 1.2 щелчка = 144 единицы
    }

    @Test
    fun scroll_total_is_conserved_across_small_steps() {
        repeat(100) { gestures.scrollNotches(true, 0.013f) }
        val sum = log.filter { it.startsWith("V") }.sumOf { it.drop(1).toInt() }
        // 100 × 0.013 щелчка = 1.3 щелчка = 156 единиц, остаток < 1 единицы
        assertTrue("sum=$sum", sum in 155..156)
    }

    @Test
    fun scroll_distance_maps_pixels_to_notches_and_axes() {
        // По вертикали знак инвертирован (SCROLL_Y_SIGN = -1): расстояние вниз даёт прокрутку колеса вниз.
        // Тачпад ослаблен вдвое (TOUCHPAD_BASE_GAIN = 0.5): щелчок жеста даёт полщелчка колеса.
        gestures.scrollDistance(0f, TouchpadGestures.PIXELS_PER_NOTCH_V)
        assertEquals(listOf("V-60"), log)
    }

    /** Горизонтальный свайп двумя пальцами: [dx] — суммарное расстояние в пикселях жеста, отрицательное — пальцы вправо. */
    private fun horizontalSwipe(dx: Float, dy: Float = 0f) {
        repeat(10) { gestures.scrollDistance(dx / 10, dy / 10, it * 8L) }
        gestures.scrollRelease(100)
    }

    @Test
    fun horizontal_swipe_right_goes_back_with_alt_left() {
        horizontalSwipe(-300f)
        assertEquals(listOf("+38", "+4be", "-4be", "-38"), log)
    }

    @Test
    fun horizontal_swipe_left_goes_forward_with_alt_right() {
        horizontalSwipe(300f)
        assertEquals(listOf("+38", "+4de", "-4de", "-38"), log)
    }

    @Test
    fun short_horizontal_swipe_does_nothing() {
        horizontalSwipe(-60f)
        assertTrue(log.isEmpty())
    }

    @Test
    fun horizontal_swipe_does_not_scroll_or_fling() {
        horizontalSwipe(-300f)
        assertTrue(log.none { it.startsWith("V") || it.startsWith("H") })
        assertTrue(!gestures.isFlinging)
    }

    @Test
    fun vertical_scroll_with_sideways_drift_is_not_navigation() {
        horizontalSwipe(-150f, TouchpadGestures.PIXELS_PER_NOTCH_V * -20f)
        assertTrue(log.none { it.startsWith("+") })
        assertTrue(log.any { it.startsWith("V") })
    }

    @Test
    fun axis_choice_resets_between_gestures() {
        horizontalSwipe(-300f)
        log.clear()
        gestures.scrollDistance(0f, TouchpadGestures.PIXELS_PER_NOTCH_V * 2)
        assertTrue(log.any { it.startsWith("V") })
    }

    @Test
    fun large_scroll_is_split_into_valid_wheel_chunks() {
        gestures.scrollNotches(true, 5f) // 600 единиц
        assertTrue(log.all { it.drop(1).toInt() in -255..255 })
        assertEquals(600, log.sumOf { it.drop(1).toInt() })
    }

    @Test
    fun scroll_end_discards_remainder() {
        gestures.scrollNotches(true, 0.004f)
        gestures.scrollEnd()
        gestures.scrollNotches(true, 0.004f)
        assertTrue(log.isEmpty())
    }

    @Test
    fun pinch_holds_ctrl_and_zooms_with_wheel() {
        gestures.pinchStart()
        gestures.pinch(1.25f) // ln ≈ 0.22 ≥ порога старта — один щелчок вверх
        gestures.pinch(0.99f)
        gestures.pinchEnd()
        assertEquals(listOf("+1d", "V120", "-1d"), log)
    }

    @Test
    fun small_pinch_jitter_does_nothing_and_never_presses_ctrl() {
        gestures.pinchStart()
        repeat(10) { gestures.pinch(1.02f); gestures.pinch(0.98f) }
        gestures.pinch(1.05f)
        gestures.pinchEnd()
        assertTrue(log.isEmpty())
    }

    @Test
    fun pinch_in_scrolls_wheel_down_and_releases_ctrl_on_cancel() {
        gestures.pinchStart()
        gestures.pinch(0.8f) // ln ≈ -0.22 → один щелчок вниз
        gestures.cancelAll()
        assertEquals(listOf("+1d", "V-120", "-1d"), log)
    }

    @Test
    fun three_finger_swipe_up_opens_task_view() {
        gestures.swipeStart(3)
        gestures.swipe(0f, 0.1f)
        gestures.swipe(0.01f, 0.1f)
        gestures.swipeEnd()
        assertEquals(listOf("+5be", "+f", "-f", "-5be"), log)
    }

    @Test
    fun three_finger_swipe_down_shows_desktop() {
        gestures.swipeStart(3)
        gestures.swipe(0f, -0.3f)
        gestures.swipeEnd()
        assertEquals(listOf("+5be", "+20", "-20", "-5be"), log)
    }

    @Test
    fun swipe_left_switches_to_next_virtual_desktop() {
        gestures.swipeStart(4)
        gestures.swipe(-0.25f, 0.02f)
        gestures.swipeEnd()
        // Ctrl+Win+Right
        assertEquals(listOf("+1d", "+5be", "+4de", "-4de", "-5be", "-1d"), log)
    }

    private fun gnomeSwipe(x: Float, y: Float) {
        gestures.desktop = DesktopProfile.GNOME
        gestures.swipeStart(3)
        gestures.swipe(x, y)
        gestures.swipeEnd()
    }

    @Test
    fun gnome_swipe_up_taps_super_for_overview() {
        gnomeSwipe(0f, 0.3f)
        assertEquals(listOf("+5be", "-5be"), log)
    }

    @Test
    fun gnome_swipe_down_opens_app_grid() {
        gnomeSwipe(0f, -0.3f)
        assertEquals(listOf("+5be", "+1e", "-1e", "-5be"), log)
    }

    @Test
    fun gnome_swipe_left_switches_workspace_with_ctrl_alt_arrow() {
        gnomeSwipe(-0.25f, 0f)
        assertEquals(listOf("+1d", "+38", "+4de", "-4de", "-38", "-1d"), log)
    }

    @Test
    fun gnome_swipe_right_goes_to_previous_workspace() {
        gnomeSwipe(0.25f, 0f)
        assertEquals(listOf("+1d", "+38", "+4be", "-4be", "-38", "-1d"), log)
    }

    @Test
    fun short_swipe_is_ignored() {
        gestures.swipeStart(3)
        gestures.swipe(0.05f, 0.05f)
        gestures.swipeEnd()
        assertTrue(log.isEmpty())
    }

    @Test
    fun touch_scroll_moves_both_axes_without_navigation() {
        // Вертикаль и горизонталь прокручиваются одновременно: два щелчка вверх и один влево.
        gestures.touchScroll(0, 2f, -1f)
        gestures.scrollRelease(1000)
        assertEquals(listOf("V240", "H-120"), log)
    }

    @Test
    fun touch_scroll_keeps_horizontal_drag_from_navigating() {
        repeat(10) { gestures.touchScroll(it * 8L, 0f, 1f) }
        gestures.scrollRelease(100)
        assertTrue(log.none { it.startsWith("+") })
    }
}
