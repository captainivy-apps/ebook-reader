package com.captainivy.ebookreader.data

import android.content.Context
import java.io.File
import java.util.UUID

/** 应用内部文件目录管理 */
class BookStorage(context: Context) {

    private val base: File = context.filesDir

    val booksDir: File = File(base, "books")
    val coversDir: File = File(base, "covers")
    val fontsDir: File = File(base, "fonts")

    init {
        booksDir.mkdirs()
        coversDir.mkdirs()
        fontsDir.mkdirs()
    }

    fun newBookFile(extension: String = "epub"): File =
        File(booksDir, "${UUID.randomUUID()}.$extension")

    fun newCoverFile(): File = File(coversDir, "${UUID.randomUUID()}.png")

    fun newFontFile(name: String): File = File(fontsDir, name)

    fun deleteQuietly(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
