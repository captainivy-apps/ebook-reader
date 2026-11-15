package com.captainivy.ebookreader.ui.reader

/**
 * 阅读器恢复前台时"以数据库为准对齐位置"的判定逻辑。
 *
 * 只有当数据库中确实存在晚于本实例最后一次写入的记录时才对齐；
 * 否则正常的 onResume（例如锁屏后返回）不会触发重排。
 */
internal object ReaderResync {

    fun shouldAdopt(freshLastReadAt: Long?, lastWrittenAt: Long): Boolean =
        (freshLastReadAt ?: 0L) > lastWrittenAt

    fun isSamePosition(
        currentChapter: Int,
        currentOffset: Int,
        targetChapter: Int,
        targetOffset: Int
    ): Boolean = currentChapter == targetChapter && currentOffset == targetOffset
}
