package com.captainivy.ebookreader.data.repo

import android.content.Context
import com.captainivy.ebookreader.data.BookStorage
import com.captainivy.ebookreader.data.db.AppDatabase
import com.captainivy.ebookreader.data.db.BookDao
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.DailyReadingStat
import com.captainivy.ebookreader.data.model.StatsSummary
import com.captainivy.ebookreader.data.model.TocItem
import com.captainivy.ebookreader.data.model.TopBook
import com.captainivy.ebookreader.util.DateUtils
import java.io.File

class BookRepository(context: Context, db: AppDatabase) {

    private val dao = BookDao(db)
    private val storage = BookStorage(context)

    fun storage(): BookStorage = storage

    // ---------------------------------------------------------------- books

    fun page(sortKey: String, ascending: Boolean, limit: Int, offset: Int): List<Book> =
        dao.queryBooks(sortKey, ascending, limit, offset)

    fun count(): Int = dao.countBooks()

    fun get(id: Long): Book? = dao.getBook(id)

    fun chapters(bookId: Long): List<Chapter> = dao.getChapters(bookId)

    fun toc(bookId: Long): List<TocItem> = dao.getToc(bookId)

    fun insert(book: Book, chapters: List<Chapter>, toc: List<TocItem>): Long =
        dao.insertBook(book, chapters, toc)

    fun saveReadingSession(
        bookId: Long,
        chapter: Int,
        offset: Int,
        maxReadOffset: Int,
        finished: Boolean,
        readMs: Long,
        chars: Int
    ) {
        dao.saveReadingSession(
            bookId = bookId,
            chapter = chapter,
            offset = offset,
            maxReadOffset = maxReadOffset,
            finished = finished,
            at = System.currentTimeMillis(),
            day = DateUtils.today(),
            readMs = readMs,
            chars = chars
        )
    }

    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        // 先删除磁盘文件
        ids.forEach { id ->
            dao.getBook(id)?.let { book ->
                storage.deleteQuietly(book.filePath.takeIf { it.isNotBlank() }?.let { File(it) })
                storage.deleteQuietly(book.coverPath?.takeIf { it.isNotBlank() }?.let { File(it) })
            }
        }
        dao.deleteBooks(ids)
    }

    // ---------------------------------------------------------------- stats

    fun statsSummary(): StatsSummary = dao.statsSummary()

    fun dailyStatsSince(sinceDay: String): List<DailyReadingStat> = dao.dailyStatsSince(sinceDay)

    fun topBooksByTime(limit: Int): List<TopBook> = dao.topBooksByTime(limit)

    fun clearStats() = dao.clearStats()
}
