package com.captainivy.ebookreader.ui.library

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.databinding.ItemBookBinding
import com.captainivy.ebookreader.databinding.ItemBookGridBinding
import com.captainivy.ebookreader.util.Bitmaps
import java.io.File
import java.util.Locale

class BookAdapter(
    private val onClick: (Book) -> Unit,
    private val onDelete: (Book) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<Book>()
    private val coverCache = LruCache<String, Bitmap>(24)

    val selected = HashSet<Long>()

    /** 封面网格模式 */
    var grid: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var selectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) selected.clear()
            notifyDataSetChanged()
        }

    fun submit(list: List<Book>, append: Boolean) {
        if (!append) items.clear()
        val start = items.size
        items.addAll(list)
        if (append) notifyItemRangeInserted(start, list.size) else notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun count(): Int = items.size

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(it.id) }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (grid) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridVH(ItemBookGridBinding.inflate(inflater, parent, false))
        } else {
            ListVH(ItemBookBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val book = items[position]
        when (holder) {
            is ListVH -> holder.bind(book)
            is GridVH -> holder.bind(book)
        }
    }

    // ------------------------------------------------------------- selection

    private fun onItemClicked(book: Book) {
        if (selectionMode) toggle(book) else onClick(book)
    }

    private fun toggle(book: Book) {
        if (selected.contains(book.id)) selected.remove(book.id) else selected.add(book.id)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun enterSelection(book: Book) {
        selectionMode = true
        selected.add(book.id)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun loadCover(book: Book, reqW: Int, reqH: Int): Bitmap? {
        val path = book.coverPath ?: return null
        val key = "$path@${reqW}x$reqH"
        coverCache.get(key)?.let { return it }
        val bmp = Bitmaps.decodeSampled(File(path), reqW, reqH)
        if (bmp != null) coverCache.put(key, bmp)
        return bmp
    }

    private fun progressText(book: Book): String =
        String.format(Locale.US, "已读 %.1f%%", book.progress * 100f)

    inner class ListVH(private val b: ItemBookBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(book: Book) {
            b.txtTitle.text = book.title
            b.txtAuthor.text = book.author.ifBlank { "未知作者" }
            b.progressRead.progress = (book.progress * 100).toInt()
            b.txtProgress.text = progressText(book)
            b.imgCover.setImageBitmap(loadCover(book, 120, 160))

            b.btnDelete.setOnClickListener {
                b.root.close()
                onDelete(book)
            }
            b.checkSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            b.checkSelect.isChecked = selected.contains(book.id)
            b.root.swipeEnabled = !selectionMode
            if (!selectionMode) b.root.close()

            b.itemContent.setOnClickListener { onItemClicked(book) }
            b.itemContent.setOnLongClickListener {
                if (!selectionMode) enterSelection(book)
                true
            }
        }
    }

    inner class GridVH(private val b: ItemBookGridBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(book: Book) {
            b.txtTitle.text = book.title
            b.txtProgress.text = progressText(book)
            b.imgCover.setImageBitmap(loadCover(book, 240, 320))

            b.checkSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            b.checkSelect.isChecked = selected.contains(book.id)
            b.checkSelect.setOnClickListener { toggle(book) }

            b.itemContent.setOnClickListener { onItemClicked(book) }
            b.itemContent.setOnLongClickListener {
                if (!selectionMode) enterSelection(book)
                true
            }
        }
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
    }
}
