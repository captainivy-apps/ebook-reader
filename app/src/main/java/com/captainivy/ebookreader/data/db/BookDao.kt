package com.captainivy.ebookreader.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.captainivy.ebookreader.data.db.DbContract.Books
import com.captainivy.ebookreader.data.db.DbContract.Chapters
import com.captainivy.ebookreader.data.db.DbContract.Stats
import com.captainivy.ebookreader.data.db.DbContract.Toc
import com.captainivy.ebookreader.data.model.Book
import com.captainivy.ebookreader.data.model.Chapter
import com.captainivy.ebookreader.data.model.ReadingStat
import com.captainivy.ebookreader.data.model.TocItem

class BookDao(private val helper: AppDatabase) {

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ---------------------------------------------------------------- books

    fun insertBook(
        book: Book,
        chapters: List<Chapter>,
        toc: List<TocItem>
    ): Long {
        val database = db
        database.beginTransaction()
        try {
            val bookId = database.insertOrThrow(Books.TABLE, null, book.toValues())
            chapters.forEach { c ->
                database.insertOrThrow(Chapters.TABLE, null, c.copy(bookId = bookId).toValues())
            }
            toc.forEach { t ->
                database.insertOrThrow(Toc.TABLE, null, t.copy(bookId = bookId).toValues())
            }
            database.setTransactionSuccessful()
            return bookId
        } finally {
            database.endTransaction()
        }
    }

    fun queryBooks(sortKey: String, ascending: Boolean, limit: Int, offset: Int): List<Book> {
        val column = if (sortKey == "added") Books.ADDED_AT else Books.LAST_READ_AT
        val direction = if (ascending) "ASC" else "DESC"
        // 最近阅读排序：已读过的书籍优先（按阅读时间排序），未读过的排在其后。
        // last_read_at 为 NULL 表示尚未打开过，不能只按 COALESCE(last_read_at, added_at)
        // 否则新导入但从未打开的书会插到最前面。
        val orderBy = if (column == Books.LAST_READ_AT) {
            "(${Books.LAST_READ_AT} IS NULL) ASC, " +
                "COALESCE(${Books.LAST_READ_AT}, ${Books.ADDED_AT}) $direction, " +
                "${Books.ID} DESC"
        } else {
            "${Books.ADDED_AT} $direction, ${Books.ID} DESC"
        }
        return db.query(
            Books.TABLE, null, null, null, null, null, orderBy, "$offset,$limit"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.toBook()) }
        }
    }

    fun countBooks(): Int =
        db.rawQuery("SELECT COUNT(*) FROM ${Books.TABLE}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun getBook(id: Long): Book? =
        db.query(Books.TABLE, null, "${Books.ID}=?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) c.toBook() else null }

    fun updateReadingPosition(bookId: Long, chapter: Int, offset: Int, finished: Boolean, at: Long) {
        val values = ContentValues().apply {
            put(Books.CURRENT_CHAPTER, chapter)
            put(Books.CURRENT_OFFSET, offset)
            put(Books.FINISHED, if (finished) 1 else 0)
            put(Books.LAST_READ_AT, at)
        }
        db.update(Books.TABLE, values, "${Books.ID}=?", arrayOf(bookId.toString()))
    }

    fun deleteBooks(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = db
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        database.beginTransaction()
        try {
            for (table in listOf(Chapters.TABLE, Toc.TABLE, Stats.TABLE, Books.TABLE)) {
                database.delete(table, "${DbContract.Books.ID} IN ($placeholders)", args)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    // ------------------------------------------------------------- chapters

    fun getChapters(bookId: Long): List<Chapter> =
        db.query(
            Chapters.TABLE, null, "${Chapters.BOOK_ID}=?", arrayOf(bookId.toString()),
            null, null, "${Chapters.IDX} ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(c.toChapter()) } }

    fun getToc(bookId: Long): List<TocItem> =
        db.query(
            Toc.TABLE, null, "${Toc.BOOK_ID}=?", arrayOf(bookId.toString()),
            null, null, "${Toc.ORDER} ASC"
        ).use { c -> buildList { while (c.moveToNext()) add(c.toToc()) } }

    // ---------------------------------------------------------------- stats

    fun addReadingStat(bookId: Long, day: String, readMs: Long, chars: Int) {
        if (readMs <= 0 && chars <= 0) return
        val database = db
        database.beginTransaction()
        try {
            val exists = database.query(
                Stats.TABLE, arrayOf(Stats.ID),
                "${Stats.BOOK_ID}=? AND ${Stats.DAY}=?",
                arrayOf(bookId.toString(), day), null, null, null
            ).use { it.moveToFirst() }
            if (exists) {
                database.execSQL(
                    "UPDATE ${Stats.TABLE} SET ${Stats.READ_MS}=${Stats.READ_MS}+?, " +
                        "${Stats.CHARS}=${Stats.CHARS}+? WHERE ${Stats.BOOK_ID}=? AND ${Stats.DAY}=?",
                    arrayOf<Any>(readMs, chars, bookId, day)
                )
            } else {
                val values = ContentValues().apply {
                    put(Stats.BOOK_ID, bookId)
                    put(Stats.DAY, day)
                    put(Stats.READ_MS, readMs)
                    put(Stats.CHARS, chars)
                }
                database.insertOrThrow(Stats.TABLE, null, values)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun totalReadingMs(): Long =
        db.rawQuery("SELECT COALESCE(SUM(${Stats.READ_MS}),0) FROM ${Stats.TABLE}", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    fun totalReadChars(): Int =
        db.rawQuery("SELECT COALESCE(SUM(${Stats.CHARS}),0) FROM ${Stats.TABLE}", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun recentDailyStats(limit: Int): List<ReadingStat> =
        db.rawQuery(
            """
            SELECT ${Stats.BOOK_ID}, ${Stats.DAY}, SUM(${Stats.READ_MS}) AS ms, SUM(${Stats.CHARS}) AS ch
            FROM ${Stats.TABLE}
            GROUP BY ${Stats.DAY}
            ORDER BY ${Stats.DAY} DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ReadingStat(
                            bookId = c.getLong(0),
                            day = c.getString(1),
                            readMs = c.getLong(2),
                            chars = c.getInt(3)
                        )
                    )
                }
            }
        }

    fun topBooksByTime(limit: Int): List<Pair<Long, Long>> =
        db.rawQuery(
            """
            SELECT ${Stats.BOOK_ID}, SUM(${Stats.READ_MS}) AS ms
            FROM ${Stats.TABLE}
            GROUP BY ${Stats.BOOK_ID}
            ORDER BY ms DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
        }

    // -------------------------------------------------------------- mapping

    private fun Book.toValues() = ContentValues().apply {
        if (id > 0) put(Books.ID, id)
        put(Books.TITLE, title)
        put(Books.AUTHOR, author)
        put(Books.FILE_PATH, filePath)
        put(Books.COVER_PATH, coverPath)
        put(Books.FORMAT, format)
        put(Books.FILE_SIZE, fileSize)
        put(Books.ADDED_AT, addedAt)
        put(Books.LAST_READ_AT, lastReadAt)
        put(Books.CHAPTER_COUNT, chapterCount)
        put(Books.TOTAL_CHARS, totalChars)
        put(Books.CURRENT_CHAPTER, currentChapter)
        put(Books.CURRENT_OFFSET, currentOffset)
        put(Books.FINISHED, if (finished) 1 else 0)
    }

    private fun Chapter.toValues() = ContentValues().apply {
        put(Chapters.BOOK_ID, bookId)
        put(Chapters.IDX, idx)
        put(Chapters.TITLE, title)
        put(Chapters.HREF, href)
        put(Chapters.CHAR_START, charStart)
        put(Chapters.CHAR_COUNT, charCount)
    }

    private fun TocItem.toValues() = ContentValues().apply {
        put(Toc.BOOK_ID, bookId)
        put(Toc.ORDER, order)
        put(Toc.TITLE, title)
        put(Toc.LEVEL, level)
        put(Toc.SPINE_INDEX, spineIndex)
        put(Toc.ANCHOR, anchor)
    }

    private fun Cursor.toBook() = Book(
        id = getLong(getColumnIndexOrThrow(Books.ID)),
        title = getString(getColumnIndexOrThrow(Books.TITLE)) ?: "",
        author = getString(getColumnIndexOrThrow(Books.AUTHOR)) ?: "",
        filePath = getString(getColumnIndexOrThrow(Books.FILE_PATH)) ?: "",
        coverPath = getString(getColumnIndexOrThrow(Books.COVER_PATH)),
        format = getString(getColumnIndexOrThrow(Books.FORMAT)) ?: "epub",
        fileSize = getLong(getColumnIndexOrThrow(Books.FILE_SIZE)),
        addedAt = getLong(getColumnIndexOrThrow(Books.ADDED_AT)),
        lastReadAt = getColumnIndexOrThrow(Books.LAST_READ_AT).let {
            if (isNull(it)) null else getLong(it)
        },
        chapterCount = getInt(getColumnIndexOrThrow(Books.CHAPTER_COUNT)),
        totalChars = getInt(getColumnIndexOrThrow(Books.TOTAL_CHARS)),
        currentChapter = getInt(getColumnIndexOrThrow(Books.CURRENT_CHAPTER)),
        currentOffset = getInt(getColumnIndexOrThrow(Books.CURRENT_OFFSET)),
        finished = getInt(getColumnIndexOrThrow(Books.FINISHED)) != 0
    )

    private fun Cursor.toChapter() = Chapter(
        id = getLong(getColumnIndexOrThrow(Chapters.ID)),
        bookId = getLong(getColumnIndexOrThrow(Chapters.BOOK_ID)),
        idx = getInt(getColumnIndexOrThrow(Chapters.IDX)),
        title = getString(getColumnIndexOrThrow(Chapters.TITLE)) ?: "",
        href = getString(getColumnIndexOrThrow(Chapters.HREF)) ?: "",
        charStart = getInt(getColumnIndexOrThrow(Chapters.CHAR_START)),
        charCount = getInt(getColumnIndexOrThrow(Chapters.CHAR_COUNT))
    )

    private fun Cursor.toToc() = TocItem(
        id = getLong(getColumnIndexOrThrow(Toc.ID)),
        bookId = getLong(getColumnIndexOrThrow(Toc.BOOK_ID)),
        order = getInt(getColumnIndexOrThrow(Toc.ORDER)),
        title = getString(getColumnIndexOrThrow(Toc.TITLE)) ?: "",
        level = getInt(getColumnIndexOrThrow(Toc.LEVEL)),
        spineIndex = getInt(getColumnIndexOrThrow(Toc.SPINE_INDEX)),
        anchor = getColumnIndex(Toc.ANCHOR).let {
            if (it < 0 || isNull(it)) null else getString(it)
        }
    )
}
