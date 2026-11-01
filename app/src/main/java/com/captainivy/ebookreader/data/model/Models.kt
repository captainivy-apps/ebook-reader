package com.captainivy.ebookreader.data.model

/** 书库中的一本书 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String?,
    val format: String = "epub",
    val fileSize: Long,
    val addedAt: Long,
    val lastReadAt: Long?,
    val chapterCount: Int,
    val totalChars: Int,
    val currentChapter: Int,
    val currentOffset: Int,
    /** 历史最大阅读位置（用于只统计新内容的阅读字数） */
    val maxReadOffset: Int = 0,
    val finished: Boolean
) {
    /** 阅读进度 0f..1f */
    val progress: Float
        get() = if (totalChars <= 0) 0f else (currentOffset.toFloat() / totalChars).coerceIn(0f, 1f)
}

/** 阅读章节（按 spine 顺序），用于定位与进度 */
data class Chapter(
    val id: Long = 0,
    val bookId: Long,
    val idx: Int,
    val title: String,
    val href: String,
    val charStart: Int,
    val charCount: Int
)

/** 目录条目（树状，level 从 0 开始） */
data class TocItem(
    val id: Long = 0,
    val bookId: Long,
    val order: Int,
    val title: String,
    val level: Int,
    val spineIndex: Int,
    /** 章节内锚点 id，用于跳转到章节内的具体位置 */
    val anchor: String? = null
)

/** 阅读统计汇总 */
data class StatsSummary(
    val totalMs: Long,
    val totalChars: Int,
    val dayCount: Int,
    val bookCount: Int
)

/** 某一天的阅读统计（按天聚合） */
data class DailyReadingStat(
    val day: String,
    val readMs: Long,
    val chars: Int,
    val bookCount: Int
)

/** 读得最多的书 */
data class TopBook(
    val bookId: Long,
    val title: String,
    val readMs: Long
)
