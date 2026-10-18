package com.captainivy.ebookreader.ui.library

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.captainivy.ebookreader.App
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.data.Prefs
import com.captainivy.ebookreader.data.importer.BookImporter
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.databinding.ActivityLibraryBinding
import com.captainivy.ebookreader.ui.reader.ReaderActivity
import com.captainivy.ebookreader.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val repo by lazy { App.get().repository }
    private val prefs by lazy { App.get().prefs }
    private lateinit var adapter: BookAdapter
    private lateinit var importer: BookImporter

    private var offset = 0
    private var loading = false
    private var hasMore = true

    private val pickDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importUri(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        importer = BookImporter(this, repo)

        adapter = BookAdapter(
            onClick = { openReader(it) },
            onDelete = { confirmDeleteSingle(it) },
            onSelectionChanged = { updateSelectionUi() }
        )
        binding.recycler.adapter = adapter
        applyViewMode()
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.count() - 5) loadMore()
            }
        })

        binding.fabImport.setOnClickListener { pickDocument.launch(arrayOf("*/*")) }
        binding.btnSelClose.setOnClickListener { exitSelection() }
        binding.btnSelAll.setOnClickListener { adapter.selectAll(); updateSelectionUi() }
        binding.btnSelDelete.setOnClickListener { confirmDeleteSelected() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时按新的可用宽度重新计算封面网格列数
        applyViewMode()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_library, menu)
        val viewItem = menu.findItem(R.id.action_view)
        viewItem?.setIcon(if (prefs.libraryGrid) R.drawable.ic_list else R.drawable.ic_grid)
        viewItem?.setTitle(
            if (prefs.libraryGrid) R.string.library_view_list else R.string.library_view_cover
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            reload(); true
        }
        R.id.action_view -> {
            prefs.libraryGrid = !prefs.libraryGrid
            applyViewMode()
            invalidateOptionsMenu()
            true
        }
        R.id.action_sort -> {
            showSortDialog(); true
        }
        R.id.action_import -> {
            pickDocument.launch(arrayOf("*/*")); true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------- view mode

    private fun applyViewMode() {
        val grid = prefs.libraryGrid
        adapter.grid = grid
        val firstVisible = (binding.recycler.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        binding.recycler.layoutManager = if (grid) {
            GridLayoutManager(this, gridColumns())
        } else {
            LinearLayoutManager(this)
        }
        if (firstVisible > 0) binding.recycler.scrollToPosition(firstVisible)
    }

    /** 按当前屏幕宽度自适应封面网格列数，横屏下自动展示更多列 */
    private fun gridColumns(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return (widthDp / TARGET_COLUMN_DP).toInt().coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
    }

    // -------------------------------------------------------------- paging

    private fun reload() {
        offset = 0
        hasMore = true
        loading = false
        adapter.clear()
        loadMore()
    }

    private fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        val currentOffset = offset
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                repo.page(prefs.sortKey, prefs.sortAscending, PAGE_SIZE, currentOffset)
            }
            adapter.submit(list, append = currentOffset > 0)
            offset = currentOffset + list.size
            if (list.size < PAGE_SIZE) hasMore = false
            loading = false
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        binding.emptyView.visibility =
            if (adapter.count() == 0 && !loading) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------- actions

    private fun openReader(book: Book) {
        startActivity(ReaderActivity.newIntent(this, book.id))
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_recent_desc),
            getString(R.string.sort_recent_asc),
            getString(R.string.sort_added_desc),
            getString(R.string.sort_added_asc)
        )
        val current = if (prefs.sortKey == Prefs.SORT_ADDED) {
            if (prefs.sortAscending) 3 else 2
        } else {
            if (prefs.sortAscending) 1 else 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.sortKey = if (which <= 1) Prefs.SORT_LAST_READ else Prefs.SORT_ADDED
                prefs.sortAscending = which == 1 || which == 3
                dialog.dismiss()
                reload()
            }
            .show()
    }

    private fun importUri(uri: Uri) {
        binding.fabImport.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    importer.importUri(uri)
                    null
                } catch (e: Exception) {
                    e.message ?: "导入失败"
                }
            }
            binding.fabImport.isEnabled = true
            if (result == null) {
                Toast.makeText(this@LibraryActivity, R.string.import_success, Toast.LENGTH_SHORT).show()
                reload()
            } else {
                Toast.makeText(this@LibraryActivity, result, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------ selection

    private fun updateSelectionUi() {
        val selecting = adapter.selectionMode
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.toolbar.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.txtSelCount.text = getString(R.string.selection_count, adapter.selected.size)
        if (selecting && adapter.selected.isEmpty()) exitSelection()
    }

    private fun exitSelection() {
        adapter.selectionMode = false
        updateSelectionUi()
    }

    private fun confirmDeleteSingle(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, book.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.delete(listOf(book.id)) }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selected.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_selected_message, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.delete(ids) }
                    exitSelection()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val TARGET_COLUMN_DP = 110f
        private const val MIN_GRID_COLUMNS = 2
        private const val MAX_GRID_COLUMNS = 6
    }
}
