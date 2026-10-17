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

/** 阅读统计（按天、按书聚合） */
data class ReadingStat(
    val id: Long = 0,
    val bookId: Long,
    val day: String,
    val readMs: Long,
    val chars: Int
)
