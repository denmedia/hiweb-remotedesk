package com.hiweb.hiwebremotedesk

import com.hiweb.hiwebremotedesk.rdp.CursorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusEngineTest {
    private val log = ArrayList<String>()
    private var kind = CursorKind.ARROW
    private val stylus = StylusEngine(object : StylusOutput {
        override fun move(x: Float, y: Float) { log += "m${x.toInt()},${y.toInt()}" }
        override fun press(x: Float, y: Float) { log += "P${x.toInt()},${y.toInt()}" }
        override fun release() { log += "R" }
        override fun tap(x: Float, y: Float, time: Long) { log += "T${x.toInt()},${y.toInt()}" }
        override fun scroll(time: Long, dx: Float, dy: Float) { log += "S${dx.toInt()},${dy.toInt()}" }
        override fun scrollRelease(time: Long) { log += "F" }
        override fun scrollEnd() { log += "E" }
    }, slop = 10f).also { it.cursorKind = { kind } }

    @Test
    fun tap_is_a_click_at_the_touch_point() {
        stylus.down(100f, 200f, buttonHeld = false, tapSuppressed = false)
        stylus.move(103f, 202f, 10)
        stylus.up(20)
        assertEquals(listOf("m100,200", "T100,200", "R"), log)
    }

    @Test
    fun tap_that_stopped_a_fling_is_not_a_click() {
        stylus.down(100f, 200f, buttonHeld = false, tapSuppressed = true)
        stylus.up(20)
        assertEquals(listOf("m100,200"), log)
    }

    @Test
    fun drag_over_arrow_scrolls_on_both_axes_and_flings_on_lift() {
        stylus.down(100f, 100f, false, false)
        stylus.move(130f, 140f, 10)
        stylus.move(150f, 150f, 20)
        stylus.up(30)
        assertEquals(listOf("m100,100", "m100,100", "S30,40", "S20,10", "F"), log)
    }

    @Test
    fun drag_over_text_selects_from_the_touch_point() {
        kind = CursorKind.TEXT
        stylus.down(100f, 100f, false, false)
        stylus.move(104f, 100f, 5)
        stylus.move(160f, 120f, 10)
        stylus.up(20)
        // Левая кнопка нажата в точке касания, а не там, где перо вышло за порог.
        assertEquals(listOf("m100,100", "P100,100", "m160,120", "R"), log)
    }

    @Test
    fun drag_over_resize_cursor_drags() {
        kind = CursorKind.DRAG
        stylus.down(10f, 10f, false, false)
        stylus.move(40f, 10f, 5)
        stylus.up(20)
        assertEquals(listOf("m10,10", "P10,10", "m40,10", "R"), log)
    }

    @Test
    fun long_press_before_moving_drags_even_over_arrow() {
        stylus.down(50f, 50f, false, false)
        assertTrue(stylus.longPress())
        stylus.move(90f, 50f, 600)
        stylus.up(700)
        assertEquals(listOf("m50,50", "P50,50", "m90,50", "R"), log)
    }

    @Test
    fun long_press_without_moving_is_still_a_click() {
        stylus.down(50f, 50f, false, false)
        assertTrue(stylus.longPress())
        stylus.up(700)
        assertEquals(listOf("m50,50", "T50,50", "R"), log)
    }

    @Test
    fun long_press_after_scrolling_started_does_nothing() {
        stylus.down(50f, 50f, false, false)
        stylus.move(50f, 90f, 10)
        assertFalse(stylus.longPress())
    }

    @Test
    fun pen_button_during_touch_only_moves_the_cursor() {
        stylus.down(10f, 10f, buttonHeld = true, tapSuppressed = false)
        stylus.move(80f, 90f, 10)
        stylus.up(20)
        assertEquals(listOf("m10,10", "m80,90"), log)
    }

    @Test
    fun pen_button_pressed_mid_scroll_stops_it() {
        stylus.down(10f, 10f, false, false)
        stylus.move(10f, 60f, 10)
        stylus.buttonChanged(true)
        stylus.move(20f, 70f, 20)
        stylus.up(30)
        assertEquals(listOf("m10,10", "m10,10", "S0,50", "E", "m20,70"), log)
    }

    @Test
    fun pen_button_pressed_mid_drag_releases_left_button() {
        kind = CursorKind.TEXT
        stylus.down(10f, 10f, false, false)
        stylus.move(60f, 10f, 10)
        stylus.buttonChanged(true)
        assertEquals(listOf("m10,10", "P10,10", "m60,10", "R"), log)
    }

    @Test
    fun after_button_release_the_rest_of_the_touch_does_nothing() {
        stylus.down(10f, 10f, buttonHeld = true, tapSuppressed = false)
        stylus.buttonChanged(false)
        stylus.move(80f, 90f, 10)
        stylus.up(20)
        assertEquals(listOf("m10,10"), log)
    }

    @Test
    fun cancel_releases_what_is_held() {
        kind = CursorKind.TEXT
        stylus.down(10f, 10f, false, false)
        stylus.move(60f, 10f, 10)
        stylus.cancel()
        assertFalse(stylus.touching)
        assertEquals("R", log.last())
    }
}
