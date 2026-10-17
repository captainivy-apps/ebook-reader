package com.captainivy.ebookreader.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ${DbContract.Books.TABLE} (
                ${DbContract.Books.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${DbContract.Books.TITLE} TEXT NOT NULL,
                ${DbContract.Books.AUTHOR} TEXT,
                ${DbContract.Books.FILE_PATH} TEXT NOT NULL,
                ${DbContract.Books.COVER_PATH} TEXT,
                ${DbContract.Books.FORMAT} TEXT NOT NULL DEFAULT 'epub',
                ${DbContract.Books.FILE_SIZE} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Books.ADDED_AT} INTEGER NOT NULL,
                ${DbContract.Books.LAST_READ_AT} INTEGER,
                ${DbContract.Books.CHAPTER_COUNT} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Books.TOTAL_CHARS} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Books.CURRENT_CHAPTER} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Books.CURRENT_OFFSET} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Books.FINISHED} INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE ${DbContract.Chapters.TABLE} (
                ${DbContract.Chapters.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${DbContract.Chapters.BOOK_ID} INTEGER NOT NULL,
                ${DbContract.Chapters.IDX} INTEGER NOT NULL,
                ${DbContract.Chapters.TITLE} TEXT,
                ${DbContract.Chapters.HREF} TEXT,
                ${DbContract.Chapters.CHAR_START} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Chapters.CHAR_COUNT} INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_chapters_book_idx ON ${DbContract.Chapters.TABLE} " +
                "(${DbContract.Chapters.BOOK_ID}, ${DbContract.Chapters.IDX})"
        )

        db.execSQL(
            """
            CREATE TABLE ${DbContract.Toc.TABLE} (
                ${DbContract.Toc.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${DbContract.Toc.BOOK_ID} INTEGER NOT NULL,
                ${DbContract.Toc.ORDER} INTEGER NOT NULL,
                ${DbContract.Toc.TITLE} TEXT,
                ${DbContract.Toc.LEVEL} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Toc.SPINE_INDEX} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Toc.ANCHOR} TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE ${DbContract.Stats.TABLE} (
                ${DbContract.Stats.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                ${DbContract.Stats.BOOK_ID} INTEGER NOT NULL,
                ${DbContract.Stats.DAY} TEXT NOT NULL,
                ${DbContract.Stats.READ_MS} INTEGER NOT NULL DEFAULT 0,
                ${DbContract.Stats.CHARS} INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_stats_book_day ON ${DbContract.Stats.TABLE} " +
                "(${DbContract.Stats.BOOK_ID}, ${DbContract.Stats.DAY})"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2：目录表新增章节内锚点列，保留既有数据。
        // 旧目录未记录锚点，无法精确跳转；重新导入该书后生效。
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE ${DbContract.Toc.TABLE} ADD COLUMN " +
                    "${DbContract.Toc.ANCHOR} TEXT"
            )
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        private const val DB_NAME = "ebook_reader.db"
        private const val DB_VERSION = 2
    }
}
