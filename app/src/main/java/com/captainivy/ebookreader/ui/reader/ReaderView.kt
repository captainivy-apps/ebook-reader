package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    var highlightColor: Int = Color.parseColor("#F1E2AE")

    private var chapterLayout: ChapterLayout? = null
    private var pageIndex: Int = 0

    private var chapterTitle: String = ""
    private var progressText: String = ""

    /** 右上角状态：时间与电量百分比（-1 表示未知，不绘制电池） */
    private var statusTime: String = ""
    private var batteryPercent: Int = -1

    private var headerBand: Int = 0
    private var footerBand: Int = 0

    /** 当前朗读高亮范围（章节内字符偏移，-1 表示无高亮） */
    private var highlightStart: Int = -1
    private var highlightEnd: Int = -1

    /** 是否正在锁定手动翻页（朗读中且开启对应设置） */
    private var pageTurnLocked: Boolean = false

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPath = Path()

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
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics
        )
        textAlign = Paint.Align.RIGHT
    }
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val lowBatteryColor = Color.parseColor("#C0392B")

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

    /** 设置当前朗读高亮范围；范围与 ChapterLayout 的字符基准一致 */
    fun setHighlight(start: Int, end: Int) {
        if (highlightStart == start && highlightEnd == end) return
        highlightStart = start
        highlightEnd = end
        invalidate()
    }

    fun clearHighlight() {
        if (highlightStart < 0 && highlightEnd < 0) return
        highlightStart = -1
        highlightEnd = -1
        invalidate()
    }

    fun updateLabels(title: String, progress: String) {
        chapterTitle = title
        progressText = progress
        invalidate()
    }

    /** 更新右上角的时间与电量，仅在内容变化时重绘（墨水屏避免无谓刷新） */
    fun setStatusBar(time: String, batteryPercent: Int) {
        if (statusTime == time && this.batteryPercent == batteryPercent) return
        statusTime = time
        this.batteryPercent = batteryPercent
        invalidate()
    }

    /** 手动翻页锁定状态：在阅读进度左侧显示小锁图标 */
    fun setPageTurnLocked(locked: Boolean) {
        if (pageTurnLocked == locked) return
        pageTurnLocked = locked
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
                    drawHighlight(canvas, layout, item, block, sl)
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

    /** 在文字下方绘制当前朗读句子的高亮底色 */
    private fun drawHighlight(
        canvas: Canvas,
        layout: ChapterLayout,
        item: PlacedItem,
        block: TextBlock,
        sl: android.text.StaticLayout
    ) {
        val start = highlightStart
        val end = highlightEnd
        if (start < 0 || end <= start) return
        val blockStart = layout.blockOffset(item.blockIndex)
        val length = block.text.length
        val localStart = (start - blockStart).coerceIn(0, length)
        val localEnd = (end - blockStart).coerceIn(0, length)
        if (localEnd <= localStart) return
        highlightPath.reset()
        try {
            sl.getSelectionPath(localStart, localEnd, highlightPath)
        } catch (_: Exception) {
            return
        }
        highlightPaint.color = highlightColor
        canvas.drawPath(highlightPath, highlightPaint)
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
        val gap = 12f * resources.displayMetrics.density
        val bandBottom = (headerBand - gap).coerceAtLeast(labelPaint.textSize + 10f)
        val baseline = bandBottom - (bandBottom - labelPaint.textSize) / 2f

        val showStatus = statusTime.isNotEmpty() || batteryPercent >= 0
        if (chapterTitle.isNotEmpty() || showStatus) {
            bgPaint.color = chapterBandColor
            canvas.drawRect(0f, 0f, width.toFloat(), bandBottom, bgPaint)
        }

        // 右上角状态区域（时间 + 电量）布局
        val right = (width - paddingRight).toFloat()
        val batteryW = dp(22f)
        val batteryH = dp(11f)
        val nubW = dp(2.5f)
        val batteryLeft = right - batteryW - nubW
        val timeRight = if (batteryPercent >= 0) batteryLeft - dp(6f) else right
        val timeWidth = if (statusTime.isNotEmpty()) timePaint.measureText(statusTime) else 0f
        val statusLeft = if (statusTime.isNotEmpty()) timeRight - timeWidth else batteryLeft

        // 章节标题裁剪到状态区域左侧，避免与时间/电量重叠
        if (chapterTitle.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(0f, 0f, (statusLeft - dp(8f)).coerceAtLeast(0f), bandBottom)
            canvas.drawText(chapterTitle, paddingLeft.toFloat(), baseline, labelPaint)
            canvas.restore()
        }

        if (showStatus) {
            if (statusTime.isNotEmpty()) {
                timePaint.color = labelColor
                canvas.drawText(statusTime, timeRight, baseline, timePaint)
            }
            if (batteryPercent >= 0) {
                val iconCenterY = baseline - labelPaint.textSize / 2f
                drawBattery(
                    canvas, batteryLeft, iconCenterY - batteryH / 2f,
                    batteryW, batteryH, nubW
                )
            }
        }

        if (progressText.isNotEmpty()) {
            val progressRight = (width - paddingRight).toFloat()
            val progressBaseline = height - footerBand + progressPaint.textSize + 6f
            canvas.drawText(progressText, progressRight, progressBaseline, progressPaint)
            if (pageTurnLocked) {
                val textLeft = progressRight - progressPaint.measureText(progressText)
                val iconSize = progressPaint.textSize * 0.95f
                val iconLeft = textLeft - dp(5f) - iconSize
                val centerY = progressBaseline - progressPaint.textSize * 0.35f
                drawLock(canvas, iconLeft, centerY, iconSize)
            }
        }
    }

    /** 绘制进度左侧的小锁图标：锁梁 + 锁体 */
    private fun drawLock(canvas: Canvas, left: Float, centerY: Float, size: Float) {
        lockPaint.color = progressPaint.color

        val bodyWidth = size * 0.75f
        val bodyHeight = size * 0.48f
        val bodyLeft = left + (size - bodyWidth) / 2f
        val bodyTop = centerY - size * 0.1f
        val bodyBottom = bodyTop + bodyHeight
        val radius = size * 0.12f
        lockPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyBottom,
            radius, radius, lockPaint
        )

        val shackleWidth = size * 0.5f
        val shackleHeight = size * 0.44f
        val shackleLeft = left + (size - shackleWidth) / 2f
        val shackleRect = RectF(
            shackleLeft,
            bodyTop - shackleHeight,
            shackleLeft + shackleWidth,
            bodyTop + shackleHeight
        )
        lockPaint.style = Paint.Style.STROKE
        lockPaint.strokeWidth = (size * 0.14f).coerceAtLeast(dp(1f))
        canvas.drawArc(shackleRect, 180f, 180f, false, lockPaint)
    }

    /** 绘制右上角电池图标：外框 + 右侧电极 + 按百分比填充 */
    private fun drawBattery(
        canvas: Canvas,
        left: Float,
        top: Float,
        bodyWidth: Float,
        bodyHeight: Float,
        nubWidth: Float
    ) {
        val percent = batteryPercent.coerceIn(0, 100)
        val color = if (percent <= 15) lowBatteryColor else labelColor

        batteryPaint.color = color
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = dp(1.2f)
        val radius = dp(2.5f)
        canvas.drawRoundRect(
            left, top, left + bodyWidth, top + bodyHeight, radius, radius, batteryPaint
        )

        // 电极
        batteryPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            left + bodyWidth, top + bodyHeight * 0.28f,
            left + bodyWidth + nubWidth, top + bodyHeight * 0.72f,
            nubWidth / 2f, nubWidth / 2f, batteryPaint
        )

        // 电量填充
        val inset = dp(2f)
        val fillWidth = (bodyWidth - inset * 2f) * (percent / 100f)
        if (fillWidth > 0f) {
            val fillRadius = dp(1f)
            canvas.drawRoundRect(
                left + inset, top + inset,
                left + inset + fillWidth, top + bodyHeight - inset,
                fillRadius, fillRadius, batteryPaint
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

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
