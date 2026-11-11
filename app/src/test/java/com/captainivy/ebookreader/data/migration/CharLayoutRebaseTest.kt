package com.captainivy.ebookreader.data.migration

import org.junit.Assert.assertEquals
import org.junit.Test

class CharLayoutRebaseTest {

    @Test
    fun chapterForOffset_mapsIntoOwningChapter() {
        val starts = intArrayOf(0, 100, 250)
        val counts = intArrayOf(100, 150, 50)

        assertEquals(0, CharLayoutRebase.chapterForOffset(0, starts, counts))
        assertEquals(0, CharLayoutRebase.chapterForOffset(99, starts, counts))
        assertEquals(1, CharLayoutRebase.chapterForOffset(100, starts, counts))
        assertEquals(1, CharLayoutRebase.chapterForOffset(249, starts, counts))
        assertEquals(2, CharLayoutRebase.chapterForOffset(250, starts, counts))
    }

    @Test
    fun chapterForOffset_beyondEnd_fallsBackToLastNonEmptyChapter() {
        val starts = intArrayOf(0, 100, 200)
        val counts = intArrayOf(100, 100, 0)

        assertEquals(1, CharLayoutRebase.chapterForOffset(999, starts, counts))
    }

    @Test
    fun rebase_keepsInChapterOffset() {
        // 第 2 章旧起点 100，新起点 80；章内偏移 30 保持不变
        val result = CharLayoutRebase.rebase(
            currentChapter = 1,
            oldOffset = 130,
            oldMax = 130,
            oldStarts = intArrayOf(0, 100, 250),
            oldCounts = intArrayOf(100, 150, 50),
            newStarts = intArrayOf(0, 80, 210),
            newCounts = intArrayOf(80, 130, 40),
            newTotal = 250
        )

        assertEquals(110, result.current)
        assertEquals(110, result.max)
    }

    @Test
    fun rebase_maxInLaterChapterIsRebasedIndependently() {
        val result = CharLayoutRebase.rebase(
            currentChapter = 0,
            oldOffset = 10,
            oldMax = 260,
            oldStarts = intArrayOf(0, 100, 250),
            oldCounts = intArrayOf(100, 150, 50),
            newStarts = intArrayOf(0, 80, 210),
            newCounts = intArrayOf(80, 130, 40),
            newTotal = 250
        )

        assertEquals(10, result.current)
        // 第 3 章：newStart 210 + (260 - 250) = 220
        assertEquals(220, result.max)
    }

    @Test
    fun rebase_clampsOutOfRangeOffsets() {
        val result = CharLayoutRebase.rebase(
            currentChapter = 1,
            oldOffset = 500,
            oldMax = -20,
            oldStarts = intArrayOf(0, 100),
            oldCounts = intArrayOf(100, 50),
            newStarts = intArrayOf(0, 90),
            newCounts = intArrayOf(90, 40),
            newTotal = 130
        )

        assertEquals(130, result.current)
        assertEquals(130, result.max)
    }
}
