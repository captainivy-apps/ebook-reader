package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.captainivy.ebookreader.App
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.data.FontManager
import com.captainivy.ebookreader.data.Prefs
import com.captainivy.ebookreader.data.epub.ChapterContentParser
import com.captainivy.ebookreader.data.epub.EpubArchive
import com.captainivy.ebookreader.data.epub.ImageLoader
import com.captainivy.ebookreader.data.epub.NoteResolver
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.TocItem
import com.captainivy.ebookreader.databinding.ActivityReaderBinding
import com.captainivy.ebookreader.databinding.DialogTypoBinding
import com.captainivy.ebookreader.ui.reader.layout.ChapterLayout
import com.captainivy.ebookreader.ui.reader.layout.LayoutConfig
import com.captainivy.ebookreader.ui.reader.layout.Paginator
import com.captainivy.ebookreader.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val repo by lazy { App.get().repository }
    private val prefs by lazy { App.get().prefs }

    private var bookId = 0L
    private var book: Book? = null
    private var chapters: List<Chapter> = emptyList()
    private var tocItems: List<TocItem> = emptyList()
    private var archive: EpubArchive? = null
    private var noteResolver: NoteResolver? = null

    private val contentParser = ChapterContentParser()
    private val paginator = Paginator()

    private var chapterIndex = 0
    private var layout: ChapterLayout? = null
    private var page = 0
    private var barsVisible = false
    private var loadJob: Job? = null

    private var bookReady = false
    private var pendingChapter = 0
    private var pendingOffset = 0
    private var lastWidth = 0
    private var lastHeight = 0

    private var sessionStartMs = 0L
    private var lastOffset = -1
    private var sessionChars = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        if (bookId <= 0L) {
            finish()
            return
        }
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        applyReaderPadding()
        applyLeftHandIndicator()
        binding.readerView.refreshMode = prefs.refreshMode
        binding.readerView.onTapListener = ::onTap
        binding.readerView.onNoteTapListener = ::onNoteTap
        binding.readerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onViewLayout() }
        setupBars()
        loadBook()
    }

    override fun onResume() {
        super.onResume()
        applyLeftHandIndicator()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        saveReadingState()
    }

    override fun onDestroy() {
        super.onDestroy()
        loadJob?.cancel()
        archive?.close()
        archive = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val alt = event?.isAltPressed == true
        return when (keyCode) {
            // 向前翻页
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                nextPage(); true
            }
            // 向后翻页
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                prevPage(); true
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (alt) prevPage() else nextPage()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ------------------------------------------------------------------ setup

    private fun setupBars() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnMeta.setOnClickListener { showMetaDialog() }
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.btnBrightness.setOnClickListener { showBrightnessDialog() }
        binding.btnTypography.setOnClickListener { showTypographyDialog() }
    }

    /** 左手模式开启时在阅读区左下角显示提示图标 */
    private fun applyLeftHandIndicator() {
        binding.imgLeftHand.visibility = if (prefs.leftHandMode) View.VISIBLE else View.GONE
    }

    private fun applyReaderPadding() {
        val margin = dp(prefs.marginDp)
        val header = dp(40)
        val footer = dp(28)
        binding.readerView.setPadding(margin, header, margin, footer)
        binding.readerView.setBands(header, footer)
    }

    private fun loadBook() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val b = repo.get(bookId)
                val ch = repo.chapters(bookId)
                val toc = repo.toc(bookId)
                val arc = b?.let {
                    try {
                        EpubArchive.open(File(it.filePath))
                    } catch (_: Exception) {
                        null
                    }
                }
                Triple(b, ch, toc) to arc
            }
            val (data, arc) = loaded
            book = data.first
            chapters = data.second
            tocItems = data.third
            archive = arc
            noteResolver = arc?.let { NoteResolver(it) }
            val b = book
            if (b == null || chapters.isEmpty()) {
                finish()
                return@launch
            }
            binding.txtBookTitle.text = b.title
            pendingChapter = b.currentChapter.coerceIn(0, chapters.size - 1)
            val chapterStart = chapters[pendingChapter].charStart
            pendingOffset = (b.currentOffset - chapterStart).coerceAtLeast(0)
            bookReady = true
            sessionStartMs = System.currentTimeMillis()
            maybeStartReading()
            applyStoredBrightness()
        }
    }

    private fun onViewLayout() {
        val w = binding.readerView.width
        val h = binding.readerView.height
        if (w <= 0 || h <= 0) return
        if (w == lastWidth && h == lastHeight) return
        lastWidth = w
        lastHeight = h
        if (!bookReady) return
        if (layout != null) {
            reloadChapter(chapterIndex, currentOffset(), flash = false)
        } else {
            maybeStartReading()
        }
    }

    private fun maybeStartReading() {
        if (!bookReady || layout != null) return
        if (binding.readerView.width <= 0) return
        showChapter(pendingChapter, pendingOffset, flash = false)
    }

    // ------------------------------------------------------------- render core

    private fun contentSize(): Pair<Int, Int> {
        val w = binding.readerView.width - binding.readerView.paddingLeft - binding.readerView.paddingRight
        val h = binding.readerView.height - binding.readerView.paddingTop - binding.readerView.paddingBottom
        return w to h
    }

    private fun layoutConfig(w: Int, h: Int): LayoutConfig {
        val density = resources.displayMetrics.scaledDensity
        val sizePx = prefs.fontSizeSp * density
        return LayoutConfig(
            typeface = FontManager.typeface(prefs.fontFamily),
            textSizePx = sizePx,
            lineSpacingMultiplier = prefs.lineSpacing,
            paragraphSpacingPx = (sizePx * 0.7f).toInt(),
            width = w,
            height = h,
            indentFirstLine = prefs.indentFirstLine,
            justify = prefs.justify
        )
    }

    private fun showChapter(
        index: Int,
        offset: Int,
        flash: Boolean,
        startAtLastPage: Boolean = false,
        anchor: String? = null
    ) {
        if (index < 0 || index >= chapters.size) return
        val (w, h) = contentSize()
        if (w <= 0 || h <= 0) {
            pendingChapter = index
            pendingOffset = offset
            return
        }
        val chapter = chapters[index]
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                val html = try {
                    archive?.readText(chapter.href).orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val loader = ImageLoader { href ->
                    try {
                        archive?.readBytes(href)?.let { Bitmaps.decodeSampled(it, w, h) }
                    } catch (_: Exception) {
                        null
                    }
                }
                contentParser.parse(html, chapter.href, loader, w)
            }
            if (!isActive) return@launch
            val cfg = layoutConfig(w, h)
            val newLayout = withContext(Dispatchers.Default) {
                paginator.paginate(content.blocks, cfg)
            }
            if (!isActive) return@launch
            val anchorOffset = anchor?.let { content.anchors[it] }
            chapterIndex = index
            layout = newLayout
            page = when {
                startAtLastPage -> newLayout.pageCount - 1
                anchorOffset != null -> newLayout.pageForOffset(anchorOffset)
                else -> newLayout.pageForOffset(offset)
            }
            render(flash)
        }
    }

    private fun reloadChapter(index: Int, offset: Int, flash: Boolean) {
        showChapter(index, offset, flash)
    }

    private fun render(flash: Boolean) {
        val l = layout ?: return
        val off = l.offsetForPage(page)
        val title = chapters.getOrNull(chapterIndex)?.title.orEmpty()
        binding.readerView.showPage(l, page, title, progressText(off), flash)
        updateSessionOffset(off)
    }

    private fun currentOffset(): Int = layout?.offsetForPage(page) ?: 0

    private fun progressText(offset: Int): String {
        val total = book?.totalChars ?: 0
        val base = chapters.getOrNull(chapterIndex)?.charStart ?: 0
        val overall = base + offset
        val p = if (total <= 0) 0f else (overall.toFloat() / total).coerceIn(0f, 1f)
        return String.format(Locale.US, "%.1f%%", p * 100f)
    }

    private fun updateSessionOffset(off: Int) {
        val overall = (chapters.getOrNull(chapterIndex)?.charStart ?: 0) + off
        if (lastOffset < 0) {
            lastOffset = overall
            return
        }
        if (overall > lastOffset) sessionChars += overall - lastOffset
        lastOffset = overall
    }

    // ------------------------------------------------------------- navigation

    private fun onTap(zone: ReaderView.Zone) {
        // 左手持有模式下翻转左右翻页区域
        val effective = if (prefs.leftHandMode) zone.opposite() else zone
        when (effective) {
            ReaderView.Zone.PREV -> if (barsVisible) hideBars() else prevPage()
            ReaderView.Zone.NEXT -> if (barsVisible) hideBars() else nextPage()
            ReaderView.Zone.CENTER -> toggleBars()
        }
    }

    private fun ReaderView.Zone.opposite(): ReaderView.Zone = when (this) {
        ReaderView.Zone.PREV -> ReaderView.Zone.NEXT
        ReaderView.Zone.NEXT -> ReaderView.Zone.PREV
        ReaderView.Zone.CENTER -> this
    }

    private fun nextPage() {
        val l = layout ?: return
        if (page < l.pageCount - 1) {
            page++
            render(true)
        } else if (chapterIndex < chapters.size - 1) {
            showChapter(chapterIndex + 1, 0, flash = true)
        }
    }

    private fun prevPage() {
        if (page > 0) {
            page--
            render(true)
        } else if (chapterIndex > 0) {
            showChapter(chapterIndex - 1, 0, flash = true, startAtLastPage = true)
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        applyBarsVisibility()
    }

    private fun hideBars() {
        if (!barsVisible) return
        barsVisible = false
        applyBarsVisibility()
    }

    private fun applyBarsVisibility() {
        val vis = if (barsVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = vis
        binding.bottomBar.visibility = vis
    }

    // ------------------------------------------------------------------ save

    private fun saveReadingState() {
        if (book == null) return
        val l = layout ?: return
        val off = currentOffset()
        val overall = (chapters.getOrNull(chapterIndex)?.charStart ?: 0) + off
        val atEnd = chapterIndex >= chapters.size - 1 && page >= l.pageCount - 1
        repo.savePosition(bookId, chapterIndex, overall, atEnd)
        val now = System.currentTimeMillis()
        repo.recordReading(bookId, now - sessionStartMs, sessionChars)
        sessionStartMs = now
        sessionChars = 0
        lastOffset = -1
    }

    // --------------------------------------------------------------- dialogs

    private fun showMetaDialog() {
        val b = book ?: return
        val sizeMb = String.format(Locale.US, "%.2f MB", b.fileSize / 1024f / 1024f)
        val msg = buildString {
            append("书名：").append(b.title).append('\n')
            append("作者：").append(b.author).append('\n')
            append("格式：").append(b.format.uppercase()).append('\n')
            append("大小：").append(sizeMb).append('\n')
            append("章节：").append(b.chapterCount).append('\n')
            append("路径：").append(b.filePath)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.book_meta)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTocDialog() {
        if (tocItems.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.menu_toc)
                .setMessage(R.string.toc_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val adapter = TocAdapter(this, tocItems, chapterIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_toc)
            .setAdapter(adapter) { _, which ->
                val item = tocItems[which]
                val target = item.spineIndex.coerceIn(0, chapters.size - 1)
                showChapter(target, 0, flash = false, anchor = item.anchor)
                hideBars()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 点击脚注/尾注上标：解析注释内容并弹窗展示 */
    private fun onNoteTap(noteId: String, targetHref: String) {
        val resolver = noteResolver
        if (resolver == null) {
            showNoteDialog(getString(R.string.note_not_found))
            return
        }
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    resolver.resolve(targetHref, noteId)
                } catch (_: Exception) {
                    null
                }
            }
            showNoteDialog(text?.takeIf { it.isNotBlank() } ?: getString(R.string.note_not_found))
        }
    }

    private fun showNoteDialog(message: String) {
        val scroll = android.widget.ScrollView(this).apply {
            val pad = dp(20)
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(
                android.widget.TextView(this@ReaderActivity).apply {
                    text = message
                    textSize = 15f
                    setTextColor(0xFF333333.toInt())
                }
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.note_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showBrightnessDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_brightness, null)
        val seek = view.findViewById<SeekBar>(R.id.seekBrightness)
        val current = if (prefs.brightness in 0..255) prefs.brightness else 128
        seek.max = 255
        seek.progress = current
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) applyBrightness(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_brightness)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTypographyDialog() {
        val bindingTypo = DialogTypoBinding.inflate(layoutInflater)
        val snapshot = TypographySnapshot.capture(prefs)

        // 字体列表
        val fontLabels = ArrayList<String>()
        val fontValues = ArrayList<String>()
        fontLabels.add(getString(R.string.font_default)); fontValues.add(Prefs.FONT_DEFAULT)
        fontLabels.add(getString(R.string.font_serif)); fontValues.add(Prefs.FONT_SERIF)
        fontLabels.add(getString(R.string.font_sans)); fontValues.add(Prefs.FONT_SANS)
        fontLabels.add(getString(R.string.font_mono)); fontValues.add(Prefs.FONT_MONO)
        FontManager.downloadedFonts().forEach { f ->
            fontLabels.add(f.nameWithoutExtension); fontValues.add(f.absolutePath)
        }
        fontValuesHolder = fontValues
        bindingTypo.spinnerFont.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fontLabels)
        val currentFontIndex = fontValues.indexOf(prefs.fontFamily).coerceAtLeast(0)
        bindingTypo.spinnerFont.setSelection(currentFontIndex)

        bindingTypo.seekFontSize.max = 32
        bindingTypo.seekFontSize.progress = (prefs.fontSizeSp - 12f).toInt().coerceIn(0, 32)

        bindingTypo.seekLineSpacing.max = 20
        bindingTypo.seekLineSpacing.progress = ((prefs.lineSpacing - 1.0f) * 10f).toInt().coerceIn(0, 20)

        bindingTypo.seekMargin.max = 60
        bindingTypo.seekMargin.progress = prefs.marginDp.coerceIn(0, 60)

        bindingTypo.switchIndent.isChecked = prefs.indentFirstLine
        bindingTypo.switchJustify.isChecked = prefs.justify

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_typography)
            .setView(bindingTypo.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                snapshot.restore(prefs)
                applyTypographyPreview()
            }
            .create()

        // 初值设置完成后再挂监听，实时预览排版效果
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) applyTypographyControls(bindingTypo)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        bindingTypo.seekFontSize.setOnSeekBarChangeListener(seekListener)
        bindingTypo.seekLineSpacing.setOnSeekBarChangeListener(seekListener)
        bindingTypo.seekMargin.setOnSeekBarChangeListener(seekListener)
        bindingTypo.spinnerFont.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) = applyTypographyControls(bindingTypo)

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        bindingTypo.switchIndent.setOnCheckedChangeListener { _, _ ->
            applyTypographyControls(bindingTypo)
        }
        bindingTypo.switchJustify.setOnCheckedChangeListener { _, _ ->
            applyTypographyControls(bindingTypo)
        }

        dialog.setOnShowListener {
            // 不遮挡背景，方便在弹窗打开时实时看到正文变化
            dialog.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setGravity(Gravity.BOTTOM)
            }
        }
        dialog.show()
    }

    private var fontValuesHolder: List<String> = emptyList()
    private var typoReloadJob: Job? = null

    private fun applyTypographyControls(bindingTypo: DialogTypoBinding) {
        applyTypography(bindingTypo)
        applyTypographyPreview()
    }

    private fun applyTypographyPreview() {
        applyReaderPadding()
        binding.readerView.refreshMode = prefs.refreshMode
        val keepOffset = currentOffset()
        typoReloadJob?.cancel()
        typoReloadJob = lifecycleScope.launch {
            delay(TYPO_PREVIEW_DEBOUNCE_MS)
            reloadChapter(chapterIndex, keepOffset, flash = false)
        }
    }

    private fun applyTypography(bindingTypo: DialogTypoBinding) {
        val idx = bindingTypo.spinnerFont.selectedItemPosition
        if (idx in fontValuesHolder.indices) {
            prefs.fontFamily = fontValuesHolder[idx]
        }
        prefs.fontSizeSp = 12f + bindingTypo.seekFontSize.progress
        prefs.lineSpacing = 1.0f + bindingTypo.seekLineSpacing.progress / 10f
        prefs.marginDp = bindingTypo.seekMargin.progress
        prefs.indentFirstLine = bindingTypo.switchIndent.isChecked
        prefs.justify = bindingTypo.switchJustify.isChecked
    }

    /** 排版设置快照，用于取消时还原 */
    private class TypographySnapshot(
        private val fontFamily: String,
        private val fontSizeSp: Float,
        private val lineSpacing: Float,
        private val marginDp: Int,
        private val indent: Boolean,
        private val justify: Boolean
    ) {
        fun restore(p: Prefs) {
            p.fontFamily = fontFamily
            p.fontSizeSp = fontSizeSp
            p.lineSpacing = lineSpacing
            p.marginDp = marginDp
            p.indentFirstLine = indent
            p.justify = justify
        }

        companion object {
            fun capture(p: Prefs) = TypographySnapshot(
                fontFamily = p.fontFamily,
                fontSizeSp = p.fontSizeSp,
                lineSpacing = p.lineSpacing,
                marginDp = p.marginDp,
                indent = p.indentFirstLine,
                justify = p.justify
            )
        }
    }

    // ------------------------------------------------------------- brightness

    private fun applyStoredBrightness() {
        val v = prefs.brightness
        val lp = window.attributes
        lp.screenBrightness = if (v in 0..255) v / 255f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun applyBrightness(value: Int) {
        val lp = window.attributes
        lp.screenBrightness = value / 255f
        window.attributes = lp
        prefs.brightness = value
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val TYPO_PREVIEW_DEBOUNCE_MS = 80L

        fun newIntent(context: Context, bookId: Long): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
