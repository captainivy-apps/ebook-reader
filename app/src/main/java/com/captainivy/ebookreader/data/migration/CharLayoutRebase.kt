package com.captainivy.ebookreader.data.migration

/** 重算字符基准后，换算得到的当前/最大阅读偏移 */
data class RebasedOffsets(val current: Int, val max: Int)

/**
 * 字符基准换算：把旧基准（`body.text().length`）下的书籍进度，换算到新基准
 * （解析后 TextBlock 长度）下。
 *
 * 页内偏移 `pageOffset` 在两种基准下保持不变，因此只需按所属章节重定位：
 * `newOffset = newStart[chapter] + (oldOffset - oldStart[chapter])`。
 */
object CharLayoutRebase {

    /** 用旧基准的章节区间定位某个全局偏移所属章节；越界时回退到最后一个非空章 */
    fun chapterForOffset(offset: Int, starts: IntArray, counts: IntArray): Int {
        for (i in starts.indices) {
            if (counts[i] <= 0) continue
            if (offset >= starts[i] && offset < starts[i] + counts[i]) return i
        }
        for (i in starts.indices.reversed()) {
            if (counts[i] > 0) return i
        }
        return 0
    }

    fun rebase(
        currentChapter: Int,
        oldOffset: Int,
        oldMax: Int,
        oldStarts: IntArray,
        oldCounts: IntArray,
        newStarts: IntArray,
        newCounts: IntArray,
        newTotal: Int
    ): RebasedOffsets {
        if (newStarts.isEmpty()) return RebasedOffsets(0, 0)
        val c = currentChapter.coerceIn(0, newStarts.lastIndex)
        val current = (newStarts[c] + (oldOffset - oldStarts[c]))
            .coerceIn(newStarts[c], newStarts[c] + newCounts[c])

        val maxChapter = chapterForOffset(oldMax, oldStarts, oldCounts)
        val max = (newStarts[maxChapter] + (oldMax - oldStarts[maxChapter]))
            .coerceIn(0, newTotal)

        return RebasedOffsets(
            current = current.coerceIn(0, newTotal),
            max = max.coerceAtLeast(current).coerceIn(0, newTotal)
        )
    }
}
