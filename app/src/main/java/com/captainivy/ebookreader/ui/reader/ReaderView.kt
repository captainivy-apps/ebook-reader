package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.captainivy.ebookreader.data.epub.HorizontalRuleBlock
import com.captainivy.ebookreader.data.epub.ImageBlock
import com.captainivy.ebookreader.data.epub.NoteRefSpan
import com.captainivy.ebookreader.data.epub.TextBlock
import com.captainivy.ebookreader.data.epub.TextStyle
import com.captainivy.ebookreader.ui.reader.layout.ChapterLayout
import com.captainivy.ebookreader.ui.reader.layout.PlacedItem

/**
 * 单页渲染视图：一次只绘制一页。翻页只做一次整屏重绘，
 * 残影由 [EinkRefreshController] 按节奏触发的厂商全刷清除，避免软件模拟黑屏造成的双闪。
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Zone { PREV, NEXT, CENTER }

    var onTapListener: ((Zone) -> Unit)? = null

    /** 点击脚注/尾注上标时回调：noteId 为锚点 id，targetHref 为注释所在文件 */
    var onNoteTapListener: ((noteId: String, targetHref: String) -> Unit)? = null

    var textColor: Int = Color.parseColor("#24211C")
    var pageColor: Int = Color.parseColor("#F5F1E6")
    var labelColor: Int = Color.parseColor("#8A857A")
    var chapterBandColor: Int = Color.parseColor("#ECE7DA")
    var quoteColor: Int = Color.parseColor("#EFEBE0")
    var quoteBarColor: Int = Color.parseColor("#C7BFA8")
    var codeColor: Int = Color.parseColor("#EEEAE0")
    var ruleColor: Int = Color.parseColor("#CFC8B8")

    private var chapterLayout: ChapterLayout? = null
    private var pageIndex: Int = 0

    private var chapterTitle: String = ""
    private var progressText: String = ""

    private var headerBand: Int = 0
    private var footerBand: Int = 0

    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imageRect = RectF()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
        textAlign = Paint.Align.RIGHT
    }

    fun setBands(headerPx: Int, footerPx: Int) {
        headerBand = headerPx
        footerBand = footerPx
    }

    fun getChapterLayout(): ChapterLayout? = chapterLayout

    fun currentPage(): Int = pageIndex

    fun showPage(layout: ChapterLayout?, page: Int, title: String, progress: String) {
        chapterLayout = layout
        pageIndex = page.coerceAtLeast(0)
        chapterTitle = title
        progressText = progress
        invalidate()
    }

    fun updateLabels(title: String, progress: String) {
        chapterTitle = title
        progressText = progress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageColor)

        val layout = chapterLayout ?: return
        if (pageIndex >= layout.pages.size) return

        val page = layout.pages[pageIndex]

        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())

        // 先绘制背景（引用、代码块背景，分隔线），再绘制正文与图片
        for (item in page.items) {
            when (val block = layout.blocks[item.blockIndex]) {
                is TextBlock -> when (block.style) {
                    TextStyle.QUOTE -> drawBlockBackground(canvas, item, quoteColor, quoteBarColor)
                    TextStyle.CODE -> drawBlockBackground(canvas, item, codeColor, 0)
                    else -> Unit
                }
                is HorizontalRuleBlock -> {
                    val cy = item.top + item.height / 2f
                    rulePaint.color = ruleColor
                    canvas.drawLine(0f, cy, item.drawWidth.toFloat(), cy, rulePaint)
                }
                else -> Unit
            }
        }

        for (item in page.items) {
            when (val block = layout.blocks[item.blockIndex]) {
                is TextBlock -> {
                    val sl = layout.layouts[item.blockIndex] ?: continue
                    canvas.save()
                    canvas.clipRect(
                        item.xOffset.toFloat(),
                        item.top.toFloat(),
                        (item.xOffset + item.drawWidth).toFloat(),
                        (item.top + item.height).toFloat()
                    )
                    canvas.translate(
                        item.xOffset.toFloat(),
                        (item.top - sl.getLineTop(item.startLine)).toFloat()
                    )
                    sl.draw(canvas)
                    canvas.restore()
                }
                is ImageBlock -> {
                    imageRect.set(
                        item.xOffset.toFloat(),
                        item.top.toFloat(),
                        (item.xOffset + item.drawWidth).toFloat(),
                        (item.top + item.drawHeight).toFloat()
                    )
                    canvas.drawBitmap(block.bitmap, null, imageRect, imagePaint)
                }
                else -> Unit
            }
        }
        canvas.restore()

        drawLabels(canvas)
    }

    private fun drawBlockBackground(
        canvas: Canvas,
        item: PlacedItem,
        fillColor: Int,
        barColor: Int
    ) {
        val left = item.xOffset.toFloat()
        val right = (item.xOffset + item.drawWidth).toFloat()
        val top = item.top.toFloat() - 4f
        val bottom = (item.top + item.height).toFloat() + 4f
        bgPaint.color = fillColor
        canvas.drawRoundRect(RectF(left, top, right, bottom), 6f, 6f, bgPaint)
        if (barColor != 0) {
            bgPaint.color = barColor
            canvas.drawRect(left + 5f, item.top.toFloat(), left + 8f, (item.top + item.height).toFloat(), bgPaint)
        }
    }

    private fun drawLabels(canvas: Canvas) {
        if (chapterTitle.isNotEmpty()) {
            val gap = 12f * resources.displayMetrics.density
            val bandBottom = (headerBand - gap).coerceAtLeast(labelPaint.textSize + 10f)
            bgPaint.color = chapterBandColor
            canvas.drawRect(0f, 0f, width.toFloat(), bandBottom, bgPaint)
            val baseline = bandBottom - (bandBottom - labelPaint.textSize) / 2f
            canvas.drawText(chapterTitle, paddingLeft.toFloat(), baseline, labelPaint)
        }
        if (progressText.isNotEmpty()) {
            val baseline = height - footerBand + progressPaint.textSize + 6f
            canvas.drawText(progressText, (width - paddingRight).toFloat(), baseline, progressPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val note = findNoteAt(event.x, event.y)
            if (note != null) {
                onNoteTapListener?.invoke(note.noteId, note.targetHref)
                return true
            }
            val zone = when {
                event.x < width / 3f -> Zone.PREV
                event.x > width * 2f / 3f -> Zone.NEXT
                else -> Zone.CENTER
            }
            onTapListener?.invoke(zone)
            return true
        }
        return event.action == MotionEvent.ACTION_DOWN || super.onTouchEvent(event)
    }

    /** 命中测试：返回触点下的脚注上标（若有） */
    private fun findNoteAt(px: Float, py: Float): NoteRefSpan? {
        val layout = chapterLayout ?: return null
        if (pageIndex >= layout.pages.size) return null
        val x = px - paddingLeft
        val y = py - paddingTop
        val page = layout.pages[pageIndex]
        for (item in page.items) {
            val block = layout.blocks[item.blockIndex]
            if (block !is TextBlock) continue
            if (x < item.xOffset || x > item.xOffset + item.drawWidth) continue
            if (y < item.top || y > item.top + item.height) continue
            val sl = layout.layouts[item.blockIndex] ?: continue
            val line = sl.getLineForVertical((y - (item.top - sl.getLineTop(item.startLine))).toInt())
            if (line < item.startLine || line >= item.endLine) continue
            val offset = sl.getOffsetForHorizontal(line, x - item.xOffset)
            val spanned = sl.text as? android.text.Spanned ?: continue
            for (o in intArrayOf(offset, offset - 1, offset + 1)) {
                if (o < 0 || o > spanned.length) continue
                val spans = spanned.getSpans(o, o, NoteRefSpan::class.java)
                if (spans.isNotEmpty()) return spans[0]
            }
        }
        return null
    }
}
