package com.captainivy.ebookreader.ui.reader

/**
 * 翻页全刷节奏计数。
 *
 * 墨水屏在快刷波形下会累积残影，只能靠一次清刷波形（GC16/Regal）清除。
 * 因此策略为：平时翻页不闪，每 [interval] 次翻页执行一次全屏清刷。
 * [interval] <= 0 表示关闭自动全刷（仅保留手动全刷入口）。
 */
class RefreshCadence(interval: Int = DEFAULT_INTERVAL) {

    @Volatile
    var interval: Int = normalize(interval)
        set(value) {
            field = normalize(value)
            count = 0
        }

    private var count = 0

    /** 记录一次翻页，返回本次是否应触发全屏清刷 */
    fun onPageTurn(): Boolean {
        val n = interval
        if (n <= 0) return false
        count++
        if (count >= n) {
            count = 0
            return true
        }
        return false
    }

    /** 手动全刷后或修改设置时重置计数 */
    fun reset() {
        count = 0
    }

    companion object {
        const val DEFAULT_INTERVAL = 5
        const val OFF = 0

        /** 可选的全刷间隔档位（页） */
        val STEPS = intArrayOf(OFF, 1, 3, 5, 10)

        private fun normalize(v: Int): Int = when {
            v <= 0 -> OFF
            v in STEPS -> v
            else -> DEFAULT_INTERVAL
        }
    }
}
