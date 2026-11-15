package com.captainivy.ebookreader.data.tts

import android.os.StatFs
import com.captainivy.ebookreader.App
import com.captainivy.ebookreader.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object TtsModelManager {

    fun dir(spec: TtsModelSpec): File =
        File(App.get().repository.storage().ttsDir, spec.id)

    fun file(spec: TtsModelSpec, localName: String): File = File(dir(spec), localName)

    fun isDownloaded(spec: TtsModelSpec): Boolean {
        val dir = dir(spec)
        return spec.files.all { f ->
            val file = File(dir, f.localName)
            file.isFile && file.length() > 0
        }
    }

    fun installedModels(): List<TtsModelSpec> =
        TtsModelCatalog.models.filter { isDownloaded(it) }

    fun installedBytes(): Long = installedModels().sumOf { spec ->
        dir(spec).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(spec: TtsModelSpec): Boolean = try {
        dir(spec).deleteRecursively()
    } catch (_: Exception) {
        false
    }
}

/** 下载进度；done/error 只会出现在终态 */
data class TtsDownloadState(
    val modelId: String,
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val done: Boolean = false,
    val error: String? = null
) {
    val percent: Int
        get() {
            if (done) return 100
            if (totalBytes <= 0L) return 0
            return ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
        }
}

/**
 * 模型下载器：下载官方 GitHub Release 压缩包（或国内加速代理），
 * 仅解压运行所需的文件。任务在应用级协程中执行，离开设置页后仍可继续。
 */
object TtsModelDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, TtsDownloadState>>(emptyMap())
    val states: StateFlow<Map<String, TtsDownloadState>> = _states.asStateFlow()

    fun isDownloading(modelId: String): Boolean = jobs.containsKey(modelId)

    fun start(spec: TtsModelSpec, source: String): Boolean {
        if (jobs.containsKey(spec.id)) return false
        pub(spec, TtsDownloadState(spec.id, spec.archiveName, 0, 1, 0L, spec.archiveBytes))
        val job = scope.launch {
            try {
                runDownload(spec, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pub(
                    spec,
                    TtsDownloadState(
                        spec.id,
                        spec.archiveName,
                        0,
                        1,
                        0L,
                        spec.archiveBytes,
                        error = e.message ?: "下载失败"
                    )
                )
            } finally {
                jobs.remove(spec.id)
            }
        }
        jobs[spec.id] = job
        return true
    }

    fun cancel(modelId: String) {
        jobs.remove(modelId)?.cancel()
        _states.value = _states.value - modelId
    }

    fun dismissResult(modelId: String) {
        val state = _states.value[modelId] ?: return
        if (state.done || state.error != null) {
            _states.value = _states.value - modelId
        }
    }

    private suspend fun runDownload(spec: TtsModelSpec, source: String) {
        val ctx = currentCoroutineContext()
        val dir = TtsModelManager.dir(spec)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建模型目录")

        val available = try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
        val needed = spec.archiveBytes + spec.extractedBytes + 20L * 1024 * 1024
        if (available < needed) {
            throw IOException("存储空间不足，请清理后重试")
        }

        val archive = File(dir, spec.archiveName + ".part")
        archive.delete()
        var total = spec.archiveBytes
        var lastError: Exception? = null
        var success = false
        for (url in TtsModelCatalog.urls(spec, source)) {
            ctx.ensureActive()
            try {
                fetch(url, archive) { written, contentLength ->
                    if (!ctx.isActive) throw CancellationException("已取消")
                    if (contentLength > 0) total = contentLength
                    pub(spec, downloadState(spec, written, total))
                }
                success = true
                break
            } catch (e: CancellationException) {
                archive.delete()
                throw e
            } catch (e: Exception) {
                archive.delete()
                lastError = e
            }
        }
        if (!success) {
            throw IOException(lastError?.message ?: "下载失败")
        }

        pub(
            spec,
            TtsDownloadState(
                spec.id,
                "正在解压模型文件…",
                1,
                1,
                total,
                total
            )
        )
        try {
            extract(spec, archive, dir, ctx) { name, index ->
                pub(
                    spec,
                    TtsDownloadState(
                        spec.id,
                        "解压：$name",
                        index,
                        spec.files.size,
                        total,
                        total
                    )
                )
            }
        } finally {
            archive.delete()
        }
        // 清理旧版本遗留的多余文件（例如已不再使用的 int8 模型）
        val wanted = spec.localFiles().toSet()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in wanted) file.delete()
        }

        if (!TtsModelManager.isDownloaded(spec)) {
            throw IOException("模型文件不完整，请重试")
        }
        pub(
            spec,
            TtsDownloadState(
                spec.id,
                spec.archiveName,
                1,
                1,
                total,
                total,
                done = true
            )
        )
    }

    private fun downloadState(
        spec: TtsModelSpec,
        downloaded: Long,
        total: Long
    ): TtsDownloadState = TtsDownloadState(
        modelId = spec.id,
        fileName = spec.archiveName,
        fileIndex = 0,
        fileCount = 1,
        downloadedBytes = downloaded.coerceAtMost(total),
        totalBytes = total.coerceAtLeast(1L)
    )

    private fun pub(spec: TtsModelSpec, state: TtsDownloadState) {
        _states.value = _states.value + (spec.id to state)
    }

    private fun extract(
        spec: TtsModelSpec,
        archive: File,
        dir: File,
        ctx: kotlin.coroutines.CoroutineContext,
        onFile: (name: String, index: Int) -> Unit
    ) {
        val wanted = spec.localFiles().toMutableSet()
        var extracted = 0
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    ctx.ensureActive()
                    if (!entry.isDirectory) {
                        val name = entry.name.substringAfterLast('/')
                        if (name in wanted) {
                            val dest = File(dir, name)
                            dest.outputStream().use { out -> tar.copyTo(out) }
                            wanted.remove(name)
                            extracted++
                            onFile(name, extracted)
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        if (wanted.isNotEmpty()) {
            throw IOException("压缩包中缺少文件：${wanted.joinToString(", ")}")
        }
    }

    private fun fetch(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 300000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) EbookReader")
        }
        conn.connect()
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?.takeIf { it > 0 } ?: 0L
            var written = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 256 * 1024) {
                            lastReport = written
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)
        } finally {
            conn.disconnect()
        }
    }
}
