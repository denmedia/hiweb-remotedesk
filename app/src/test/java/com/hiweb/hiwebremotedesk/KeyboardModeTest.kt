package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardModeTest {
    @Test
    fun cyclesThroughAllThreeModes() {
        assertEquals(KeyboardMode.HIDE, KeyboardMode.SHOW.next())
        assertEquals(KeyboardMode.AUTO, KeyboardMode.HIDE.next())
        assertEquals(KeyboardMode.SHOW, KeyboardMode.AUTO.next())
    }

    @Test
    fun savedIdRoundTrips() {
        KeyboardMode.entries.forEach { assertEquals(it, KeyboardMode.parse(it.id)) }
    }

    @Test
    fun unknownOrMissingIdFallsBackToAuto() {
        assertEquals(KeyboardMode.AUTO, KeyboardMode.parse(null))
        assertEquals(KeyboardMode.AUTO, KeyboardMode.parse("что-то"))
    }
}
