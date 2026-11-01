package com.captainivy.ebookreader.util

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DateUtils {

    private fun dayFormat(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun dayLabelFormat(): SimpleDateFormat =
        SimpleDateFormat("M月d日 EEE", Locale.getDefault())

    fun today(): String = dayFormat().format(Date())

    /** 阅读时长：不足 1 分钟 / X 分钟 / X 小时 Y 分钟 / X 天 Y 小时 */
    fun formatDuration(millis: Long): String {
        val totalSec = millis.coerceAtLeast(0) / 1000
        if (totalSec <= 0) return "0 分钟"
        if (totalSec < 60) return "不足 1 分钟"
        val totalMin = totalSec / 60
        val totalHour = totalMin / 60
        val day = totalHour / 24
        val hour = totalHour % 24
        val minute = totalMin % 60
        return when {
            day > 0 -> if (hour > 0) "$day 天 $hour 小时" else "$day 天"
            totalHour > 0 -> if (minute > 0) "$totalHour 小时 $minute 分钟" else "$totalHour 小时"
            else -> "$totalMin 分钟"
        }
    }

    /** 兼容旧调用 */
    fun formatTime(millis: Long): String = formatDuration(millis)

    /** 千分位数字 */
    fun formatCount(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.coerceAtLeast(0))

    /** 今天 / 昨天 / M月d日 周X */
    fun formatDayLabel(day: String, today: String = today()): String {
        if (day == today) return "今天"
        if (day == offsetDay(today, -1)) return "昨天"
        return try {
            val date = dayFormat().parse(day)
            if (date == null) day else dayLabelFormat().format(date)
        } catch (_: Exception) {
            day
        }
    }

    /** 最近 n 天，从今天开始倒序 */
    fun lastNDays(n: Int): List<String> {
        if (n <= 0) return emptyList()
        val today = today()
        return (0 until n).map { offsetDay(today, -it) }
    }

    private fun offsetDay(day: String, delta: Int): String {
        return try {
            val date = dayFormat().parse(day) ?: return day
            val cal = Calendar.getInstance().apply {
                time = date
                add(Calendar.DAY_OF_YEAR, delta)
            }
            dayFormat().format(cal.time)
        } catch (_: Exception) {
            day
        }
    }
}
