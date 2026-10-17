package com.captainivy.ebookreader.ui.library

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs

/**
 * 左侧滑动显露删除按钮的列表项容器。
 * 子 View 顺序：index 0 = 操作区（删除按钮，位于底层），index 1 = 内容区（位于上层）。
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var startTranslation = 0f
    private var actionWidth = 0
    private var opened = false

    var swipeEnabled: Boolean = true

    private val content: View? get() = if (childCount > 1) getChildAt(1) else null
    private val actions: View? get() = if (childCount > 0) getChildAt(0) else null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val c = content
        val a = actions
        if (c == null || a == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        a.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        actionWidth = a.measuredWidth
        c.measure(widthMeasureSpec, heightMeasureSpec)
        val h = c.measuredHeight
        a.measure(
            MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = content ?: return
        val a = actions ?: return
        val w = width
        val h = height
        c.layout(0, 0, w, h)
        a.layout(w - actionWidth, 0, w, h)
        c.translationX = if (opened) -actionWidth.toFloat() else 0f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return false
        val c = content ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startTranslation = c.translationX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled) return super.onTouchEvent(ev)
        val c = content ?: return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startTranslation = c.translationX
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val t = (startTranslation + dx).coerceIn(-actionWidth.toFloat(), 0f)
                c.translationX = t
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                setOpen(c.translationX < -actionWidth / 2f)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    fun setOpen(open: Boolean) {
        opened = open
        content?.animate()
            ?.translationX(if (open) -actionWidth.toFloat() else 0f)
            ?.setDuration(150)
            ?.start()
    }

    fun close() = setOpen(false)

    fun isOpen(): Boolean = opened
}
