package com.captainivy.ebookreader.data.repo

import android.content.Context
import com.captainivy.ebookreader.data.BookStorage
import com.captainivy.ebookreader.data.db.AppDatabase
import com.captainivy.ebookreader.data.db.BookDao
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.ReadingStat
import com.captainivy.ebookreader.data.model.TocItem
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

    fun savePosition(bookId: Long, chapter: Int, offset: Int, finished: Boolean) {
        dao.updateReadingPosition(bookId, chapter, offset, finished, System.currentTimeMillis())
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

    fun recordReading(bookId: Long, readMs: Long, chars: Int) {
        dao.addReadingStat(bookId, DateUtils.today(), readMs, chars)
    }

    fun totalReadingMs(): Long = dao.totalReadingMs()

    fun totalReadChars(): Int = dao.totalReadChars()

    fun recentDailyStats(limit: Int): List<ReadingStat> = dao.recentDailyStats(limit)

    fun topBooksByTime(limit: Int): List<Pair<Long, Long>> = dao.topBooksByTime(limit)
}
