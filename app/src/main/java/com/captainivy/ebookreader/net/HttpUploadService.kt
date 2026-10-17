package com.captainivy.ebookreader.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.captainivy.ebookreader.App
import com.captainivy.ebookreader.R
import com.captainivy.ebookreader.data.importer.BookImporter
import com.captainivy.ebookreader.ui.settings.SettingsActivity
import com.captainivy.ebookreader.util.NetworkUtils
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 局域网上传服务：提供网页上传 EPUB 文件，导入书库。
 */
class HttpUploadService : Service() {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, App.get().prefs.httpPort)
            ?: App.get().prefs.httpPort
        startForeground(NOTIF_ID, buildNotification(port))
        startServer(port)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverThread = null
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        if (running) return
        running = true
        serverThread = Thread({
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (running) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        handleClient(client)
                    } catch (_: Exception) {
                    } finally {
                        try {
                            client.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "http-upload-server").also { it.start() }
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val contentType = headers["content-type"].orEmpty()
        val body = if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)

        val output = socket.getOutputStream()
        when {
            method == "GET" && (path == "/" || path.isEmpty()) -> {
                respondHtml(output, uploadPage())
            }
            method == "POST" && path == "/upload" -> {
                val result = handleUpload(contentType, body)
                respondHtml(output, result)
            }
            else -> respond(output, "404 Not Found", "text/plain; charset=utf-8", "Not Found")
        }
    }

    private fun handleUpload(contentType: String, body: ByteArray): String {
        val boundary = contentType
            .substringAfter("boundary=", "")
            .trim()
            .trim('"')
        if (boundary.isEmpty() || body.isEmpty()) {
            return resultPage(listOf(), "上传内容为空")
        }
        val files = parseMultipart(body, boundary)
        if (files.isEmpty()) return resultPage(listOf(), "未找到文件字段")

        val importer = BookImporter(this, App.get().repository)
        val imported = ArrayList<String>()
        val failed = ArrayList<String>()
        for ((name, bytes) in files) {
            if (bytes.isEmpty()) continue
            try {
                runBlocking {
                    importer.importStream(ByteArrayInputStream(bytes), name)
                }
                imported.add(name.ifBlank { "未命名" })
            } catch (e: Exception) {
                failed.add("${name.ifBlank { "未命名" }}（${e.message}）")
            }
        }
        return resultPage(imported, failed.joinToString("；"))
    }

    private fun parseMultipart(body: ByteArray, boundary: String): List<Pair<String, ByteArray>> {
        val bodyStr = String(body, Charsets.ISO_8859_1)
        val delimiter = "--$boundary"
        val result = ArrayList<Pair<String, ByteArray>>()
        val segments = bodyStr.split(delimiter)
        for (raw in segments) {
            var seg = raw
            if (seg.startsWith("--")) break
            seg = seg.removePrefix("\r\n")
            val headerEnd = seg.indexOf("\r\n\r\n")
            if (headerEnd < 0) continue
            val headerText = seg.substring(0, headerEnd)
            var data = seg.substring(headerEnd + 4)
            if (data.endsWith("\r\n")) data = data.substring(0, data.length - 2)
            val filename = parseFilename(headerText)
            if (filename.isNullOrEmpty()) continue
            result.add(basename(filename) to data.toByteArray(Charsets.ISO_8859_1))
        }
        return result
    }

    /**
     * 解析 Content-Disposition 中的文件名，避免中文乱码：
     * 1) 优先使用 RFC 5987 的 `filename*=UTF-8''%E4%B8%AD...`；
     * 2) 否则兼容浏览器直接写入的 UTF-8 原始字节（body 按 ISO-8859-1 读取会变成乱码，这里还原）。
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
            String(plain.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        } catch (_: Exception) {
            plain
        }
    }

    /** 仅解码百分号转义（不把 '+' 当作空格，避免破坏文件名） */
    private fun percentDecode(value: String): String {
        val out = java.io.ByteArrayOutputStream()
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
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun basename(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    // ------------------------------------------------------------- http utils

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) data else data.copyOf(read)
    }

    private fun respond(
        output: OutputStream,
        status: String,
        contentType: String,
        body: String,
        extra: String = ""
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            if (extra.isNotEmpty()) append(extra)
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondHtml(output: OutputStream, body: String) =
        respond(output, "200 OK", "text/html; charset=utf-8", body)

    // ---------------------------------------------------------------- pages

    private fun page(title: String, content: String): String = """
        <!DOCTYPE html>
        <html lang="zh">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
        <style>
        body{font-family:-apple-system,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;color:#333}
        h1{font-size:20px}
        .box{border:2px dashed #bbb;border-radius:12px;padding:32px;text-align:center;margin-top:24px}
        input[type=file]{font-size:16px}
        button{margin-top:16px;padding:10px 24px;font-size:16px;border:0;border-radius:8px;background:#4a7c59;color:#fff}
        .hint{color:#888;font-size:13px;margin-top:8px}
        .ok{color:#2e7d32}
        .err{color:#c62828}
        </style>
        </head>
        <body>
        <h1>$title</h1>
        $content
        </body>
        </html>
    """.trimIndent()

    private fun uploadPage(): String = page(
        "导入电子书",
        """
        <p>选择 EPUB 文件上传到阅读器设备。</p>
        <form class="box" action="/upload" method="post" enctype="multipart/form-data">
            <input type="file" name="file" accept=".epub,application/epub+zip" multiple><br>
            <button type="submit">上传并导入</button>
            <div class="hint">仅支持 EPUB 格式</div>
        </form>
        """.trimIndent()
    )

    private fun resultPage(imported: List<String>, failed: String): String {
        val okHtml = if (imported.isEmpty()) "" else
            "<p class=\"ok\">成功导入 ${imported.size} 本：<br>${imported.joinToString("<br>")}</p>"
        val errHtml = if (failed.isBlank()) "" else "<p class=\"err\">失败：$failed</p>"
        return page(
            "上传结果",
            "$okHtml$errHtml<p><a href=\"/\">返回继续上传</a></p>"
        )
    }

    // --------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.upload_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(port: Int): Notification {
        val ip = NetworkUtils.localIpAddress() ?: "0.0.0.0"
        val intent = Intent(this, SettingsActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.upload_notification_title))
            .setContentText("http://$ip:$port")
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        const val ACTION_START = "com.captainivy.ebookreader.START_UPLOAD"
        const val ACTION_STOP = "com.captainivy.ebookreader.STOP_UPLOAD"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "http_upload"
        private const val NOTIF_ID = 1001
        private val PLAIN_FILENAME =
            Regex("filename\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        private val EXTENDED_FILENAME =
            Regex("filename\\*\\s*=\\s*([^;\\r\\n]+)", RegexOption.IGNORE_CASE)

        fun start(context: Context, port: Int) {
            val intent = Intent(context, HttpUploadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HttpUploadService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
