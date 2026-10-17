package com.captainivy.ebookreader.data.epub

data class EpubMetadata(
    val title: String,
    val author: String,
    val language: String?,
    val coverHref: String?
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String
)

/** 目录项（树状） */
data class TocEntry(
    val title: String,
    val spineIndex: Int,
    val level: Int,
    val children: List<TocEntry>,
    /** 章节内的锚点 id（href#fragment），可为空 */
    val anchor: String? = null
)

/** 一个阅读章节（spine 顺序） */
data class SpineChapter(
    val index: Int,
    val href: String,
    val title: String,
    val charCount: Int
)

data class EpubData(
    val metadata: EpubMetadata,
    val spine: List<SpineChapter>,
    val toc: List<TocEntry>,
    val totalChars: Int
)
