package com.captainivy.ebookreader.data.migration

import com.captainivy.ebookreader.data.db.DbContract
import com.captainivy.ebookreader.data.epub.ChapterContentParser
import com.captainivy.ebookreader.data.epub.EpubArchive
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.repo.BookRepository
import java.io.File

/**
 * 一次性把已有书籍的字符计数从旧基准（`body.text().length`）迁移到
 * 与阅读器排版一致的新基准（解析后 TextBlock 长度），并换算已有阅读进度。
 *
 * 需在后台线程调用；单本失败（文件缺失等）不影响其它书，下次启动会重试。
 */
class CharCountMigration(
    private val repo: BookRepository,
    private val parser: ChapterContentParser = ChapterContentParser()
) {

    fun migrateIfNeeded() {
        repo.bookIdsNeedingCharMigration().forEach { migrateBook(it) }
    }

    /**
     * 迁移单本书；已迁移或无需迁移时直接返回。
     *
     * 用进程级锁串行化，避免启动时的后台迁移与打开阅读器时的按需迁移并发写同一本书。
     */
    fun migrateBook(bookId: Long) {
        synchronized(lock) {
            val book = repo.get(bookId) ?: return
            if (book.charsVersion >= DbContract.CHARS_SCHEMA_VERSION) return
            migrate(book)
        }
    }

    private fun migrate(book: Book) {
        val bookId = book.id
        val chapters = repo.chapters(bookId)
        if (chapters.isEmpty()) {
            repo.markCharLayoutCurrent(bookId)
            return
        }
        val archive = try {
            EpubArchive.open(File(book.filePath))
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val newStarts = IntArray(chapters.size)
            val newCounts = IntArray(chapters.size)
            var acc = 0
            chapters.forEachIndexed { i, chapter ->
                val html = try {
                    archive.readText(chapter.href)
                } catch (_: Exception) {
                    null
                }
                val len = if (html.isNullOrBlank()) 0 else parser.parse(html, chapter.href, null, 0).plainLength
                newStarts[i] = acc
                newCounts[i] = len
                acc += len
            }

            val oldStarts = IntArray(chapters.size) { chapters[it].charStart }
            val oldCounts = IntArray(chapters.size) { chapters[it].charCount }
            val rebased = CharLayoutRebase.rebase(
                currentChapter = book.currentChapter,
                oldOffset = book.currentOffset,
                oldMax = book.maxReadOffset,
                oldStarts = oldStarts,
                oldCounts = oldCounts,
                newStarts = newStarts,
                newCounts = newCounts,
                newTotal = acc
            )

            val updated = chapters.mapIndexed { i, chapter ->
                chapter.copy(charStart = newStarts[i], charCount = newCounts[i])
            }
            repo.applyCharLayout(
                bookId = bookId,
                chapters = updated,
                totalChars = acc,
                currentOffset = rebased.current,
                maxReadOffset = rebased.max,
                expectedLastReadAt = book.lastReadAt
            )
        } finally {
            archive.close()
        }
    }

    companion object {
        private val lock = Any()
    }
}
