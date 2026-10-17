package com.captainivy.ebookreader.ui.reader.layout

import android.text.StaticLayout
import com.captainivy.ebookreader.data.epub.Block
import com.captainivy.ebookreader.data.epub.TextBlock

class PlacedItem(
    val blockIndex: Int,
    val startLine: Int,
    val endLine: Int,
    val top: Int,
    val height: Int,
    val xOffset: Int,
    val drawWidth: Int,
    val drawHeight: Int
)

class Page(val items: List<PlacedItem>)

/**
 * 一章排版结果：分页 + 每块 StaticLayout + 字符偏移映射。
 */
class ChapterLayout(
    val blocks: List<Block>,
    val layouts: Array<StaticLayout?>,
    val pages: List<Page>,
    val width: Int,
    val height: Int
) {

    /** 每个块的起始字符偏移（图片块计 0，取所在位置偏移） */
    private val blockOffsets: IntArray = IntArray(blocks.size).also { arr ->
        var acc = 0
        blocks.forEachIndexed { i, b ->
            arr[i] = acc
            if (b is TextBlock) acc += b.text.length
        }
    }

    val pageCount: Int get() = pages.size

    /** 字符偏移 -> 页码 */
    fun pageForOffset(offset: Int): Int {
        if (pages.isEmpty()) return 0
        var blockIndex = -1
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b is TextBlock) {
                val start = blockOffsets[i]
                val stop = start + b.text.length
                if (offset < stop) {
                    blockIndex = i
                    break
                }
            }
        }
        if (blockIndex < 0) return pages.size - 1
        val layout = layouts[blockIndex] ?: return pages.size - 1
        val local = (offset - blockOffsets[blockIndex]).coerceIn(0, (layout.text.length - 1).coerceAtLeast(0))
        val line = layout.getLineForOffset(local)
        for (pi in pages.indices) {
            for (item in pages[pi].items) {
                if (item.blockIndex == blockIndex && line >= item.startLine && line < item.endLine) {
                    return pi
                }
            }
        }
        return pages.size - 1
    }

    /** 页码起始字符偏移 */
    fun offsetForPage(pageIndex: Int): Int {
        if (pages.isEmpty()) return 0
        val page = pages[pageIndex.coerceIn(0, pages.size - 1)]
        val item = page.items.firstOrNull() ?: return 0
        val block = blocks[item.blockIndex]
        return if (block is TextBlock) {
            blockOffsets[item.blockIndex] + (layouts[item.blockIndex]?.getLineStart(item.startLine) ?: 0)
        } else {
            blockOffsets[item.blockIndex]
        }
    }
}
