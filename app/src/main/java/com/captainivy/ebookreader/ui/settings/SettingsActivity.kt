package com.captainivy.ebookreader.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
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
import com.captainivy.ebookreader.databinding.ActivitySettingsBinding
import com.captainivy.ebookreader.net.HttpUploadService
import com.captainivy.ebookreader.ui.reader.EinkRefreshController
import com.captainivy.ebookreader.ui.reader.RefreshCadence
import com.captainivy.ebookreader.util.DateUtils
import com.captainivy.ebookreader.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { App.get().prefs }
    private val repo by lazy { App.get().repository }

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
        binding.rowPort.setOnClickListener { editPort() }
        binding.btnFontDownload.setOnClickListener { showFontDialog() }
        binding.btnFontImport.setOnClickListener { pickFont.launch(arrayOf("*/*")) }

        updateHttpUi()
        updateRefreshIntervalUi()
        updateFontCount()
    }

    override fun onResume() {
        super.onResume()
        updateRefreshIntervalUi()
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

    private data class StatsData(
        val totalMs: Long,
        val chars: Int,
        val books: Int,
        val recent: List<com.captainivy.ebookreader.data.model.ReadingStat>,
        val top: List<Pair<String, Long>>
    )

    private fun loadStats() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val totalMs = repo.totalReadingMs()
                val chars = repo.totalReadChars()
                val books = repo.count()
                val recent = repo.recentDailyStats(7)
                val top = repo.topBooksByTime(5).map { (id, ms) ->
                    (repo.get(id)?.title ?: "已删除的书籍") to ms
                }
                StatsData(totalMs, chars, books, recent, top)
            }
            binding.txtStatsSummary.text = buildString {
                append(getString(R.string.stats_summary, data.books))
                append('\n')
                append(getString(R.string.stats_total_time, DateUtils.formatTime(data.totalMs)))
                append('\n')
                append(getString(R.string.stats_total_chars, data.chars))
            }
            renderRecent(data.recent)
            renderTop(data.top)
        }
    }

    private fun renderRecent(recent: List<com.captainivy.ebookreader.data.model.ReadingStat>) {
        binding.statsRecentContainer.removeAllViews()
        if (recent.isEmpty()) {
            binding.statsRecentContainer.addView(smallText(getString(R.string.stats_none)))
            return
        }
        recent.forEach {
            binding.statsRecentContainer.addView(
                smallText("${it.day}    ${DateUtils.formatTime(it.readMs)} · ${it.chars} 字")
            )
        }
    }

    private fun renderTop(top: List<Pair<String, Long>>) {
        binding.statsTopContainer.removeAllViews()
        if (top.isEmpty()) {
            binding.statsTopContainer.addView(smallText(getString(R.string.stats_none)))
            return
        }
        top.forEach {
            binding.statsTopContainer.addView(
                smallText("${it.first}    ${DateUtils.formatTime(it.second)}")
            )
        }
    }

    private fun smallText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF666666.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
    }
}
