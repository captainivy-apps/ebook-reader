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
import com.captainivy.ebookreader.data.model.DailyReadingStat
import com.captainivy.ebookreader.data.model.StatsSummary
import com.captainivy.ebookreader.data.model.TocItem
import com.captainivy.ebookreader.data.model.TopBook

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

    fun updateReadingPosition(
        bookId: Long,
        chapter: Int,
        offset: Int,
        maxReadOffset: Int,
        finished: Boolean,
        at: Long
    ) {
        val values = ContentValues().apply {
            put(Books.CURRENT_CHAPTER, chapter)
            put(Books.CURRENT_OFFSET, offset)
            put(Books.MAX_READ_OFFSET, maxReadOffset.coerceAtLeast(offset))
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
            // 子表通过 book_id 关联删除（不能误用各表自身的 _id）。
            // 阅读统计保留：删书不应抹掉历史阅读时长与字数。
            database.delete(Chapters.TABLE, "${Chapters.BOOK_ID} IN ($placeholders)", args)
            database.delete(Toc.TABLE, "${Toc.BOOK_ID} IN ($placeholders)", args)
            database.delete(Books.TABLE, "${Books.ID} IN ($placeholders)", args)
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

    /** 原子保存阅读位置与本次阅读统计 */
    fun saveReadingSession(
        bookId: Long,
        chapter: Int,
        offset: Int,
        maxReadOffset: Int,
        finished: Boolean,
        at: Long,
        day: String,
        readMs: Long,
        chars: Int
    ) {
        val database = db
        database.beginTransaction()
        try {
            updateReadingPosition(bookId, chapter, offset, maxReadOffset, finished, at)
            upsertReadingStat(database, bookId, day, readMs, chars)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun upsertReadingStat(
        database: SQLiteDatabase,
        bookId: Long,
        day: String,
        readMs: Long,
        chars: Int
    ) {
        val ms = readMs.coerceAtLeast(0)
        val ch = chars.coerceAtLeast(0)
        if (ms == 0L && ch == 0) return
        val exists = database.query(
            Stats.TABLE, arrayOf(Stats.ID),
            "${Stats.BOOK_ID}=? AND ${Stats.DAY}=?",
            arrayOf(bookId.toString(), day), null, null, null
        ).use { it.moveToFirst() }
        if (exists) {
            database.execSQL(
                "UPDATE ${Stats.TABLE} SET ${Stats.READ_MS}=${Stats.READ_MS}+?, " +
                    "${Stats.CHARS}=${Stats.CHARS}+? WHERE ${Stats.BOOK_ID}=? AND ${Stats.DAY}=?",
                arrayOf<Any>(ms, ch, bookId, day)
            )
        } else {
            val values = ContentValues().apply {
                put(Stats.BOOK_ID, bookId)
                put(Stats.DAY, day)
                put(Stats.READ_MS, ms)
                put(Stats.CHARS, ch)
            }
            database.insertOrThrow(Stats.TABLE, null, values)
        }
    }

    fun statsSummary(): StatsSummary =
        db.rawQuery(
            """
            SELECT COALESCE(SUM(${Stats.READ_MS}),0) AS ms,
                   COALESCE(SUM(${Stats.CHARS}),0) AS ch,
                   COUNT(DISTINCT ${Stats.DAY}) AS days,
                   COUNT(DISTINCT ${Stats.BOOK_ID}) AS books
            FROM ${Stats.TABLE}
            """.trimIndent(),
            null
        ).use { c ->
            if (c.moveToFirst()) {
                StatsSummary(
                    totalMs = c.getLong(0),
                    totalChars = c.getInt(1),
                    dayCount = c.getInt(2),
                    bookCount = c.getInt(3)
                )
            } else {
                StatsSummary(0, 0, 0, 0)
            }
        }

    fun dailyStatsSince(sinceDay: String): List<DailyReadingStat> =
        db.rawQuery(
            """
            SELECT ${Stats.DAY}, SUM(${Stats.READ_MS}) AS ms,
                   SUM(${Stats.CHARS}) AS ch, COUNT(DISTINCT ${Stats.BOOK_ID}) AS books
            FROM ${Stats.TABLE}
            WHERE ${Stats.DAY} >= ?
            GROUP BY ${Stats.DAY}
            ORDER BY ${Stats.DAY} DESC
            """.trimIndent(),
            arrayOf(sinceDay)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DailyReadingStat(
                            day = c.getString(0),
                            readMs = c.getLong(1),
                            chars = c.getInt(2),
                            bookCount = c.getInt(3)
                        )
                    )
                }
            }
        }

    fun topBooksByTime(limit: Int): List<TopBook> =
        db.rawQuery(
            """
            SELECT b.${Books.ID}, b.${Books.TITLE}, SUM(s.${Stats.READ_MS}) AS ms
            FROM ${Stats.TABLE} s
            JOIN ${Books.TABLE} b ON b.${Books.ID} = s.${Stats.BOOK_ID}
            GROUP BY s.${Stats.BOOK_ID}
            HAVING SUM(s.${Stats.READ_MS}) > 0
            ORDER BY ms DESC, b.${Books.TITLE} ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TopBook(
                            bookId = c.getLong(0),
                            title = c.getString(1) ?: "",
                            readMs = c.getLong(2)
                        )
                    )
                }
            }
        }

    fun clearStats() {
        db.delete(Stats.TABLE, null, null)
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
        put(Books.MAX_READ_OFFSET, maxReadOffset)
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
        maxReadOffset = getInt(getColumnIndexOrThrow(Books.MAX_READ_OFFSET)),
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
