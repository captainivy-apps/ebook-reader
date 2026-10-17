package com.captainivy.ebookreader.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 流式解析 multipart/form-data 请求体：边读边把每个文件字段写入临时文件，
 * 避免把整个请求体（可能包含多本电子书）一次性读入内存，从而降低峰值内存占用。
 */
class MultipartStreamReader(
    input: InputStream,
    boundary: String,
    private val tempDir: File
) {

    private val scanner = StreamScanner(input, BUFFER_SIZE)
    private val marker = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
    private val contentMarker = "\r\n--$boundary".toByteArray(StandardCharsets.ISO_8859_1)

    /**
     * 依次处理每个文件字段；回调负责读取并删除临时文件。
     * 返回处理到的文件字段数量。
     */
    fun readFiles(onFile: (name: String?, file: File) -> Unit): Int {
        if (!scanner.readUntil(marker, null)) return 0
        val first = scanner.readLine() ?: return 0
        if (first.startsWith("--")) return 0

        var count = 0
        while (true) {
            val headers = readHeaders()
            val filename = parseFilename(headers)
            val isFile = !filename.isNullOrBlank()
            val temp = if (isFile) File.createTempFile("upload_", ".part", tempDir) else null
            val out = temp?.let { FileOutputStream(it) }
            val found = try {
                scanner.readContent(contentMarker, out)
            } finally {
                out?.close()
            }
            if (temp != null) {
                onFile(filename, temp)
                count++
            }
            if (!found) break
            val closing = scanner.readLine() ?: break
            if (closing.startsWith("--")) break
        }
        return count
    }

    private fun readHeaders(): String {
        val sb = StringBuilder()
        while (true) {
            val line = scanner.readLine() ?: break
            if (line.isEmpty()) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    /**
     * 解析 Content-Disposition 中的文件名，避免中文乱码：
     * 1) 优先使用 RFC 5987 的 `filename*=UTF-8''%E4%B8%AD...`；
     * 2) 否则兼容浏览器直接写入的 UTF-8 原始字节（按 ISO-8859-1 读入后再按 UTF-8 还原）。
     */
    private fun parseFilename(headerText: String): String? {
        val extended = EXTENDED_FILENAME.find(headerText)?.groupValues?.get(1)?.trim()?.trim('"')
        if (!extended.isNullOrEmpty()) {
            val value = if (extended.contains("''")) extended.substringAfter("''") else extended
            return percentDecode(value)
        }
        val plain = PLAIN_FILENAME.find(headerText)?.groupValues?.get(1) ?: return null
        if (plain.isEmpty()) return null
        return try {
            String(plain.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            plain
        }
    }

    /** 仅解码百分号转义（不把 '+' 当作空格，避免破坏文件名） */
    private fun percentDecode(value: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.code and 0xFF)
            i++
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /**
     * 带缓冲的字节流扫描器：支持按行读取与按分隔符分片读取，内存占用与原文件大小无关。
     */
    private class StreamScanner(private val input: InputStream, size: Int) {

        private val buf = ByteArray(size)
        private var start = 0
        private var end = 0

        private fun refill(): Boolean {
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, end - start)
                end -= start
                start = 0
            }
            while (true) {
                if (end >= buf.size) return false
                val n = try {
                    input.read(buf, end, buf.size - end)
                } catch (_: IOException) {
                    -1
                }
                if (n < 0) return false
                if (n > 0) {
                    end += n
                    return true
                }
            }
        }

        private fun readByte(): Int {
            if (start >= end && !refill()) return -1
            if (start >= end) return -1
            return buf[start++].toInt() and 0xFF
        }

        fun readLine(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = readByte()
                if (b < 0) return if (out.size() == 0) null else stripCr(out)
                if (b == NL) return stripCr(out)
                out.write(b)
            }
        }

        private fun stripCr(out: ByteArrayOutputStream): String {
            val bytes = out.toByteArray()
            val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == CR) bytes.size - 1 else bytes.size
            return String(bytes, 0, len, StandardCharsets.ISO_8859_1)
        }

        /** 读取直到 pattern 出现，其间字节写入 out（可为 null 表示丢弃）；找到返回 true */
        fun readUntil(pattern: ByteArray, out: OutputStream?): Boolean {
            val m = pattern.size
            while (true) {
                if (start >= end && !refill()) {
                    flush(out, start, end)
                    return false
                }
                val idx = indexOf(buf, start, end, pattern)
                if (idx >= 0) {
                    flush(out, start, idx)
                    start = idx + m
                    return true
                }
                if (!slide(out, m)) return false
            }
        }

        /**
         * 读取文件内容直到遇到分隔符。仅当 pattern 之后紧跟 CR 或 '-' 时才判定为真正的分隔符，
         * 避免内容中恰好出现分隔符前缀时被误截断。
         */
        fun readContent(pattern: ByteArray, out: OutputStream?): Boolean {
            val m = pattern.size
            while (true) {
                if (start >= end && !refill()) {
                    flush(out, start, end)
                    return false
                }
                var needMore = false
                var i = start
                loop@ while (i + m <= end) {
                    var j = 0
                    while (j < m && buf[i + j] == pattern[j]) j++
                    if (j == m) {
                        val after = i + m
                        if (after < end) {
                            val c = buf[after]
                            if (c == CR || c == DASH) {
                                flush(out, start, i)
                                start = after
                                return true
                            }
                            i++
                            continue
                        } else {
                            // 已匹配到缓冲末尾，保留该段并补充数据以校验后继字节
                            flush(out, start, i)
                            val keep = end - i
                            System.arraycopy(buf, i, buf, 0, keep)
                            start = 0
                            end = keep
                            needMore = true
                            break@loop
                        }
                    }
                    i++
                }
                if (needMore) {
                    if (!refill()) {
                        flush(out, start, end)
                        return false
                    }
                    continue
                }
                if (!slide(out, m)) return false
            }
        }

        /** 保留末尾 (m-1) 字节，其余写出并补充新数据；补充失败返回 false */
        private fun slide(out: OutputStream?, m: Int): Boolean {
            val keep = (m - 1).coerceAtMost(end - start)
            val flushEnd = end - keep
            flush(out, start, flushEnd)
            System.arraycopy(buf, flushEnd, buf, 0, keep)
            start = 0
            end = keep
            if (!refill()) {
                flush(out, start, end)
                return false
            }
            return true
        }

        private fun flush(out: OutputStream?, from: Int, to: Int) {
            if (out != null && to > from) out.write(buf, from, to - from)
        }

        private fun indexOf(data: ByteArray, from: Int, to: Int, pattern: ByteArray): Int {
            val m = pattern.size
            var i = from
            while (i + m <= to) {
                var j = 0
                while (j < m && data[i + j] == pattern[j]) j++
                if (j == m) return i
                i++
            }
            return -1
        }

        companion object {
            private const val CR: Byte = '\r'.code.toByte()
            private const val NL = '\n'.code
            private const val DASH: Byte = '-'.code.toByte()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private val PLAIN_FILENAME =
            Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        private val EXTENDED_FILENAME =
            Regex("filename\\*\\s*=\\s*([^;\\r\\n]+)", RegexOption.IGNORE_CASE)
    }
}
