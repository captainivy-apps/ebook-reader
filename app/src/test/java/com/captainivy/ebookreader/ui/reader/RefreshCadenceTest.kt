package com.captainivy.ebookreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCadenceTest {

    @Test
    fun off_neverFires() {
        val cadence = RefreshCadence(RefreshCadence.OFF)
        repeat(50) { assertFalse(cadence.onPageTurn()) }
    }

    @Test
    fun negative_neverFires() {
        val cadence = RefreshCadence(-3)
        repeat(10) { assertFalse(cadence.onPageTurn()) }
    }

    @Test
    fun everyPage_firesEveryTurn() {
        val cadence = RefreshCadence(1)
        repeat(5) { assertTrue(cadence.onPageTurn()) }
    }

    @Test
    fun everyNth_firesOnExactNthTurn() {
        val cadence = RefreshCadence(5)
        val results = (1..15).map { cadence.onPageTurn() }
        val expected = (1..15).map { it % 5 == 0 }
        assertEquals(expected, results)
    }

    @Test
    fun reset_restartsCount() {
        val cadence = RefreshCadence(3)
        assertFalse(cadence.onPageTurn()) // 1
        assertFalse(cadence.onPageTurn()) // 2
        cadence.reset()
        assertFalse(cadence.onPageTurn()) // 1
        assertFalse(cadence.onPageTurn()) // 2
        assertTrue(cadence.onPageTurn())  // 3 -> fires
    }

    @Test
    fun changeInterval_resetsCounter() {
        val cadence = RefreshCadence(10)
        repeat(9) { assertFalse(cadence.onPageTurn()) }
        cadence.interval = 3
        assertFalse(cadence.onPageTurn()) // 1
        assertFalse(cadence.onPageTurn()) // 2
        assertTrue(cadence.onPageTurn())  // 3 -> fires
    }

    @Test
    fun invalidInterval_fallsBackToDefault() {
        val cadence = RefreshCadence(7) // 7 不在档位内 -> 默认 5
        assertEquals(RefreshCadence.DEFAULT_INTERVAL, cadence.interval)
        val results = (1..5).map { cadence.onPageTurn() }
        assertEquals(listOf(false, false, false, false, true), results)
    }
}
