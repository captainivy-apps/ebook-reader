package com.captainivy.ebookreader.data.db

/** 表名与列名常量 */
object DbContract {

    object Books {
        const val TABLE = "books"
        const val ID = "_id"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val FILE_PATH = "file_path"
        const val COVER_PATH = "cover_path"
        const val FORMAT = "format"
        const val FILE_SIZE = "file_size"
        const val ADDED_AT = "added_at"
        const val LAST_READ_AT = "last_read_at"
        const val CHAPTER_COUNT = "chapter_count"
        const val TOTAL_CHARS = "total_chars"
        const val CURRENT_CHAPTER = "current_chapter"
        const val CURRENT_OFFSET = "current_offset"
        const val FINISHED = "finished"
    }

    object Chapters {
        const val TABLE = "chapters"
        const val ID = "_id"
        const val BOOK_ID = "book_id"
        const val IDX = "idx"
        const val TITLE = "title"
        const val HREF = "href"
        const val CHAR_START = "char_start"
        const val CHAR_COUNT = "char_count"
    }

    object Toc {
        const val TABLE = "toc"
        const val ID = "_id"
        const val BOOK_ID = "book_id"
        const val ORDER = "sort_order"
        const val TITLE = "title"
        const val LEVEL = "level"
        const val SPINE_INDEX = "spine_index"
        const val ANCHOR = "anchor"
    }

    object Stats {
        const val TABLE = "reading_stats"
        const val ID = "_id"
        const val BOOK_ID = "book_id"
        const val DAY = "day"
        const val READ_MS = "read_ms"
        const val CHARS = "chars"
    }
}
