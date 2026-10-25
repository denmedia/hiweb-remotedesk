package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchscreenGesturesTest {
    private val log = ArrayList<String>()
    private var now = 0L
    private val g = TouchscreenGestures(object : GestureOutput {
        override fun wheel(vertical: Boolean, units: Int) { log += "W$units" }
        override fun key(code: Int, extended: Boolean, down: Boolean) {
            log += "${if (down) "+" else "-"}${code.toString(16)}${if (extended) "e" else ""}"
        }
    }, density = 1f, clock = { now })

    private fun gesture(fingers: Int, dx: Float = 0f, dy: Float = 0f, spread0: Float = 100f, spread1: Float = spread0): Boolean {
        g.begin(fingers, 500f, 500f, spread0)
        g.update(fingers, 500f + dx, 500f + dy, spread1)
        return g.end()
    }

    @Test
    fun three_finger_swipe_left_is_undo() {
        assertTrue(gesture(3, dx = -150f))
        assertEquals(listOf("+1d", "+2c", "-2c", "-1d"), log)
    }

    @Test
    fun three_finger_swipe_right_is_redo_ctrl_y_on_windows() {
        gesture(3, dx = 150f)
        assertEquals(listOf("+1d", "+15", "-15", "-1d"), log)
    }

    @Test
    fun three_finger_swipe_right_is_redo_ctrl_shift_z_on_gnome() {
        g.desktop = DesktopProfile.GNOME
        gesture(3, dx = 150f)
        assertEquals(listOf("+1d", "+2a", "+2c", "-2c", "-2a", "-1d"), log)
    }

    @Test
    fun three_finger_pinch_in_copies_and_out_pastes() {
        gesture(3, spread0 = 150f, spread1 = 60f)
        assertEquals(listOf("+1d", "+2e", "-2e", "-1d"), log)
        log.clear()
        gesture(3, spread0 = 60f, spread1 = 150f)
        assertEquals(listOf("+1d", "+2f", "-2f", "-1d"), log)
    }

    @Test
    fun second_pinch_in_soon_after_cuts() {
        gesture(3, spread0 = 150f, spread1 = 60f)
        log.clear()
        now = 800
        gesture(3, spread0 = 150f, spread1 = 60f)
        assertEquals(listOf("+1d", "+2d", "-2d", "-1d"), log)
    }

    @Test
    fun late_second_pinch_is_copy_again() {
        gesture(3, spread0 = 150f, spread1 = 60f)
        log.clear()
        now = 5000
        gesture(3, spread0 = 150f, spread1 = 60f)
        assertEquals(listOf("+1d", "+2e", "-2e", "-1d"), log)
    }

    @Test
    fun four_finger_swipes_switch_apps() {
        gesture(4, dx = -150f)
        assertEquals(listOf("+38", "+f", "-f", "-38"), log)
        log.clear()
        gesture(5, dx = 150f)
        assertEquals(listOf("+38", "+2a", "+f", "-f", "-2a", "-38"), log)
    }

    @Test
    fun four_finger_swipe_up_goes_home() {
        gesture(4, dy = -200f)
        assertEquals(listOf("+5be", "+20", "-20", "-5be"), log)
        log.clear()
        g.desktop = DesktopProfile.GNOME
        gesture(4, dy = -200f)
        assertEquals(listOf("+5be", "-5be"), log)
    }

    @Test
    fun five_finger_pinch_in_opens_app_switcher() {
        gesture(5, spread0 = 200f, spread1 = 80f)
        assertEquals(listOf("+5be", "+f", "-f", "-5be"), log)
    }

    @Test
    fun short_movement_does_nothing() {
        assertFalse(gesture(3, dx = 20f, dy = 10f))
        assertTrue(log.isEmpty())
    }

    @Test
    fun fourth_finger_arriving_late_counts_as_four() {
        g.begin(3, 500f, 500f, 100f)
        g.update(4, 500f, 500f, 100f)
        g.update(4, 350f, 500f, 100f)
        g.end()
        assertEquals(listOf("+38", "+f", "-f", "-38"), log)
    }

    @Test
    fun cancel_discards_gesture() {
        g.begin(3, 500f, 500f, 100f)
        g.update(3, 300f, 500f, 100f)
        g.cancel()
        assertFalse(g.end())
        assertTrue(log.isEmpty())
    }
}
