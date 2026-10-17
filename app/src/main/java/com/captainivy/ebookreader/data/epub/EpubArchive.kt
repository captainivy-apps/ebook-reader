package com.captainivy.ebookreader.data.epub

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** 对 epub（zip）文件的随机访问封装 */
class EpubArchive private constructor(private val zip: ZipFile) : Closeable {

    fun readBytes(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: zip.getEntry(path.trimStart('/')) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)

    fun contains(path: String): Boolean = zip.getEntry(path) != null

    override fun close() {
        try {
            zip.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun open(file: File): EpubArchive = EpubArchive(ZipFile(file))
    }
}
