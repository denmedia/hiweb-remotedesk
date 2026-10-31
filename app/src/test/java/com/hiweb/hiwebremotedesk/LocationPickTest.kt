package com.hiweb.hiwebremotedesk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Выбор лучшего определения местоположения среди GPS, сетевого и fused датчиков. */
class LocationPickTest {
    private fun s(timeSec: Long, accuracy: Float, provider: String) = LocationSample(timeSec * 1000, accuracy, provider)

    @Test
    fun firstFixAlwaysWins() {
        assertTrue(LocationPick.better(null, s(0, 5000f, "network")))
    }

    @Test
    fun moreAccurateWins() {
        assertTrue(LocationPick.better(s(0, 800f, "network"), s(1, 8f, "gps")))
    }

    @Test
    fun coarserAndOtherProviderLoses() {
        // GPS точен и свеж: грубый сетевой отсчёт его не вытесняет.
        assertFalse(LocationPick.better(s(0, 8f, "gps"), s(5, 600f, "network")))
    }

    @Test
    fun slightlyWorseFromSameProviderWins() {
        assertTrue(LocationPick.better(s(0, 20f, "network"), s(5, 60f, "network")))
        assertFalse(LocationPick.better(s(0, 20f, "network"), s(5, 400f, "network")))
    }

    @Test
    fun sameAccuracyNewerWins() {
        assertTrue(LocationPick.better(s(0, 30f, "gps"), s(5, 30f, "fused")))
    }

    @Test
    fun staleCurrentIsReplacedByAnything() {
        // GPS потерял спутники (вошли в здание): через две минуты годится и грубый сетевой.
        assertTrue(LocationPick.better(s(0, 5f, "gps"), s(200, 900f, "network")))
    }

    @Test
    fun oldNewcomerLoses() {
        assertFalse(LocationPick.better(s(500, 100f, "network"), s(0, 5f, "gps")))
    }
}
