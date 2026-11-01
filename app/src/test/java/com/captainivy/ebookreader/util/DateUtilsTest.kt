package com.captainivy.ebookreader.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateUtilsTest {

    @Test
    fun formatDuration_zero() {
        assertEquals("0 分钟", DateUtils.formatDuration(0))
    }

    @Test
    fun formatDuration_lessThanMinute() {
        assertEquals("不足 1 分钟", DateUtils.formatDuration(30_000))
    }

    @Test
    fun formatDuration_minutes() {
        assertEquals("5 分钟", DateUtils.formatDuration(5 * 60_000L))
    }

    @Test
    fun formatDuration_hoursAndMinutes() {
        assertEquals("1 小时 30 分钟", DateUtils.formatDuration(90 * 60_000L))
    }

    @Test
    fun formatDuration_wholeHours() {
        assertEquals("2 小时", DateUtils.formatDuration(2 * 60 * 60_000L))
    }

    @Test
    fun formatDuration_days() {
        assertEquals("2 天", DateUtils.formatDuration(48 * 60 * 60_000L))
    }

    @Test
    fun formatDuration_daysAndHours() {
        assertEquals("1 天 1 小时", DateUtils.formatDuration(25 * 60 * 60_000L))
    }

    @Test
    fun formatDuration_negativeClamped() {
        assertEquals("0 分钟", DateUtils.formatDuration(-1000))
    }

    @Test
    fun formatCount_usesGrouping() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("12,345", DateUtils.formatCount(12_345))
            assertEquals("0", DateUtils.formatCount(-1))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun formatDayLabel_todayAndYesterday() {
        assertEquals("今天", DateUtils.formatDayLabel("2026-11-01", today = "2026-11-01"))
        assertEquals("昨天", DateUtils.formatDayLabel("2026-10-31", today = "2026-11-01"))
    }

    @Test
    fun formatDayLabel_olderDay() {
        val label = DateUtils.formatDayLabel("2026-10-30", today = "2026-11-01")
        assertTrue(label.contains("10月30日"))
    }

    @Test
    fun lastNDays_returnsDescendingDays() {
        val days = DateUtils.lastNDays(7)
        assertEquals(7, days.size)
        assertEquals(DateUtils.today(), days.first())
        assertEquals(7, days.distinct().size)
    }
}
