package com.captainivy.ebookreader.ui.reader

/**
 * 记录一本书的历史最大阅读位置，并累计“新内容”字数。
 *
 * 只有向前越过历史最大位置时才计入字数；回读、后退不会重复计数。
 * 目录跳转、排版重排等非翻页动作只抬高基线，不计入字数。
 */
class ReadingProgress(initialMaxOffset: Int) {

    var maxOffset: Int = initialMaxOffset.coerceAtLeast(0)
        private set

    var pendingChars: Int = 0
        private set

    /**
     * @param offset 当前阅读位置的全局字符偏移
     * @param countNew 是否把越过最大位置的部分计为本次新阅读字数
     */
    fun onPosition(offset: Int, countNew: Boolean) {
        if (offset <= maxOffset) return
        if (countNew) pendingChars += offset - maxOffset
        maxOffset = offset
    }

    /** 取出本次新增字数并清零 */
    fun takeChars(): Int {
        val chars = pendingChars
        pendingChars = 0
        return chars
    }
}
