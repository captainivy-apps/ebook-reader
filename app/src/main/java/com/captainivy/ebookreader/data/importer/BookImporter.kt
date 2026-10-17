package com.captainivy.ebookreader.data.importer

import android.content.Context
import android.net.Uri
import com.captainivy.ebookreader.data.epub.EpubArchive
import com.captainivy.ebookreader.data.epub.EpubParser
import com.captainivy.ebookreader.data.epub.TocEntry
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.TocItem
import com.captainivy.ebookreader.data.repo.BookRepository
import com.captainivy.ebookreader.util.Bitmaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 导入 epub：拷贝到内部存储 -> 解析元数据/目录/字数 -> 生成封面 -> 入库。
 */
class BookImporter(
    private val context: Context,
    private val repository: BookRepository
) {

    suspend fun importUri(uri: Uri): Long = withContext(Dispatchers.IO) {
        val storage = repository.storage()
        val dest = storage.newBookFile("epub")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("无法打开所选文件")
            importFile(dest)
        } catch (e: Exception) {
            storage.deleteQuietly(dest)
            throw e
        }
    }

    suspend fun importStream(input: InputStream, name: String?): Long = withContext(Dispatchers.IO) {
        val storage = repository.storage()
        val dest = storage.newBookFile(extensionOf(name))
        try {
            dest.outputStream().use { output -> input.copyTo(output) }
            importFile(dest, name)
        } catch (e: Exception) {
            storage.deleteQuietly(dest)
            throw e
        }
    }

    /** 阻塞式解析并入库，调用方需在 IO 线程 */
    fun importFile(file: File, fallbackTitle: String? = null): Long {
        val archive = EpubArchive.open(file)
        val data = try {
            EpubParser().parse(archive)
        } catch (e: Exception) {
            archive.close()
            throw IllegalArgumentException("解析 EPUB 失败：${e.message}")
        }

        val coverPath = try {
            extractCover(archive, data.metadata.coverHref)
        } catch (_: Exception) {
            null
        }
        archive.close()

        var start = 0
        val chapters = data.spine.map { sc ->
            val chapter = Chapter(
                bookId = 0,
                idx = sc.index,
                title = sc.title,
                href = sc.href,
                charStart = start,
                charCount = sc.charCount
            )
            start += sc.charCount
            chapter
        }
        val toc = flattenToc(data.toc)

        val book = Book(
            title = data.metadata.title.ifBlank {
                fallbackTitle?.substringBeforeLast('.')?.ifBlank { null } ?: file.nameWithoutExtension
            },
            author = data.metadata.author,
            filePath = file.absolutePath,
            coverPath = coverPath,
            format = "epub",
            fileSize = file.length(),
            addedAt = System.currentTimeMillis(),
            lastReadAt = null,
            chapterCount = data.spine.size,
            totalChars = data.totalChars,
            currentChapter = 0,
            currentOffset = 0,
            finished = false
        )
        return repository.insert(book, chapters, toc)
    }

    private fun extractCover(archive: EpubArchive, coverHref: String?): String? {
        if (coverHref.isNullOrBlank()) return null
        val bytes = archive.readBytes(coverHref) ?: return null
        val bitmap = Bitmaps.decodeSampled(bytes, 400, 600) ?: return null
        val file = repository.storage().newCoverFile()
        val saved = try {
            Bitmaps.savePng(bitmap, file)
        } finally {
            // 及时回收，避免批量导入时位图堆积占用内存
            bitmap.recycle()
        }
        return if (saved) file.absolutePath else null
    }

    private fun flattenToc(entries: List<TocEntry>): List<TocItem> {
        val out = ArrayList<TocItem>()
        var order = 0
        fun walk(list: List<TocEntry>) {
            for (e in list) {
                out.add(
                    TocItem(
                        bookId = 0,
                        order = order++,
                        title = e.title,
                        level = e.level,
                        spineIndex = e.spineIndex.coerceAtLeast(0),
                        anchor = e.anchor
                    )
                )
                walk(e.children)
            }
        }
        walk(entries)
        return out
    }

    private fun extensionOf(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return if (ext.isNullOrBlank()) "epub" else ext
    }
}
