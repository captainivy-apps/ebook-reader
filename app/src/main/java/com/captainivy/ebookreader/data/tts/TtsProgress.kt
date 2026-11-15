package com.captainivy.ebookreader.data.tts

/** 根据播放进度估算当前朗读到的字符偏移 */
object TtsProgress {

    /**
     * 将 [fraction]（0..1）映射到 [start, end) 区间内的字符偏移。
     * 上限取 end - 1，避免进度到 1.0 时跳到下一段/下一页。
     */
    fun estimateOffset(start: Int, end: Int, fraction: Float): Int {
        if (end <= start) return start
        val f = fraction.coerceIn(0f, 1f)
        val offset = start + ((end - start) * f).toInt()
        return offset.coerceIn(start, end - 1)
    }
}
