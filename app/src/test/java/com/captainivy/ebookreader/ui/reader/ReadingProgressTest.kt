package com.captainivy.ebookreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {

    @Test
    fun forward_countsNewChars() {
        val progress = ReadingProgress(100)
        progress.onPosition(150, countNew = true)
        assertEquals(150, progress.maxOffset)
        assertEquals(50, progress.takeChars())
    }

    @Test
    fun backward_doesNotCount() {
        val progress = ReadingProgress(100)
        progress.onPosition(80, countNew = true)
        assertEquals(100, progress.maxOffset)
        assertEquals(0, progress.takeChars())
    }

    @Test
    fun reread_doesNotCountAgain() {
        val progress = ReadingProgress(100)
        progress.onPosition(150, countNew = true)
        assertEquals(50, progress.takeChars())

        progress.onPosition(120, countNew = true)
        progress.onPosition(150, countNew = true)
        assertEquals(0, progress.takeChars())
    }

    @Test
    fun jump_raisesBaselineWithoutCounting() {
        val progress = ReadingProgress(100)
        progress.onPosition(500, countNew = false)
        assertEquals(500, progress.maxOffset)
        assertEquals(0, progress.takeChars())
    }

    @Test
    fun backwardJump_keepsBaseline() {
        val progress = ReadingProgress(100)
        progress.onPosition(500, countNew = false)
        progress.onPosition(200, countNew = false)
        assertEquals(500, progress.maxOffset)
    }

    @Test
    fun takeChars_resetsPending() {
        val progress = ReadingProgress(0)
        progress.onPosition(20, countNew = true)
        assertEquals(20, progress.takeChars())
        assertEquals(0, progress.takeChars())
    }

    @Test
    fun negativeInitialOffset_isClamped() {
        assertEquals(0, ReadingProgress(-5).maxOffset)
    }
}
