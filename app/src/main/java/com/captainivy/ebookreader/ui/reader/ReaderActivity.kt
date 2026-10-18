package com.captainivy.ebookreader.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import com.captainivy.ebookreader.data.epub.Block
import com.captainivy.ebookreader.data.epub.TextBlock
import com.captainivy.ebookreader.data.epub.TextStyle
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

    private val einkRefresh = EinkRefreshController.get()
    private val refreshCadence = RefreshCadence(prefs.refreshInterval)

    private var chapterIndex = 0
    private var layout: ChapterLayout? = null
    private var page = 0
    private var barsVisible = false
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null

    /** 章节排版缓存（含预排版的下一章），键为章节序号 */
    private var cacheSignature = ""
    private val chapterCache = object : LinkedHashMap<Int, CachedChapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedChapter>): Boolean =
            size > MAX_CACHED_CHAPTERS
    }

    private class CachedChapter(
        val signature: String,
        val layout: ChapterLayout,
        val anchors: Map<String, Int>
    )

    /** 翻页后到面板刷新完成前的输入锁定截止时间（仅墨水屏生效），避免刷新请求排队造成闪烁 */
    private var settleUntilMs = 0L

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
        einkRefresh.attach(this)
        binding.readerView.setDeviceLabel(einkRefresh.deviceSummary())
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
        prefetchJob?.cancel()
        chapterCache.clear()
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
        binding.btnRefresh.setOnClickListener { manualRefresh() }
    }

    /** 立即全屏清刷一次并重置自动全刷节奏 */
    private fun manualRefresh() {
        refreshCadence.reset()
        if (!einkRefresh.fullRefresh(binding.readerView)) {
            binding.readerView.invalidate()
        }
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
            reloadChapter(chapterIndex, currentOffset(), pageTurn = false)
        } else {
            maybeStartReading()
        }
    }

    private fun maybeStartReading() {
        if (!bookReady || layout != null) return
        if (binding.readerView.width <= 0) return
        showChapter(pendingChapter, pendingOffset, pageTurn = false)
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
        pageTurn: Boolean,
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
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val cached = loadChapter(index, w, h) ?: return@launch
            if (!isActive) return@launch
            val newLayout = cached.layout
            val anchorOffset = anchor?.let { cached.anchors[it] }
            chapterIndex = index
            layout = newLayout
            page = when {
                startAtLastPage -> newLayout.pageCount - 1
                anchorOffset != null -> newLayout.pageForOffset(anchorOffset)
                else -> newLayout.pageForOffset(offset)
            }
            render(pageTurn)
            prefetchChapter(index + 1, w, h)
        }
    }

    /** 解析并排版一章；命中缓存则直接返回。越界返回 null。 */
    private suspend fun loadChapter(index: Int, w: Int, h: Int): CachedChapter? {
        if (index < 0 || index >= chapters.size) return null
        val sig = layoutSignature(w, h)
        if (sig != cacheSignature) {
            chapterCache.clear()
            cacheSignature = sig
        }
        chapterCache[index]?.let { return it }
        val chapter = chapters[index]
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
        val cfg = layoutConfig(w, h)
        val blocks = withChapterTitle(content.blocks, chapter.title)
        val newLayout = withContext(Dispatchers.Default) {
            paginator.paginate(blocks, cfg)
        }
        val cached = CachedChapter(sig, newLayout, content.anchors)
        chapterCache[index] = cached
        return cached
    }

    /** 预排版下一章，翻到该章时直接命中缓存，消除加载空白帧与额外刷新。 */
    private fun prefetchChapter(index: Int, w: Int, h: Int) {
        if (index < 0 || index >= chapters.size) return
        val sig = layoutSignature(w, h)
        if (chapterCache[index]?.signature == sig) return
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch {
            try {
                loadChapter(index, w, h)
            } catch (_: Exception) {
            }
        }
    }

    /** 当前排版配置签名：任一排版参数变化都会使缓存失效 */
    private fun layoutSignature(w: Int, h: Int): String =
        "$w|$h|${prefs.fontFamily}|${prefs.fontSizeSp}|${prefs.lineSpacing}|" +
            "${prefs.indentFirstLine}|${prefs.justify}"

    private fun reloadChapter(index: Int, offset: Int, pageTurn: Boolean) {
        showChapter(index, offset, pageTurn)
    }

    /**
     * 让正文里的章节标题用更大的字号显示。
     *
     * 很多 EPUB（尤其中文书）把章节标题写成普通段落，排版后与正文字号无异。
     * 这里把章节开头的第一个文本块识别为标题时，提升为标题样式（H2：更大字号、加粗、居中）。
     * 仅替换样式、不改变文本长度，因此不影响阅读位置与进度的偏移映射。
     */
    private fun withChapterTitle(blocks: List<Block>, title: String): List<Block> {
        if (title.isBlank()) return blocks
        val idx = blocks.indexOfFirst { it is TextBlock }
        if (idx < 0) return blocks
        val first = blocks[idx] as TextBlock
        if (first.style in HEADING_STYLES) return blocks
        if (!titleMatches(first.text, title)) return blocks
        val promoted = TextBlock(first.text, TextStyle.H2, first.align)
        return blocks.toMutableList().also { it[idx] = promoted }
    }

    /** 判断章节首个文本块是否为标题：忽略空白后相互包含，且长度相近（避免误判正文首段） */
    private fun titleMatches(text: CharSequence, title: String): Boolean {
        val a = text.toString().filterNot { it.isWhitespace() }
        val b = title.filterNot { it.isWhitespace() }
        if (a.length < 2 || b.length < 2) return false
        if (a.length > b.length + 12) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun render(pageTurn: Boolean) {
        val l = layout ?: return
        val off = l.offsetForPage(page)
        val title = chapters.getOrNull(chapterIndex)?.title.orEmpty()
        binding.readerView.showPage(l, page, title, progressText(off))
        updateSessionOffset(off)
        if (pageTurn) {
            val full = maybeClearGhosting()
            if (einkRefresh.available) {
                settleUntilMs = SystemClock.uptimeMillis() +
                    if (full) FULL_SETTLE_MS else FAST_SETTLE_MS
            }
        }
    }

    /**
     * 翻页后按节奏清除残影：每 [Prefs.refreshInterval] 次翻页请求一次厂商全屏清刷。
     * 返回本次是否触发了全屏清刷。非墨水屏或厂商接口不可用时静默跳过。
     */
    private fun maybeClearGhosting(): Boolean {
        if (einkRefresh.available && refreshCadence.onPageTurn()) {
            einkRefresh.fullRefresh(binding.readerView)
            return true
        }
        return false
    }

    /** 墨水屏面板刷新期间忽略新的翻页输入，避免刷新请求排队导致连续闪动 */
    private fun isSettling(): Boolean =
        einkRefresh.available && SystemClock.uptimeMillis() < settleUntilMs

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
        if (isSettling()) return
        val l = layout ?: return
        if (page < l.pageCount - 1) {
            page++
            render(true)
        } else if (chapterIndex < chapters.size - 1) {
            showChapter(chapterIndex + 1, 0, pageTurn = true)
        }
    }

    private fun prevPage() {
        if (isSettling()) return
        if (page > 0) {
            page--
            render(true)
        } else if (chapterIndex > 0) {
            showChapter(chapterIndex - 1, 0, pageTurn = true, startAtLastPage = true)
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

    /** 墨水屏设备禁用弹窗动画，避免过渡动画产生多余的局部刷新与残影；普通设备不变。 */
    private fun AlertDialog.Builder.showForEink(): AlertDialog {
        val dialog = create()
        if (einkRefresh.available) dialog.window?.setWindowAnimations(0)
        dialog.show()
        return dialog
    }

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
            .showForEink()
    }

    private fun showTocDialog() {
        if (tocItems.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.menu_toc)
                .setMessage(R.string.toc_empty)
                .setPositiveButton(android.R.string.ok, null)
                .showForEink()
            return
        }
        val adapter = TocAdapter(this, tocItems, chapterIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_toc)
            .setAdapter(adapter) { _, which ->
                val item = tocItems[which]
                val target = item.spineIndex.coerceIn(0, chapters.size - 1)
                showChapter(target, 0, pageTurn = false, anchor = item.anchor)
                hideBars()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showForEink()
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
            .showForEink()
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
            .showForEink()
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
        // 墨水屏禁用弹窗滑动动画，避免过渡帧造成额外刷新
        if (einkRefresh.available) dialog.window?.setWindowAnimations(0)
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
        val keepOffset = currentOffset()
        typoReloadJob?.cancel()
        typoReloadJob = lifecycleScope.launch {
            delay(TYPO_PREVIEW_DEBOUNCE_MS)
            reloadChapter(chapterIndex, keepOffset, pageTurn = false)
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

        /** 墨水屏快刷（无全刷）时翻页后的输入锁定时间 */
        private const val FAST_SETTLE_MS = 220L

        /** 触发全屏清刷时更长的输入锁定时间，等待清刷波形完成 */
        private const val FULL_SETTLE_MS = 700L

        private const val MAX_CACHED_CHAPTERS = 3

        private val HEADING_STYLES = setOf(
            TextStyle.H1, TextStyle.H2, TextStyle.H3,
            TextStyle.H4, TextStyle.H5, TextStyle.H6
        )

        fun newIntent(context: Context, bookId: Long): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
