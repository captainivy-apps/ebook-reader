package com.captainivy.ebookreader.ui.reader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
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
import com.captainivy.ebookreader.data.migration.CharCountMigration
import com.captainivy.ebookreader.data.tts.TtsController
import com.captainivy.ebookreader.data.tts.TtsPlaybackService
import com.captainivy.ebookreader.data.tts.TtsProgress
import com.captainivy.ebookreader.data.tts.TtsSegment
import com.captainivy.ebookreader.data.tts.TtsSegmenter
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.TocItem
import com.captainivy.ebookreader.databinding.ActivityReaderBinding
import com.captainivy.ebookreader.databinding.DialogTypoBinding
import com.captainivy.ebookreader.ui.reader.layout.ChapterLayout
import com.captainivy.ebookreader.ui.reader.layout.LayoutConfig
import com.captainivy.ebookreader.ui.reader.layout.Paginator
import com.captainivy.ebookreader.util.Bitmaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val repo by lazy { App.get().repository }
    private val prefs by lazy { App.get().prefs }
    private val charMigration by lazy { CharCountMigration(repo) }

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

    private var sessionStartElapsed = 0L
    private var sessionActive = false
    private var progress: ReadingProgress? = null

    private val tts by lazy { TtsController(this) }
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var ttsJob: Job? = null
    private var ttsRunning = false
    private var ttsPaused = false
    private var ttsLoading = false
    private var ttsSpeed = 1.0f
    private var ttsChapter = -1
    private var ttsSegments: List<TtsSegment> = emptyList()
    private var ttsSegmentIndex = 0
    private var ttsSession = 0
    private var ttsPanelCollapsed = false
    private var ttsCollapseJob: Job? = null
    private var ttsPlayingSegment: TtsSegment? = null
    private var ttsPlaybackFraction = 0f
    private var ttsLastProgressFraction = 0f
    private var ttsProgressToken = 0
    private var ttsTrackProgress = false
    private var ttsIsSystemEngine = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var playbackService: TtsPlaybackService? = null
    private var playbackBound = false
    private var pendingPlaybackState: TtsPlaybackService.PlaybackState? = null

    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TtsPlaybackService.LocalBinder)?.service() ?: return
            playbackService = service
            service.onCommand = ::handlePlaybackCommand
            pendingPlaybackState?.let { service.updateState(it) }
            pendingPlaybackState = null
            currentPlaybackState()?.let { service.updateState(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService?.onCommand = null
            playbackService = null
        }
    }

    /** 位置增量落库任务（翻页后去抖合并） */
    private var persistJob: Job? = null

    /** 位置写入串行化：seq 越大越新，避免去抖任务用旧值覆盖新值 */
    private val positionLock = Any()
    private var positionSeq = 0L
    private var writtenSeq = 0L

    /** 正在从数据库对齐位置期间，暂时禁止本实例写库，避免用旧位置覆盖 */
    private var syncInProgress = false

    /** 本实例最后一次写入/加载的位置时间戳（对应 books.last_read_at），用于判断库中是否有更新的写入 */
    @Volatile
    private var lastWrittenAt = 0L

    private data class PositionSnapshot(
        val chapter: Int,
        val offset: Int,
        val maxReadOffset: Int,
        val finished: Boolean,
        val capturedAt: Long
    )

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var batteryPercent = -1
    private var statusReceiverRegistered = false
    private var clockJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            batteryPercent = parseBatteryPercent(intent)
            updateStatusBar()
        }
    }

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
        applyEinkPageBackground()
        binding.readerView.onTapListener = ::onTap
        binding.readerView.onNoteTapListener = ::onNoteTap
        binding.readerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onViewLayout() }
        setupBars()
        tts.onAutoPaused = {
            if (ttsRunning && !ttsPaused) pauseTts()
        }
        loadBook()
    }

    override fun onResume() {
        super.onResume()
        claimActiveReader()
        applyLeftHandIndicator()
        startSessionIfReady()
        startStatusUpdates()
        syncPositionFromStore()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 用户按 Home/最近任务离开时尽早落库（比 onPause 更可靠的时机） */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        flushReadingState()
    }

    override fun onPause() {
        super.onPause()
        // 熄屏、按 Home 或切换应用都不打断朗读：前台服务会保活并提供媒体控制。
        stopStatusUpdates()
        flushReadingState()
    }

    override fun onStop() {
        super.onStop()
        // 兜底：即使 onPause 因设备休眠等原因未可靠执行，也再落一次位置
        flushReadingState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newBookId = intent.getLongExtra(EXTRA_BOOK_ID, 0L)
        if (newBookId <= 0L || newBookId == bookId) return
        stopTts()
        setIntent(intent)
        finish()
        startActivity(newIntent(this, newBookId))
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseActiveReader()
        stopTtsPlaybackService()
        persistJob?.cancel()
        loadJob?.cancel()
        prefetchJob?.cancel()
        chapterCache.clear()
        ttsJob?.cancel()
        ttsCollapseJob?.cancel()
        tts.onProgress = null
        tts.release()
        archive?.close()
        archive = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 朗读期间（播放或暂停）：若音量键被配置为翻页键，临时改为调节音量
        if (ttsRunning && keyCode in VOLUME_KEYS &&
            (keyCode in prefs.nextPageKeys || keyCode in prefs.prevPageKeys)
        ) {
            adjustVolumeByKey(keyCode)
            return true
        }
        return when {
            keyCode in prefs.nextPageKeys -> {
                nextPage(); true
            }
            keyCode in prefs.prevPageKeys -> {
                prevPage(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** 朗读期间用音量键直接调节媒体音量，停止朗读后恢复原翻页快捷键 */
    private fun adjustVolumeByKey(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            else -> return
        }
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
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
        binding.btnTts.setOnClickListener { toggleTts() }
        binding.ttsPanel.btnTtsPlayPause.setOnClickListener { toggleTts() }
        binding.ttsPanel.btnTtsStop.setOnClickListener { stopTts() }
        binding.ttsPanel.btnTtsRewind.setOnClickListener { skipTts(-1) }
        binding.ttsPanel.btnTtsForward.setOnClickListener { skipTts(1) }
        binding.ttsPanel.btnTtsSpeed.setOnClickListener { cycleTtsSpeed() }
        binding.btnTtsCollapsed.setOnClickListener { expandTtsPanel() }
        ttsSpeed = prefs.ttsSpeed
        updateTtsPanel()
    }

    /** 墨水屏设备使用纯白正文底色，减少灰底对对比度与残影的影响 */
    private fun applyEinkPageBackground() {
        if (!einkRefresh.available) return
        binding.readerView.pageColor = Color.WHITE
        binding.root.setBackgroundColor(Color.WHITE)
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
        binding.imgLeftHand.visibility =
            if (prefs.leftHandMode && !ttsRunning) View.VISIBLE else View.GONE
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
                // 先确保本书字符基准已迁移，避免旧基准的 charStart 与新基准的页内偏移混用
                charMigration.migrateBook(bookId)
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
            progress = ReadingProgress(b.maxReadOffset)
            lastWrittenAt = b.lastReadAt ?: 0L
            startSessionIfReady()
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
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            prefs.fontSizeSp,
            resources.displayMetrics
        )
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
        anchor: String? = null,
        onRendered: (() -> Unit)? = null
    ) {
        if (index < 0 || index >= chapters.size) {
            onRendered?.invoke()
            return
        }
        val (w, h) = contentSize()
        if (w <= 0 || h <= 0) {
            pendingChapter = index
            pendingOffset = offset
            onRendered?.invoke()
            return
        }
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
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
            } finally {
                onRendered?.invoke()
            }
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
        updateSessionOffset(off, pageTurn)
        schedulePersistPosition()
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

    private fun updateSessionOffset(off: Int, countNew: Boolean) {
        val overall = (chapters.getOrNull(chapterIndex)?.charStart ?: 0) + off
        progress?.onPosition(overall, countNew)
    }

    // ------------------------------------------ active instance & resync

    /**
     * 同一本书若因双击/卡顿产生了多个阅读器实例，只允许当前前台实例写库，
     * 避免旧实例在稍后（onStop/再次返回）用内存里的旧位置覆盖新进度。
     */
    private fun claimActiveReader() {
        activeReaders[bookId] = WeakReference(this)
    }

    private fun releaseActiveReader() {
        if (activeReaders[bookId]?.get() === this) activeReaders.remove(bookId)
    }

    private fun isActiveReader(): Boolean {
        val current = activeReaders[bookId]?.get()
        return current == null || current === this
    }

    /**
     * 恢复前台时以数据库为准对齐位置：若其它实例（或更晚的写入）已推进进度，
     * 本实例跳到库中的最新位置，避免“回到很久以前”并防止后续再写回旧值。
     */
    private fun syncPositionFromStore() {
        if (!bookReady || layout == null || syncInProgress) return
        if (loadJob?.isActive == true) return
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { repo.get(bookId) } ?: return@launch
            if (!bookReady || layout == null || chapters.isEmpty()) return@launch
            // 只有数据库中确实存在晚于本实例最后一次写入的记录时才对齐
            if (!ReaderResync.shouldAdopt(fresh.lastReadAt, lastWrittenAt)) return@launch
            lastWrittenAt = fresh.lastReadAt ?: 0L
            val ch = fresh.currentChapter.coerceIn(0, chapters.size - 1)
            val chapterStart = chapters.getOrNull(ch)?.charStart ?: 0
            val targetOffset = (fresh.currentOffset - chapterStart).coerceAtLeast(0)
            if (ReaderResync.isSamePosition(chapterIndex, currentOffset(), ch, targetOffset)) {
                return@launch
            }
            syncInProgress = true
            showChapter(ch, targetOffset, pageTurn = false) { syncInProgress = false }
        }
    }

    // ------------------------------------------------------------ status bar

    /** 注册电量监听并按分钟刷新右上角时间（HH:mm） */
    private fun startStatusUpdates() {
        if (!statusReceiverRegistered) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                statusReceiverRegistered = true
            } catch (_: Exception) {
            }
        }
        clockJob?.cancel()
        clockJob = lifecycleScope.launch {
            while (isActive) {
                updateStatusBar()
                // 对齐到下一个整分钟，避免无意义的频繁重绘
                val now = System.currentTimeMillis()
                delay(60_000L - now % 60_000L + 50L)
            }
        }
    }

    private fun stopStatusUpdates() {
        clockJob?.cancel()
        clockJob = null
        if (statusReceiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
            statusReceiverRegistered = false
        }
    }

    private fun updateStatusBar() {
        binding.readerView.setStatusBar(timeFormat.format(Date()), batteryPercent)
    }

    private fun parseBatteryPercent(intent: Intent?): Int {
        if (intent == null) return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return (level * 100 / scale).coerceIn(0, 100)
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
        if (isManualPageTurnBlocked()) return
        if (isSettling()) return
        if (ttsRunning && !ttsPaused) pauseTts()
        val l = layout ?: return
        if (page < l.pageCount - 1) {
            page++
            render(true)
        } else if (chapterIndex < chapters.size - 1) {
            // 章节切换是异步的，先把当前可见位置同步落库，避免此窗口内被中断而丢页
            persistCurrentPositionNow()
            showChapter(chapterIndex + 1, 0, pageTurn = true)
        }
    }

    private fun prevPage() {
        if (isManualPageTurnBlocked()) return
        if (isSettling()) return
        if (ttsRunning && !ttsPaused) pauseTts()
        if (page > 0) {
            page--
            render(true)
        } else if (chapterIndex > 0) {
            persistCurrentPositionNow()
            showChapter(chapterIndex - 1, 0, pageTurn = true, startAtLastPage = true)
        }
    }

    /** 朗读期间按设置禁止手动翻页（触摸与按键均走 nextPage/prevPage） */
    private fun isManualPageTurnBlocked(): Boolean =
        ttsRunning && prefs.ttsDisableManualPageTurn

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
        val target = if (barsVisible) dp(74) else dp(16)
        listOf(binding.ttsPanel.root, binding.btnTtsCollapsed).forEach { view ->
            val lp = view.layoutParams as? android.widget.FrameLayout.LayoutParams
            if (lp != null && lp.bottomMargin != target) {
                lp.bottomMargin = target
                view.layoutParams = lp
            }
        }
    }

    // ------------------------------------------------------------------- tts

    private fun toggleTts() {
        if (ttsRunning) {
            ttsPanelCollapsed = false
            updateTtsPanel()
        }
        when {
            !ttsRunning -> startTts()
            ttsPaused -> resumeTts()
            else -> pauseTts()
        }
    }

    private fun startTts() {
        if (ttsRunning) return
        val l = layout
        if (!bookReady || l == null) {
            toast(getString(R.string.tts_not_ready))
            return
        }
        hideBars()
        ttsRunning = true
        ttsPaused = false
        ttsLoading = true
        ttsPanelCollapsed = false
        ttsSpeed = prefs.ttsSpeed
        ttsChapter = -1
        ttsSegments = emptyList()
        ttsSegmentIndex = 0
        ttsPlayingSegment = null
        ttsPlaybackFraction = 0f
        ttsLastProgressFraction = 0f
        ttsProgressToken++
        ttsTrackProgress = false
        tts.onProgress = null
        binding.readerView.clearHighlight()
        updateTtsPanel()
        requestNotificationPermissionIfNeeded()
        startTtsPlaybackService()
        syncPlaybackState()

        val offset = currentOffset()
        val session = ++ttsSession
        ttsJob?.cancel()
        ttsJob = lifecycleScope.launch {
            try {
                val prepared = tts.prepare(prefs.ttsEngineId)
                if (session != ttsSession || !ttsRunning) return@launch
                ttsIsSystemEngine = prepared.isSystem
                prepared.fallbackMessage?.let { toast(it) }
                initTtsSegments(chapterIndex, offset, fromEnd = false)
                ttsLoading = false
                updateTtsPanel()
                syncPlaybackState()
                scheduleTtsPanelCollapse()
                runTtsLoop(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session != ttsSession) return@launch
                ttsLoading = false
                ttsRunning = false
                ttsPaused = false
                binding.readerView.clearHighlight()
                updateTtsPanel()
                stopTtsPlaybackService()
                toast(e.message ?: getString(R.string.tts_start_failed))
            }
        }
    }

    private suspend fun runTtsLoop(session: Int) {
        while (ttsRunning && session == ttsSession) {
            if (ttsPaused) {
                while (ttsPaused && ttsRunning && session == ttsSession) delay(60)
                continue
            }
            val segment = ttsSegments.getOrNull(ttsSegmentIndex)
            if (segment == null) {
                if (!advanceTtsChapter(session)) {
                    if (session == ttsSession) endTtsSession()
                    return
                }
                continue
            }
            ttsPlayingSegment = segment
            ttsPlaybackFraction = 0f
            ttsLastProgressFraction = 0f
            ttsTrackProgress = segmentCrossesPage(segment)
            ensureTtsVisible(segment)
            binding.readerView.setHighlight(segment.start, segment.end)
            val token = ++ttsProgressToken
            tts.onProgress = { fraction ->
                onTtsPlaybackProgress(segment, session, token, fraction)
            }
            tts.speak(segment.text, ttsSpeed)
            if (!ttsRunning || session != ttsSession) {
                tts.onProgress = null
                return
            }
            if (!ttsPaused) onTtsPlaybackProgress(segment, session, token, 1f)
            tts.onProgress = null
            ttsPlayingSegment = null
            ttsTrackProgress = false
            if (ttsPaused) {
                while (ttsPaused && ttsRunning && session == ttsSession) delay(60)
                continue
            }
            ttsSegmentIndex++
        }
    }

    /** 当前句子是否跨页；只有跨页句子才需要根据播放进度提前翻页 */
    private fun segmentCrossesPage(segment: TtsSegment): Boolean {
        val l = layout ?: return false
        if (ttsChapter != chapterIndex) return false
        val startPage = l.pageForOffset(segment.start)
        val endPage = l.pageForOffset((segment.end - 1).coerceAtLeast(segment.start))
        return startPage != endPage
    }

    /**
     * 播放进度回调（主线程）。
     *
     * [segment] 与 [session] 用于拒绝上一句/上一次播放遗留的异步进度，
     * 并且同一句内只允许进度单调递增、页面向前切换，避免“先到后页再回前页”。
     */
    private fun onTtsPlaybackProgress(
        segment: TtsSegment,
        session: Int,
        token: Int,
        fraction: Float
    ) {
        if (!ttsRunning || ttsPaused || !ttsTrackProgress) return
        if (token != ttsProgressToken) return
        if (session != ttsSession || ttsPlayingSegment !== segment) return
        if (ttsChapter != chapterIndex) return
        val l = layout ?: return
        val progress = fraction.coerceIn(0f, 1f)
        if (progress + 0.0001f < ttsLastProgressFraction) return
        ttsLastProgressFraction = progress
        ttsPlaybackFraction = progress
        val offset = TtsProgress.estimateOffset(segment.start, segment.end, progress)
        val target = l.pageForOffset(offset)
        if (target > page) {
            page = target
            render(pageTurn = false)
        }
    }

    private suspend fun advanceTtsChapter(session: Int): Boolean {
        val next = ttsChapter + 1
        if (next !in chapters.indices) return false
        if (!renderChapterForTts(next, 0) || session != ttsSession) return false
        initTtsSegments(next, 0, fromEnd = false)
        syncPlaybackState()
        return true
    }

    private suspend fun renderChapterForTts(index: Int, offset: Int): Boolean {
        if (index !in chapters.indices) return false
        return suspendCancellableCoroutine { cont ->
            showChapter(index, offset, pageTurn = false) {
                if (cont.isActive) cont.resume(chapterIndex == index && layout != null)
            }
        }
    }

    private fun initTtsSegments(chapter: Int, offset: Int, fromEnd: Boolean) {
        val l = layout
        ttsChapter = chapter
        if (l == null) {
            ttsSegments = emptyList()
            ttsSegmentIndex = 0
            return
        }
        val texts = l.blocks.filterIsInstance<TextBlock>().map { it.text }
        val segments = TtsSegmenter.segment(texts)
        if (fromEnd) {
            ttsSegments = segments
            ttsSegmentIndex = segments.lastIndex.coerceAtLeast(0)
            return
        }
        val index = segments.indexOfFirst { it.end > offset }
        if (index < 0) {
            ttsSegments = segments
            ttsSegmentIndex = segments.size
            return
        }
        val segment = segments[index]
        if (offset > segment.start && offset < segment.end) {
            var local = offset - segment.start
            while (local < segment.text.length && segment.text[local].isWhitespace()) local++
            val newStart = segment.start + local
            val newText = segment.text.substring(local)
            if (newText.isNotEmpty()) {
                ttsSegments = segments.toMutableList().also {
                    it[index] = TtsSegment(newStart, segment.end, newText)
                }
                ttsSegmentIndex = index
                return
            }
        }
        ttsSegments = segments
        ttsSegmentIndex = index
    }

    private fun ensureTtsVisible(segment: TtsSegment) {
        val l = layout ?: return
        if (ttsChapter != chapterIndex) return
        val target = l.pageForOffset(segment.start)
        if (target != page) {
            page = target
            render(pageTurn = false)
        }
    }

    private fun skipTts(delta: Int) {
        if (!ttsRunning || ttsLoading) return
        ttsPaused = false
        ttsPlayingSegment = null
        ttsTrackProgress = false
        ttsPlaybackFraction = 0f
        ttsLastProgressFraction = 0f
        ttsProgressToken++
        tts.onProgress = null
        tts.stop()
        val session = ++ttsSession
        ttsJob?.cancel()
        ttsJob = lifecycleScope.launch {
            try {
                delay(60)
                if (session != ttsSession || !ttsRunning) return@launch
                val target = ttsSegmentIndex + delta
                when {
                    target < 0 -> {
                        val prev = ttsChapter - 1
                        if (prev >= 0 &&
                            renderChapterForTts(prev, chapters[prev].charCount)
                        ) {
                            initTtsSegments(prev, 0, fromEnd = true)
                        } else {
                            ttsSegmentIndex = 0
                        }
                    }
                    target >= ttsSegments.size -> {
                        val next = ttsChapter + 1
                        if (next in chapters.indices && renderChapterForTts(next, 0)) {
                            initTtsSegments(next, 0, fromEnd = false)
                        } else {
                            endTtsSession()
                            return@launch
                        }
                    }
                    else -> ttsSegmentIndex = target
                }
                updateTtsPanel()
                syncPlaybackState()
                scheduleTtsPanelCollapse()
                runTtsLoop(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session != ttsSession) return@launch
                endTtsSession()
                toast(e.message ?: getString(R.string.tts_start_failed))
            }
        }
    }

    private fun cycleTtsSpeed() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val index = speeds.indexOfFirst { kotlin.math.abs(it - ttsSpeed) < 0.01f }
        ttsSpeed = speeds[(index + 1).mod(speeds.size)]
        prefs.ttsSpeed = ttsSpeed
        updateTtsPanel()
        if (ttsRunning && !ttsLoading) skipTts(0)
    }

    private fun pauseTts() {
        if (!ttsRunning || ttsPaused) return
        ttsCollapseJob?.cancel()
        ttsPaused = true
        tts.pause()
        updateTtsPanel()
        syncPlaybackState()
    }

    private fun resumeTts() {
        if (!ttsRunning || !ttsPaused) return
        ttsPaused = false
        tts.resume()
        ttsSegments.getOrNull(ttsSegmentIndex)?.let { segment ->
            if (ttsIsSystemEngine) {
                ttsPlaybackFraction = 0f
                ttsProgressToken++
            }
            val l = layout
            if (l != null && ttsChapter == chapterIndex) {
                val offset = TtsProgress.estimateOffset(
                    segment.start,
                    segment.end,
                    ttsPlaybackFraction
                )
                val target = l.pageForOffset(offset)
                if (target != page) {
                    page = target
                    render(pageTurn = false)
                }
                binding.readerView.setHighlight(segment.start, segment.end)
            }
            ttsPlayingSegment = segment
            ttsLastProgressFraction = ttsPlaybackFraction
            ttsTrackProgress = segmentCrossesPage(segment)
        }
        updateTtsPanel()
        scheduleTtsPanelCollapse()
        syncPlaybackState()
    }

    private fun stopTts() {
        ttsJob?.cancel()
        ttsJob = null
        endTtsSession()
    }

    private fun endTtsSession() {
        ttsSession++
        ttsCollapseJob?.cancel()
        ttsRunning = false
        ttsPaused = false
        ttsLoading = false
        ttsPanelCollapsed = false
        ttsPlayingSegment = null
        ttsTrackProgress = false
        ttsPlaybackFraction = 0f
        ttsLastProgressFraction = 0f
        ttsProgressToken++
        tts.onProgress = null
        tts.stop()
        binding.readerView.clearHighlight()
        updateTtsPanel()
        stopTtsPlaybackService()
    }

    // ------------------------------------------------------ playback service

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: Exception) {
        }
    }

    private fun startTtsPlaybackService() {
        TtsPlaybackService.start(this)
        if (playbackBound) return
        try {
            playbackBound = bindService(
                Intent(this, TtsPlaybackService::class.java),
                playbackConnection,
                Context.BIND_AUTO_CREATE
            )
        } catch (_: Exception) {
            playbackBound = false
        }
    }

    private fun stopTtsPlaybackService() {
        val service = playbackService
        service?.onCommand = null
        if (service != null) {
            service.stopSession()
        } else if (playbackBound) {
            TtsPlaybackService.stop(this)
        }
        if (playbackBound) {
            try {
                unbindService(playbackConnection)
            } catch (_: Exception) {
            }
            playbackBound = false
        }
        playbackService = null
        pendingPlaybackState = null
    }

    private fun currentPlaybackState(): TtsPlaybackService.PlaybackState? {
        if (!ttsRunning) return null
        return TtsPlaybackService.PlaybackState(
            bookId = bookId,
            title = book?.title.orEmpty(),
            subtitle = chapters.getOrNull(ttsChapter)?.title.orEmpty(),
            playing = ttsRunning && !ttsPaused && !ttsLoading,
            paused = ttsPaused,
            loading = ttsLoading
        )
    }

    private fun syncPlaybackState() {
        val state = currentPlaybackState() ?: return
        val service = playbackService
        if (service != null) {
            service.updateState(state)
        } else {
            pendingPlaybackState = state
        }
    }

    private fun handlePlaybackCommand(command: TtsPlaybackService.Command) {
        if (isFinishing || isDestroyed) {
            if (command == TtsPlaybackService.Command.STOP) stopTtsPlaybackService()
            return
        }
        when (command) {
            TtsPlaybackService.Command.PLAY -> {
                if (ttsRunning) resumeTts() else stopTtsPlaybackService()
            }
            TtsPlaybackService.Command.PAUSE -> {
                if (ttsRunning && !ttsLoading) pauseTts()
            }
            TtsPlaybackService.Command.STOP -> {
                if (ttsRunning) stopTts() else stopTtsPlaybackService()
            }
            TtsPlaybackService.Command.PREVIOUS -> {
                if (ttsRunning) skipTts(-1)
            }
            TtsPlaybackService.Command.NEXT -> {
                if (ttsRunning) skipTts(1)
            }
        }
    }

    /** 展开悬浮控制面板，并重新开始 5 秒自动收起计时 */
    private fun expandTtsPanel() {
        if (!ttsRunning) return
        ttsPanelCollapsed = false
        updateTtsPanel()
        scheduleTtsPanelCollapse()
    }

    /** 播放中 5 秒无操作后，将控制面板收起到屏幕左侧 */
    private fun scheduleTtsPanelCollapse() {
        ttsCollapseJob?.cancel()
        if (!ttsRunning || ttsPaused || ttsLoading || ttsPanelCollapsed) return
        ttsCollapseJob = lifecycleScope.launch {
            delay(TTS_PANEL_AUTO_COLLAPSE_MS)
            collapseTtsPanel()
        }
    }

    private fun collapseTtsPanel() {
        if (!ttsRunning || ttsPaused || ttsLoading) return
        ttsPanelCollapsed = true
        updateTtsPanel()
    }

    private fun updateTtsPanel() {
        val panel = binding.ttsPanel
        val expanded = ttsRunning && !ttsPanelCollapsed
        panel.root.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnTtsCollapsed.visibility =
            if (ttsRunning && ttsPanelCollapsed) View.VISIBLE else View.GONE
        binding.imgLeftHand.visibility =
            if (!ttsRunning && prefs.leftHandMode) View.VISIBLE else View.GONE
        if (!expanded) return
        panel.btnTtsPlayPause.setImageResource(
            if (ttsPaused) R.drawable.ic_play else R.drawable.ic_pause
        )
        panel.btnTtsPlayPause.contentDescription = getString(
            if (ttsPaused) R.string.tts_resume else R.string.tts_pause
        )
        panel.btnTtsSpeed.text =
            if (ttsLoading) getString(R.string.tts_loading_short) else "${ttsSpeed}x"
        val enabled = !ttsLoading
        panel.btnTtsPlayPause.isEnabled = enabled
        panel.btnTtsRewind.isEnabled = enabled
        panel.btnTtsForward.isEnabled = enabled
        panel.btnTtsSpeed.isEnabled = enabled
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ save

    /** 仅在阅读器处于前台且书籍已就绪时开始/恢复计时 */
    private fun startSessionIfReady() {
        if (sessionActive || !bookReady) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionActive = true
    }

    /**
     * 保存阅读位置并记录本次前台阅读时长与新内容字数。
     *
     * 位置保存不再依赖统计会话是否已开始：即使 [sessionActive] 为 false，
     * 只要排版已就绪就落库，避免“统计没开始 → 位置也没保存”导致重开滞后。
     */
    private fun flushReadingState() {
        val l = layout
        if (l == null) {
            sessionActive = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        val readMs = if (sessionActive) (now - sessionStartElapsed).coerceAtLeast(0) else 0L
        sessionActive = false

        persistJob?.cancel()
        val chars = progress?.takeChars() ?: 0
        persistPosition(readMs = readMs, chars = chars, sync = true)
    }

    /** 翻页/重排后按去抖节奏增量保存位置，降低进程被回收时丢页的范围 */
    private fun schedulePersistPosition() {
        persistJob?.cancel()
        persistJob = lifecycleScope.launch {
            delay(POSITION_PERSIST_DEBOUNCE_MS)
            persistPosition(readMs = 0L, chars = 0, sync = false)
        }
    }

    /** 章节切换前同步保存当前可见位置（切换涉及异步排版，存在中断窗口） */
    private fun persistCurrentPositionNow() {
        persistJob?.cancel()
        persistPosition(readMs = 0L, chars = 0, sync = true)
    }

    private fun currentPositionSnapshot(): PositionSnapshot? {
        val l = layout ?: return null
        val off = currentOffset()
        val overall = (chapters.getOrNull(chapterIndex)?.charStart ?: 0) + off
        val atEnd = chapterIndex >= chapters.size - 1 && page >= l.pageCount - 1
        val max = progress?.maxOffset ?: overall
        return PositionSnapshot(
            chapter = chapterIndex,
            offset = overall,
            maxReadOffset = if (max > overall) max else overall,
            finished = atEnd,
            capturedAt = System.currentTimeMillis()
        )
    }

    /**
     * 写入一次位置快照。用递增序号串行化，保证旧快照不会覆盖更新的位置。
     * [sync] 为 true 时在当前线程同步写入（用于 pause/stop 等必须立即持久的时机）。
     */
    private fun persistPosition(readMs: Long, chars: Int, sync: Boolean) {
        // 非当前前台实例、或正在从数据库对齐位置时，不写库，避免用旧位置覆盖新进度
        if (!isActiveReader() || syncInProgress) return
        val snapshot = currentPositionSnapshot() ?: return
        val seq = synchronized(positionLock) { ++positionSeq }
        val write = Runnable {
            synchronized(positionLock) {
                if (seq > writtenSeq) {
                    writtenSeq = seq
                    repo.saveReadingSession(
                        bookId = bookId,
                        chapter = snapshot.chapter,
                        offset = snapshot.offset,
                        maxReadOffset = snapshot.maxReadOffset,
                        finished = snapshot.finished,
                        at = snapshot.capturedAt,
                        readMs = readMs,
                        chars = chars
                    )
                    if (snapshot.capturedAt > lastWrittenAt) lastWrittenAt = snapshot.capturedAt
                }
            }
        }
        if (sync) {
            write.run()
        } else {
            lifecycleScope.launch(Dispatchers.IO) { write.run() }
        }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_toc)
            .setAdapter(adapter) { _, which ->
                stopTts()
                val item = tocItems[which]
                val target = item.spineIndex.coerceIn(0, chapters.size - 1)
                showChapter(target, 0, pageTurn = false, anchor = item.anchor)
                hideBars()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showForEink()
        // 打开目录时自动定位到当前阅读章节
        val position = currentTocPosition()
        dialog.listView?.post { dialog.listView?.setSelectionFromTop(position, dp(12)) }
    }

    /** 当前阅读章节在目录列表中的位置：优先精确匹配，否则取最靠前的所在章节 */
    private fun currentTocPosition(): Int {
        var fallback = 0
        tocItems.forEachIndexed { i, item ->
            if (item.spineIndex == chapterIndex) return i
            if (item.spineIndex < chapterIndex) fallback = i
        }
        return fallback
    }

    /** 点击脚注/尾注上标：解析注释内容并弹窗展示 */
    private fun onNoteTap(noteId: String, targetHref: String) {
        pauseTts()
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
        pauseTts()
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
            fontLabels.add(FontManager.displayName(f.name)); fontValues.add(f.absolutePath)
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

        /** 播放中控制面板自动收到屏幕左侧的时间 */
        private const val TTS_PANEL_AUTO_COLLAPSE_MS = 5000L

        /** 墨水屏快刷（无全刷）时翻页后的输入锁定时间 */
        private const val FAST_SETTLE_MS = 220L

        /** 触发全屏清刷时更长的输入锁定时间，等待清刷波形完成 */
        private const val FULL_SETTLE_MS = 700L

        private const val MAX_CACHED_CHAPTERS = 3

        private val VOLUME_KEYS = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN
        )

        /** 翻页后增量保存位置的去抖间隔 */
        private const val POSITION_PERSIST_DEBOUNCE_MS = 150L

        /** 每本书当前处于前台的阅读器实例；用于阻止重复实例用旧位置写库 */
        private val activeReaders = ConcurrentHashMap<Long, WeakReference<ReaderActivity>>()

        private val HEADING_STYLES = setOf(
            TextStyle.H1, TextStyle.H2, TextStyle.H3,
            TextStyle.H4, TextStyle.H5, TextStyle.H6
        )

        fun newIntent(context: Context, bookId: Long): Intent =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)
    }
}
