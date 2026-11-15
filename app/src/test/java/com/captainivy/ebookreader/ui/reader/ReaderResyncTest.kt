package com.captainivy.ebookreader.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderResyncTest {

    @Test
    fun shouldAdopt_onlyWhenStoreIsNewer() {
        assertFalse(ReaderResync.shouldAdopt(1000L, 1000L))
        assertFalse(ReaderResync.shouldAdopt(900L, 1000L))
        assertTrue(ReaderResync.shouldAdopt(1001L, 1000L))
    }

    @Test
    fun shouldAdopt_nullLastReadAtIsNotNewer() {
        assertFalse(ReaderResync.shouldAdopt(null, 0L))
        assertFalse(ReaderResync.shouldAdopt(null, 5L))
    }

    @Test
    fun isSamePosition_comparesChapterAndOffset() {
        assertTrue(ReaderResync.isSamePosition(3, 120, 3, 120))
        assertFalse(ReaderResync.isSamePosition(3, 120, 3, 121))
        assertFalse(ReaderResync.isSamePosition(3, 120, 4, 120))
    }
}
