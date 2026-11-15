package com.captainivy.ebookreader.data.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsProgressTest {

    @Test
    fun mapsFractionWithinSegment() {
        assertEquals(100, TtsProgress.estimateOffset(100, 200, 0f))
        assertEquals(150, TtsProgress.estimateOffset(100, 200, 0.5f))
        assertEquals(199, TtsProgress.estimateOffset(100, 200, 1f))
    }

    @Test
    fun clampsFractionAndHandlesEmptySegments() {
        assertEquals(100, TtsProgress.estimateOffset(100, 200, -1f))
        assertEquals(199, TtsProgress.estimateOffset(100, 200, 2f))
        assertEquals(100, TtsProgress.estimateOffset(100, 100, 0.5f))
    }
}
