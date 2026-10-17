package com.captainivy.ebookreader.ui.reader.layout

import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import com.captainivy.ebookreader.data.epub.Block
import com.captainivy.ebookreader.data.epub.HorizontalRuleBlock
import com.captainivy.ebookreader.data.epub.ImageBlock
import com.captainivy.ebookreader.data.epub.TextAlign
import com.captainivy.ebookreader.data.epub.TextBlock
import com.captainivy.ebookreader.data.epub.TextStyle

class LayoutConfig(
    val typeface: Typeface,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float,
    val paragraphSpacingPx: Int,
    val width: Int,
    val height: Int,
    val indentFirstLine: Boolean = false,
    val justify: Boolean = false
)

/**
 * 把章节块序列按给定版式切分为页。支持富文本、图片与分隔线。
 */
class Paginator {

    fun paginate(blocks: List<Block>, config: LayoutConfig): ChapterLayout {
        val width = config.width.coerceAtLeast(1)
        val height = config.height.coerceAtLeast(1)

        val layouts = arrayOfNulls<StaticLayout>(blocks.size)

        blocks.forEachIndexed { i, block ->
            if (block is TextBlock) {
                layouts[i] = buildTextLayout(block, config, width)
            }
        }

        val pages = ArrayList<Page>()
        var current = ArrayList<PlacedItem>()
        var used = 0

        fun newPage() {
            if (current.isNotEmpty()) pages.add(Page(current))
            current = ArrayList()
            used = 0
        }

        blocks.forEachIndexed { bi, block ->
            when (block) {
                is TextBlock -> {
                    val layout = layouts[bi]!!
                    val total = layout.lineCount
                    val spacing = paragraphSpacingFor(block.style, config)
                    var line = 0
                    var firstChunk = true
                    while (line < total) {
                        val extraTop = if (firstChunk && current.isNotEmpty()) spacing else 0
                        val avail = height - used - extraTop
                        val base = layout.getLineTop(line)
                        var l = line
                        while (l < total) {
                            val h = layout.getLineBottom(l) - base
                            if (h > avail) break
                            l++
                        }
                        // 当前页连一行都放不下时，换到新页重排，避免正文溢出到页脚进度区
                        if (l == line) {
                            if (current.isNotEmpty()) {
                                newPage()
                                continue
                            }
                            // 整页高度仍放不下一行（极端字号）：强制放置一行以免死循环
                            l = line + 1
                        }
                        val startTop = used + extraTop
                        val blockH = layout.getLineBottom(l - 1) - base
                        current.add(
                            PlacedItem(bi, line, l, startTop, blockH, 0, layout.width, blockH)
                        )
                        used = startTop + blockH
                        line = l
                        firstChunk = false
                        if (line < total) newPage()
                    }
                }
                is ImageBlock -> {
                    var dw = block.width
                    var dh = block.height
                    if (dw <= 0 || dh <= 0) return@forEachIndexed
                    if (dw > width) {
                        dh = dh * width / dw
                        dw = width
                    }
                    if (dh > height) {
                        dw = dw * height / dh
                        dh = height
                    }
                    val spacing = if (current.isNotEmpty()) config.paragraphSpacingPx else 0
                    if (used + spacing + dh > height && current.isNotEmpty()) newPage()
                    val top = used + (if (current.isEmpty()) 0 else config.paragraphSpacingPx)
                    val x = (width - dw) / 2
                    current.add(PlacedItem(bi, 0, 0, top, dh, x, dw, dh))
                    used = top + dh
                }
                is HorizontalRuleBlock -> {
                    val ruleH = (config.textSizePx * 1.4f).toInt().coerceAtLeast(8)
                    val spacing = if (current.isNotEmpty()) config.paragraphSpacingPx else 0
                    if (used + spacing + ruleH > height && current.isNotEmpty()) newPage()
                    val top = used + spacing
                    current.add(PlacedItem(bi, 0, 0, top, ruleH, 0, width, ruleH))
                    used = top + ruleH
                }
            }
        }
        if (current.isNotEmpty()) pages.add(Page(current))
        if (pages.isEmpty()) pages.add(Page(emptyList()))

        return ChapterLayout(blocks, layouts, pages, width, height)
    }

    private fun buildTextLayout(block: TextBlock, config: LayoutConfig, width: Int): StaticLayout {
        val size = config.textSizePx * scaleFor(block.style)
        val typeface = if (block.style in HEADING_STYLES) {
            Typeface.create(config.typeface, Typeface.BOLD)
        } else {
            config.typeface
        }
        val paint = TextPaint().apply {
            isAntiAlias = true
            this.typeface = typeface
            textSize = size
        }

        val text = SpannableStringBuilder(block.text)
        leadingMarginFor(block, size, config)?.let {
            text.setSpan(it, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val alignment = alignmentFor(block.style, block.align)
        val justify = config.justify && block.style == TextStyle.NORMAL

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(alignment)
                .setLineSpacing(0f, config.lineSpacingMultiplier)
                .setIncludePad(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && justify) {
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                width.coerceAtLeast(1),
                alignment,
                config.lineSpacingMultiplier,
                0f,
                false
            )
        }
    }

    private fun leadingMarginFor(
        block: TextBlock,
        size: Float,
        config: LayoutConfig
    ): LeadingMarginSpan? = when (block.style) {
        TextStyle.NORMAL ->
            if (config.indentFirstLine) {
                LeadingMarginSpan.Standard((config.textSizePx * 2f).toInt(), 0)
            } else {
                null
            }
        TextStyle.LIST -> LeadingMarginSpan.Standard(0, (size * 1.2f).toInt())
        TextStyle.QUOTE -> LeadingMarginSpan.Standard(size.toInt(), size.toInt())
        TextStyle.CODE -> LeadingMarginSpan.Standard((size * 0.8f).toInt(), (size * 0.8f).toInt())
        else -> null
    }

    private fun alignmentFor(style: TextStyle, align: TextAlign): Layout.Alignment = when {
        style == TextStyle.CAPTION -> Layout.Alignment.ALIGN_CENTER
        align == TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        align == TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        style == TextStyle.H1 || style == TextStyle.H2 -> Layout.Alignment.ALIGN_CENTER
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /** 多级标题用逐级递减的字号加以区分 */
    private fun scaleFor(style: TextStyle): Float = when (style) {
        TextStyle.H1 -> 1.9f
        TextStyle.H2 -> 1.62f
        TextStyle.H3 -> 1.4f
        TextStyle.H4 -> 1.24f
        TextStyle.H5 -> 1.12f
        TextStyle.H6 -> 1.0f
        TextStyle.CODE -> 0.9f
        TextStyle.CAPTION -> 0.82f
        else -> 1.0f
    }

    private fun paragraphSpacingFor(style: TextStyle, config: LayoutConfig): Int {
        val base = config.paragraphSpacingPx
        return when (style) {
            TextStyle.H1, TextStyle.H2, TextStyle.H3, TextStyle.H4, TextStyle.H5, TextStyle.H6 ->
                base + (config.textSizePx * 0.5f).toInt()
            TextStyle.LIST -> base / 2
            TextStyle.CAPTION -> base / 2
            else -> base
        }
    }

    companion object {
        private val HEADING_STYLES = setOf(
            TextStyle.H1, TextStyle.H2, TextStyle.H3,
            TextStyle.H4, TextStyle.H5, TextStyle.H6
        )
    }
}
