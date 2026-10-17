package com.captainivy.ebookreader.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun today(): String = dayFormat.format(Date())

    fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分钟"
            else -> "${totalSec}秒"
        }
    }
}
