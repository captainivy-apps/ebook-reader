package com.captainivy.ebookreader.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSegmenterTest {

    @Test
    fun splitsChineseSentences() {
        val result = TtsSegmenter.segment(listOf("你好。世界！"))
        assertEquals(2, result.size)
        assertEquals("你好。", result[0].text)
        assertEquals(0, result[0].start)
        assertEquals(3, result[0].end)
        assertEquals("世界！", result[1].text)
        assertEquals(3, result[1].start)
        assertEquals(6, result[1].end)
    }

    @Test
    fun keepsOffsetsAcrossBlocks() {
        val texts = listOf("第一段。", "Second!", "第三段")
        val result = TtsSegmenter.segment(texts)
        assertEquals(
            listOf("第一段。", "Second!", "第三段"),
            result.map { it.text }
        )
        assertEquals(0, result[0].start)
        assertEquals(4, result[1].start)
        assertEquals(11, result[2].start)
        val joined = texts.joinToString("")
        result.forEach {
            assertEquals(it.text, joined.substring(it.start, it.end))
        }
    }

    @Test
    fun trimsWhitespaceButKeepsCorrectOffsets() {
        val result = TtsSegmenter.segment(listOf("  你好。 世界！  "))
        assertEquals(2, result.size)
        assertEquals("你好。", result[0].text)
        assertEquals(2, result[0].start)
        assertEquals(5, result[0].end)
        assertEquals("世界！", result[1].text)
        assertEquals(6, result[1].start)
        assertEquals(9, result[1].end)
    }

    @Test
    fun splitsLongTextWithoutOverflow() {
        val text = "啊".repeat(200)
        val result = TtsSegmenter.segment(listOf(text))
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.text.length <= TtsSegmenter.MAX_SEGMENT_CHARS })
        assertEquals(text, result.joinToString("") { it.text })
        result.forEach {
            assertEquals(it.text, text.substring(it.start, it.end))
        }
    }

    @Test
    fun keepsClosingQuoteWithSentence() {
        val result = TtsSegmenter.segment(listOf("他说：“你好。”然后走了。"))
        assertEquals(2, result.size)
        assertEquals("他说：“你好。”", result[0].text)
        assertEquals("然后走了。", result[1].text)
    }

    @Test
    fun doesNotSwallowOpeningQuoteOnNextLine() {
        val result = TtsSegmenter.segment(listOf("你好。\n\"Hello\""))
        assertEquals(2, result.size)
        assertEquals("你好。", result[0].text)
        assertEquals("\"Hello\"", result[1].text)
    }

    @Test
    fun keepsConsecutivePunctuationAndEllipsisTogether() {
        val result = TtsSegmenter.segment(listOf("真的吗？！等等……然后呢。"))
        assertEquals(3, result.size)
        assertEquals("真的吗？！", result[0].text)
        assertEquals("等等……", result[1].text)
        assertEquals("然后呢。", result[2].text)
    }

    @Test
    fun skipsPunctuationOnlyFragments() {
        val result = TtsSegmenter.segment(listOf("第一句。\n***\n第二句。"))
        assertEquals(listOf("第一句。", "第二句。"), result.map { it.text })
    }

    @Test
    fun longEnglishTextSplitsAtWhitespaceNotInsideWord() {
        val text = "word ".repeat(40) + "end"
        val result = TtsSegmenter.segment(listOf(text))
        assertTrue(result.size > 1)
        assertTrue(result.all { it.text.length <= TtsSegmenter.MAX_SEGMENT_CHARS })
        result.forEach {
            assertEquals(it.text, it.text.trim())
            assertEquals(it.text, text.substring(it.start, it.end))
        }
        assertEquals(text.replace(" ", ""), result.joinToString("") { it.text }.replace(" ", ""))
    }

    @Test
    fun neverSplitsSurrogatePairs() {
        val emoji = "\uD83D\uDE00"
        val text = emoji.repeat(60)
        val result = TtsSegmenter.segment(listOf(text))
        assertTrue(result.size > 1)
        result.forEach {
            assertEquals(0, it.text.length % 2)
            assertEquals(it.text, text.substring(it.start, it.end))
        }
        assertEquals(text, result.joinToString("") { it.text })
    }

    @Test
    fun ignoresBlankText() {
        assertTrue(TtsSegmenter.segment(listOf("", "   ", "\n")).isEmpty())
    }

    @Test
    fun handlesEnglishPunctuation() {
        val result = TtsSegmenter.segment(listOf("Hello! Are you ok? 你好;"))
        assertEquals(3, result.size)
        assertEquals("Hello!", result[0].text)
        assertEquals("Are you ok?", result[1].text)
        assertEquals("你好;", result[2].text)
    }
}
