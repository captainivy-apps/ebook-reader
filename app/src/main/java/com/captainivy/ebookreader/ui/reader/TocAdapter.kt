package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.data.model.TocItem

/**
 * 目录列表适配器：按层级缩进并使用多级字号，高亮当前所在章节。
 */
class TocAdapter(
    private val context: Context,
    private val items: List<TocItem>,
    private val currentSpine: Int
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): TocItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val tv = (convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.dialog_toc_item, parent, false)) as TextView
        val item = items[position]

        tv.text = item.title
        val level = item.level.coerceIn(0, 6)
        tv.setPadding(
            dp(16 + level * 16),
            tv.paddingTop,
            dp(16),
            tv.paddingBottom
        )
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeFor(level))
        tv.setTypeface(null, if (level == 0) Typeface.BOLD else Typeface.NORMAL)

        val current = item.spineIndex == currentSpine
        tv.setTextColor(if (current) COLOR_CURRENT else COLOR_NORMAL)
        return tv
    }

    private fun textSizeFor(level: Int): Float = when (level) {
        0 -> 16f
        1 -> 15f
        2 -> 14f
        else -> 13f
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_CURRENT = 0xFF2F6FB0.toInt()
        private const val COLOR_NORMAL = 0xFF333333.toInt()
    }
}
