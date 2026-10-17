package com.captainivy.ebookreader.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object Bitmaps {

    /** 按最大宽高采样解码，返回可安全使用的位图 */
    fun decodeSampled(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun decodeSampled(file: File, maxW: Int, maxH: Int): Bitmap? = try {
        file.readBytes().let { decodeSampled(it, maxW, maxH) }
    } catch (_: Exception) {
        null
    }

    private fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW <= 0 || reqH <= 0) return 1
        while ((w / sample) / 2 >= reqW && (h / sample) / 2 >= reqH) {
            sample *= 2
        }
        return sample
    }

    /** 把位图写入文件（PNG） */
    fun savePng(bitmap: Bitmap, out: File): Boolean = try {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        true
    } catch (_: Exception) {
        false
    }

    fun readStream(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            buffer.write(buf, 0, n)
        }
        return buffer.toByteArray()
    }
}
