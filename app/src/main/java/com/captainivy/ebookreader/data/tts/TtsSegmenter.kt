package com.captainivy.ebookreader.data.tts

/** 一章内可朗读的文本片段（偏移与 ChapterLayout 的字符基准一致） */
data class TtsSegment(
    val start: Int,
    val end: Int,
    val text: String
)

/**
 * 把章节文本块拆分为适合逐句合成播放的片段。
 *
 * 仅接受文本序列，不依赖 Android 类，便于单元测试。
 * 偏移按所有文本块长度累计，与 [com.captainivy.ebookreader.ui.reader.layout.ChapterLayout]
 * 的 blockOffsets 保持一致（图片等非文本块不占偏移）。
 */
object TtsSegmenter {

    /** 单句最大字符数，超过后在标点/空白处拆分，避免一次合成过久 */
    const val MAX_SEGMENT_CHARS = 80

    /** 句子终止符；连续出现时作为同一处边界处理 */
    private val TERMINALS = setOf(
        '。', '！', '？', '；', '!', '?', ';', '\n', '\r'
    )

    /** 句末可能紧跟的收尾符号，应归入同一句，避免出现单独朗读的引号/括号 */
    private val TRAILING_CLOSERS = setOf(
        '”', '’', '」', '』', '）', ')', '】', '》', '〉',
        '"', '\'', ']', '}'
    )

    /** 超长句硬拆分时优先选择的分句位置 */
    private val SOFT_BREAKS = setOf(
        '，', ',', '、', '：', ':', '—', '－', '-', ' ', '\t'
    )

    fun segment(texts: List<CharSequence>): List<TtsSegment> {
        val out = ArrayList<TtsSegment>()
        var base = 0
        for (text in texts) {
            val value = text.toString()
            segmentText(value, base, out)
            base += value.length
        }
        return out
    }

    private fun segmentText(text: String, base: Int, out: MutableList<TtsSegment>) {
        if (text.isEmpty()) return
        var chunkStart = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            var breakEnd = -1

            if (c in TERMINALS) {
                var j = i + 1
                var crossedLineBreak = c == '\n' || c == '\r'
                // 连续的终止符（如“？！”、“……”“\r\n”）属于同一边界
                while (j < text.length && (text[j] in TERMINALS || text[j] == '…')) {
                    if (text[j] == '\n' || text[j] == '\r') crossedLineBreak = true
                    j++
                }
                // 只有同一行内紧跟的收尾引号/括号才归入本句；
                // 换行后的引号可能是下一段对话的开引号，不能误吞。
                if (!crossedLineBreak) {
                    while (j < text.length && text[j] in TRAILING_CLOSERS) j++
                }
                breakEnd = j
            } else if (c == '…') {
                var j = i + 1
                while (j < text.length && text[j] == '…') j++
                while (j < text.length && text[j] in TRAILING_CLOSERS) j++
                breakEnd = j
            }

            if (breakEnd > i) {
                emit(text, base, chunkStart, breakEnd, out)
                chunkStart = breakEnd
                i = breakEnd
                continue
            }

            if (i - chunkStart >= MAX_SEGMENT_CHARS) {
                val split = softSplit(text, chunkStart)
                emit(text, base, chunkStart, split, out)
                chunkStart = split
                i = split
                continue
            }
            i++
        }
        emit(text, base, chunkStart, text.length, out)
    }

    /**
     * 在 [start, current) 内寻找尽量自然的分割点。
     * 优先回退到空白/逗号等软分隔处；找不到才在最大长度处硬拆。
     */
    private fun softSplit(text: String, start: Int): Int {
        val limit = (start + MAX_SEGMENT_CHARS).coerceAtMost(text.length)
        val lowerBound = start + MAX_SEGMENT_CHARS / 2
        for (pos in (limit - 1).coerceAtLeast(lowerBound) downTo lowerBound) {
            val c = text[pos]
            if (c.isWhitespace() || c in SOFT_BREAKS) {
                return adjustSplit(text, pos + 1, start)
            }
        }
        return adjustSplit(text, limit.coerceAtLeast(start + 1), start)
    }

    /** 避免把代理对（emoji 等）或组合字符从中间截断 */
    private fun adjustSplit(text: String, proposed: Int, start: Int): Int {
        var split = proposed.coerceIn(start + 1, text.length)
        if (split < text.length && split > start &&
            Character.isHighSurrogate(text[split - 1]) &&
            Character.isLowSurrogate(text[split])
        ) {
            split++
        }
        while (split < text.length &&
            Character.getType(text[split]) == Character.NON_SPACING_MARK.toInt()
        ) {
            split++
        }
        return split.coerceAtMost(text.length)
    }

    private fun emit(
        text: String,
        base: Int,
        rawStart: Int,
        rawEnd: Int,
        out: MutableList<TtsSegment>
    ) {
        var start = rawStart.coerceIn(0, text.length)
        var end = rawEnd.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return

        // 纯标点/引号片段不单独朗读，避免出现“顿一下但没有内容”的碎片
        var readable = false
        for (index in start until end) {
            if (isReadableChar(text[index])) {
                readable = true
                break
            }
        }
        if (!readable) return
        out.add(TtsSegment(base + start, base + end, text.substring(start, end)))
    }

    /** 字母、数字、汉字、emoji 等可朗读字符；空白与纯标点不算 */
    private fun isReadableChar(c: Char): Boolean {
        if (c.isWhitespace()) return false
        return when (Character.getType(c)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> false
            else -> true
        }
    }
}
