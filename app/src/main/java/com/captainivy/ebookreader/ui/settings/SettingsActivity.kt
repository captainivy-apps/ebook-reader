package com.captainivy.ebookreader.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.captainivy.ebookreader.App
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.data.FontManager
import com.captainivy.ebookreader.data.model.DailyReadingStat
import com.captainivy.ebookreader.data.model.StatsSummary
import com.captainivy.ebookreader.data.model.TopBook
import com.captainivy.ebookreader.databinding.ActivitySettingsBinding
import com.captainivy.ebookreader.databinding.DialogPageKeysBinding
import com.captainivy.ebookreader.databinding.ItemStatsBookBinding
import com.captainivy.ebookreader.databinding.ItemStatsDayBinding
import com.captainivy.ebookreader.databinding.ItemStatsSummaryBinding
import com.captainivy.ebookreader.net.HttpUploadService
import com.captainivy.ebookreader.ui.reader.EinkRefreshController
import com.captainivy.ebookreader.ui.reader.RefreshCadence
import com.captainivy.ebookreader.util.DateUtils
import com.captainivy.ebookreader.util.KeyNames
import com.captainivy.ebookreader.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { App.get().prefs }
    private val repo by lazy { App.get().repository }
    private var statsJob: Job? = null

    private val pickFont =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFont(it) }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) startHttp()
            else Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val einkRefresh = EinkRefreshController.get()
        einkRefresh.attach(this)
        binding.txtEinkDevice.text = einkRefresh.deviceSummary()

        binding.switchHttp.isChecked = prefs.httpEnabled
        binding.switchHttp.setOnCheckedChangeListener { _, checked -> onHttpToggled(checked) }
        binding.switchLeftHand.isChecked = prefs.leftHandMode
        binding.switchLeftHand.setOnCheckedChangeListener { _, checked ->
            prefs.leftHandMode = checked
        }
        binding.rowRefreshInterval.setOnClickListener { showRefreshIntervalDialog() }
        binding.rowNextKeys.setOnClickListener { showPageKeysDialog(PageDirection.NEXT) }
        binding.rowPrevKeys.setOnClickListener { showPageKeysDialog(PageDirection.PREV) }
        binding.rowPort.setOnClickListener { editPort() }
        binding.btnFontDownload.setOnClickListener { showFontDialog() }
        binding.btnFontImport.setOnClickListener { pickFont.launch(arrayOf("*/*")) }
        binding.btnFontUrl.setOnClickListener { showFontUrlDialog() }
        binding.btnClearStats.setOnClickListener { confirmClearStats() }

        updateHttpUi()
        updateRefreshIntervalUi()
        updatePageKeyUi()
        updateFontCount()
    }

    override fun onResume() {
        super.onResume()
        updateRefreshIntervalUi()
        updatePageKeyUi()
        updateFontCount()
        loadStats()
    }

    // ------------------------------------------------------------ http upload

    private fun onHttpToggled(checked: Boolean) {
        prefs.httpEnabled = checked
        if (checked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startHttp()
            }
        } else {
            HttpUploadService.stop(this)
        }
        updateHttpUi()
    }

    private fun startHttp() {
        HttpUploadService.start(this, prefs.httpPort)
    }

    private fun updateHttpUi() {
        binding.txtPort.text = prefs.httpPort.toString()
        if (prefs.httpEnabled) {
            val ip = NetworkUtils.localIpAddress() ?: "0.0.0.0"
            binding.txtHttpAddress.text = "http://$ip:${prefs.httpPort}\n${getString(R.string.settings_http_hint)}"
        } else {
            binding.txtHttpAddress.text = getString(R.string.settings_http_disabled)
        }
    }

    private fun editPort() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.httpPort.toString())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_http_port)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    Toast.makeText(this, R.string.port_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.httpPort = port
                if (prefs.httpEnabled) {
                    HttpUploadService.stop(this)
                    startHttp()
                }
                updateHttpUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------- e-ink refresh

    private fun updateRefreshIntervalUi() {
        binding.txtRefreshInterval.text = refreshIntervalLabel(prefs.refreshInterval)
    }

    private fun refreshIntervalLabel(interval: Int): String = when (interval) {
        RefreshCadence.OFF -> getString(R.string.refresh_interval_off)
        1 -> getString(R.string.refresh_interval_every)
        else -> getString(R.string.refresh_interval_every_n, interval)
    }

    private fun showRefreshIntervalDialog() {
        val steps = RefreshCadence.STEPS
        val labels = steps.map { refreshIntervalLabel(it) }.toTypedArray()
        val currentIndex = steps.indexOf(prefs.refreshInterval).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_refresh_interval)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.refreshInterval = steps[which]
                updateRefreshIntervalUi()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- page keys

    private enum class PageDirection { NEXT, PREV }

    private fun updatePageKeyUi() {
        binding.txtNextKeys.text = pageKeysLabel(prefs.nextPageKeys)
        binding.txtPrevKeys.text = pageKeysLabel(prefs.prevPageKeys)
    }

    private fun pageKeysLabel(keys: List<Int>): String =
        if (keys.isEmpty()) {
            getString(R.string.page_keys_none)
        } else {
            keys.joinToString("、") { KeyNames.label(this, it) }
        }

    private fun keysFor(direction: PageDirection): List<Int> =
        if (direction == PageDirection.NEXT) prefs.nextPageKeys else prefs.prevPageKeys

    private fun setKeysFor(direction: PageDirection, keys: List<Int>) {
        if (direction == PageDirection.NEXT) prefs.nextPageKeys = keys else prefs.prevPageKeys = keys
    }

    private fun directionLabel(direction: PageDirection): String =
        getString(if (direction == PageDirection.NEXT) R.string.settings_page_next else R.string.settings_page_prev)

    private fun showPageKeysDialog(direction: PageDirection) {
        val dialogBinding = DialogPageKeysBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(directionLabel(direction))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.page_keys_close, null)
            .create()

        fun refresh() {
            val keys = keysFor(direction)
            dialogBinding.keyListContainer.removeAllViews()
            dialogBinding.txtEmpty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
            keys.forEach { keyCode ->
                dialogBinding.keyListContainer.addView(keyRow(keyCode) {
                    setKeysFor(direction, keysFor(direction) - keyCode)
                    refresh()
                    updatePageKeyUi()
                })
            }
            dialogBinding.btnClearKeys.isEnabled = keys.isNotEmpty()
            dialogBinding.btnClearKeys.alpha = if (keys.isEmpty()) 0.4f else 1f
        }

        dialogBinding.btnAddKey.setOnClickListener {
            showKeyCaptureDialog(direction) {
                refresh()
                updatePageKeyUi()
            }
        }
        dialogBinding.btnClearKeys.setOnClickListener {
            setKeysFor(direction, emptyList())
            refresh()
            updatePageKeyUi()
            Toast.makeText(this, R.string.page_keys_cleared, Toast.LENGTH_SHORT).show()
        }

        refresh()
        dialog.show()
    }

    private fun keyRow(keyCode: Int, onDelete: () -> Unit): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (44 * density).toInt()
        }
        row.addView(TextView(this).apply {
            text = KeyNames.label(this@SettingsActivity, keyCode)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFF222222.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.page_keys_delete)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFB05050.toInt())
            setPadding((12 * density).toInt(), 0, 0, 0)
            setOnClickListener { onDelete() }
        })
        return row
    }

    private fun showKeyCaptureDialog(direction: PageDirection, onAdded: () -> Unit) {
        val prompt = TextView(this).apply {
            text = getString(R.string.page_keys_capture_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.page_keys_capture_title)
            .setView(prompt)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnKeyListener { d, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return@setOnKeyListener true
            if (event.repeatCount > 0) return@setOnKeyListener true
            if (KeyNames.isModifierKey(keyCode)) return@setOnKeyListener true
            if (event.isAltPressed || event.isCtrlPressed || event.isShiftPressed || event.isMetaPressed) {
                return@setOnKeyListener true
            }

            val current = keysFor(direction)
            val other = if (direction == PageDirection.NEXT) prefs.prevPageKeys else prefs.nextPageKeys
            when {
                keyCode in current -> {
                    Toast.makeText(this, R.string.page_keys_duplicate_same, Toast.LENGTH_SHORT).show()
                }
                keyCode in other -> {
                    val otherDirection =
                        if (direction == PageDirection.NEXT) PageDirection.PREV else PageDirection.NEXT
                    Toast.makeText(
                        this,
                        getString(R.string.page_keys_duplicate_other, directionLabel(otherDirection)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else -> {
                    setKeysFor(direction, current + keyCode)
                    Toast.makeText(
                        this,
                        getString(R.string.page_keys_added, KeyNames.label(this, keyCode)),
                        Toast.LENGTH_SHORT
                    ).show()
                    d.dismiss()
                    onAdded()
                }
            }
            true
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ fonts

    private fun updateFontCount() {
        val count = FontManager.downloadedFonts().size
        binding.txtFontCount.text = getString(R.string.font_count, count)
    }

    private fun showFontDialog() {
        val names = FontManager.catalog.map { f ->
            val status = if (FontManager.isDownloaded(f.fileName)) {
                getString(R.string.font_downloaded)
            } else {
                getString(R.string.font_not_downloaded)
            }
            "${f.name}  $status"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_font_download)
            .setItems(names) { _, which ->
                val font = FontManager.catalog[which]
                if (FontManager.isDownloaded(font.fileName)) {
                    confirmDeleteFont(font)
                } else {
                    downloadFont(font)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFont(font: FontManager.CatalogFont) {
        AlertDialog.Builder(this)
            .setTitle(R.string.font_delete_title)
            .setMessage(getString(R.string.font_delete_message, font.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                File(repo.storage().fontsDir, font.fileName).delete()
                updateFontCount()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadFont(font: FontManager.CatalogFont) {
        Toast.makeText(this, getString(R.string.font_downloading, font.name), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FontManager.downloadFont(font, repo.storage().fontsDir)
            }
            updateFontCount()
            if (result == null) {
                Toast.makeText(this@SettingsActivity, R.string.font_download_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showFontUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.font_url_hint)
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.font_url_title)
            .setView(container)
            .setPositiveButton(R.string.settings_font_url) { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    Toast.makeText(this, R.string.font_url_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    importFontFromUrl(url)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFontFromUrl(url: String) {
        Toast.makeText(this, R.string.font_url_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FontManager.importFromUrl(url, repo.storage().fontsDir)
            }
            updateFontCount()
            val msg = result ?: getString(R.string.font_url_success)
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFont(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val name = queryFileName(uri) ?: "font_${System.currentTimeMillis()}.ttf"
                    val dest = repo.storage().newFontFile(name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("无法读取文件")
                    null
                } catch (e: Exception) {
                    e.message ?: "导入失败"
                }
            }
            updateFontCount()
            val msg = result ?: getString(R.string.font_import_success)
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    // ------------------------------------------------------------------ stats

    private fun loadStats() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            val (summary, recent, top) = withContext(Dispatchers.IO) {
                val summary = repo.statsSummary()
                val days = DateUtils.lastNDays(7)
                val recent = repo.dailyStatsSince(days.last())
                Triple(summary, recent, repo.topBooksByTime(5))
            }
            renderStats(summary, recent, top)
        }
    }

    private fun renderStats(
        summary: StatsSummary,
        recent: List<DailyReadingStat>,
        top: List<TopBook>
    ) {
        val hasData = summary.totalMs > 0 || summary.totalChars > 0 || summary.dayCount > 0
        binding.txtStatsEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.statsSummaryContainer.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.statsRecentSection.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.statsTopSection.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.statsClearDivider.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.btnClearStats.visibility = if (hasData) View.VISIBLE else View.GONE
        if (!hasData) return

        renderSummary(summary)
        renderRecent(recent)
        renderTop(top)
    }

    private fun renderSummary(summary: StatsSummary) {
        binding.statsSummaryContainer.removeAllViews()
        addSummaryRow(
            R.string.settings_stats_total_time,
            DateUtils.formatDuration(summary.totalMs)
        )
        addDivider(binding.statsSummaryContainer)
        addSummaryRow(
            R.string.settings_stats_total_chars,
            getString(
                R.string.stats_value_chars,
                DateUtils.formatCount(summary.totalChars.toLong())
            )
        )
        addDivider(binding.statsSummaryContainer)
        addSummaryRow(
            R.string.settings_stats_days,
            getString(R.string.stats_value_days, summary.dayCount)
        )
        addDivider(binding.statsSummaryContainer)
        addSummaryRow(
            R.string.settings_stats_books,
            getString(R.string.stats_value_books, summary.bookCount)
        )
    }

    private fun addSummaryRow(labelRes: Int, value: String) {
        val item = ItemStatsSummaryBinding.inflate(
            layoutInflater,
            binding.statsSummaryContainer,
            false
        )
        item.txtLabel.setText(labelRes)
        item.txtValue.text = value
        binding.statsSummaryContainer.addView(item.root)
    }

    private fun renderRecent(recent: List<DailyReadingStat>) {
        binding.statsRecentContainer.removeAllViews()
        val byDay = recent.associateBy { it.day }
        val today = DateUtils.today()
        val days = DateUtils.lastNDays(7)
        days.forEachIndexed { index, day ->
            val stat = byDay[day]
            val item = ItemStatsDayBinding.inflate(
                layoutInflater,
                binding.statsRecentContainer,
                false
            )
            item.txtDay.text = DateUtils.formatDayLabel(day, today)
            if (stat == null || (stat.readMs <= 0 && stat.chars <= 0)) {
                item.txtDay.setTextColor(0xFF777777.toInt())
                item.txtValue.setText(R.string.stats_no_record)
                item.txtValue.setTextColor(0xFF999999.toInt())
            } else {
                item.txtDay.setTextColor(
                    if (day == today) 0xFF5B6B50.toInt() else 0xFF333333.toInt()
                )
                item.txtValue.text = getString(
                    R.string.stats_daily_value,
                    DateUtils.formatDuration(stat.readMs),
                    getString(
                        R.string.stats_value_chars,
                        DateUtils.formatCount(stat.chars.toLong())
                    )
                )
            }
            binding.statsRecentContainer.addView(item.root)
            if (index < days.lastIndex) addDivider(binding.statsRecentContainer)
        }
    }

    private fun renderTop(top: List<TopBook>) {
        binding.statsTopContainer.removeAllViews()
        if (top.isEmpty()) {
            addEmptyText(binding.statsTopContainer)
            return
        }
        top.forEachIndexed { index, book ->
            val item = ItemStatsBookBinding.inflate(
                layoutInflater,
                binding.statsTopContainer,
                false
            )
            item.txtRank.text = getString(R.string.stats_rank, index + 1)
            item.txtTitle.text = book.title
            item.txtTime.text = DateUtils.formatDuration(book.readMs)
            binding.statsTopContainer.addView(item.root)
            if (index < top.lastIndex) addDivider(binding.statsTopContainer)
        }
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(View(this).apply {
            setBackgroundColor(0xFFE6E6E6.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt()
            )
        })
    }

    private fun addEmptyText(container: LinearLayout) {
        container.addView(TextView(this).apply {
            setText(R.string.stats_none)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF999999.toInt())
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        })
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_clear_title)
            .setMessage(R.string.stats_clear_message)
            .setPositiveButton(R.string.stats_clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.clearStats() }
                    loadStats()
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.stats_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
