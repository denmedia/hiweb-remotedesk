package com.hiweb.hiwebremotedesk

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRotationTest {
    @Test
    fun landscape_turns_left_and_portrait_returns_to_landscape() {
        // Поворот дисплея убывает на 1 — картинка идёт против часовой стрелки (влево).
        assertEquals(0, SessionRotation.target(rotation = 1, portrait = false))
        assertEquals(2, SessionRotation.target(rotation = 3, portrait = false))
        assertEquals(3, SessionRotation.target(rotation = 0, portrait = false))
        // Из книжной обратно: всегда в альбомную.
        assertEquals(1, SessionRotation.target(rotation = 0, portrait = true))
        assertEquals(3, SessionRotation.target(rotation = 2, portrait = true))
    }

    @Test
    fun button_changes_orientation_from_every_starting_rotation() {
        for (rotation in 0..3) for (portrait in listOf(true, false)) {
            val target = SessionRotation.target(rotation, portrait)
            // Чётность поворота и есть признак «окно той же ориентации, что и экран в естественном положении».
            assertFalse("rotation=$rotation", (target % 2 == rotation % 2))
        }
    }

    @Test
    fun delta_is_a_quarter_turn_in_either_direction() {
        assertEquals(1, SessionRotation.delta(0, 1))
        assertEquals(3, SessionRotation.delta(1, 0))
        assertEquals(3, SessionRotation.delta(0, 3))
        assertEquals(1, SessionRotation.delta(3, 0))
        assertEquals(2, SessionRotation.delta(1, 3))
        assertEquals(0, SessionRotation.delta(2, 2))
    }

    @Test
    fun natural_orientation_follows_rotation_parity() {
        assertTrue(SessionRotation.naturalPortrait(rotation = 0, portrait = true))
        assertTrue(SessionRotation.naturalPortrait(rotation = 1, portrait = false))
        assertFalse(SessionRotation.naturalPortrait(rotation = 0, portrait = false))
        assertFalse(SessionRotation.naturalPortrait(rotation = 3, portrait = true))
    }

    @Test
    fun orientation_constants_match_rotation_for_natural_portrait_device() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, SessionRotation.orientationFor(0, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, SessionRotation.orientationFor(1, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, SessionRotation.orientationFor(2, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SessionRotation.orientationFor(3, true))
    }

    @Test
    fun orientation_constants_match_rotation_for_natural_landscape_device() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, SessionRotation.orientationFor(0, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, SessionRotation.orientationFor(1, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, SessionRotation.orientationFor(2, false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, SessionRotation.orientationFor(3, false))
    }
}

class RotationClockTest {
    private val t0 = 1_000L

    @Test
    fun rotates_then_waits_blurred_until_windows_answers() {
        val clock = RotationClock(t0)
        assertEquals(0f, clock.progress(t0), 1e-6f)
        assertEquals(0f, clock.blur(t0), 1e-6f)
        assertEquals(1f, clock.progress(t0 + RotationClock.ROTATE_MS), 1e-6f)
        assertEquals(1f, clock.blur(t0 + RotationClock.BLUR_MS), 1e-6f)
        // Windows молчит: картинка на месте и размыта.
        assertEquals(1f, clock.alpha(t0 + 5_000), 1e-6f)
        assertFalse(clock.finished(t0 + 5_000))
        assertEquals(1f, clock.blur(t0 + 5_000), 1e-6f)
    }

    @Test
    fun fades_only_after_the_answer_and_the_end_of_the_turn() {
        val early = RotationClock(t0).apply { ready(t0 + 100) }
        // Ответ пришёл посреди поворота: исчезать начнёт после его конца.
        assertEquals(1f, early.alpha(t0 + RotationClock.ROTATE_MS), 1e-6f)
        assertTrue(early.alpha(t0 + RotationClock.ROTATE_MS + RotationClock.FADE_MS / 2) in 0.01f..0.99f)
        assertTrue(early.finished(t0 + RotationClock.ROTATE_MS + RotationClock.FADE_MS))

        val late = RotationClock(t0).apply { ready(t0 + 3_000) }
        assertEquals(1f, late.alpha(t0 + 2_999), 1e-6f)
        assertTrue(late.finished(t0 + 3_000 + RotationClock.FADE_MS))
    }

    @Test
    fun first_answer_wins() {
        val clock = RotationClock(t0)
        clock.ready(t0 + 1_000)
        clock.ready(t0 + 9_000)
        assertTrue(clock.finished(t0 + 1_000 + RotationClock.FADE_MS))
    }

    @Test
    fun gives_up_waiting_after_timeout() {
        val clock = RotationClock(t0)
        assertFalse(clock.isReady)
        assertEquals(1f, clock.alpha(t0 + RotationClock.TIMEOUT_MS), 1e-6f)
        assertTrue(clock.finished(t0 + RotationClock.TIMEOUT_MS + RotationClock.FADE_MS))
    }

    @Test
    fun restart_continues_from_current_blur() {
        val first = RotationClock(t0)
        val blurred = first.blur(t0 + 300)
        val next = RotationClock(t0 + 300, fromBlur = blurred)
        assertEquals(blurred, next.blur(t0 + 300), 1e-6f)
        assertTrue(next.blur(t0 + 600) > blurred)
    }
}
