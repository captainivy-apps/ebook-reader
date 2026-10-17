package com.captainivy.ebookreader.data

import android.graphics.Typeface
import com.captainivy.ebookreader.App
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * 字体解析、目录管理与下载。
 * fontFamily 取值：default / serif / sans / mono，或字体文件的绝对路径。
 */
object FontManager {

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * 可下载字体。
     * - [directUrls]：直链（优先尝试，jsDelivr CDN，国内可访问且体积小）
     * - [archiveUrl]/[archiveEntry]：兜底压缩包（npmmirror 阿里云国内镜像，需解包单个字体文件）
     */
    data class CatalogFont(
        val id: String,
        val name: String,
        val fileName: String,
        val directUrls: List<String> = emptyList(),
        val archiveUrl: String? = null,
        val archiveEntry: String? = null
    )

    private fun jsdelivr(pkg: String, version: String, entry: String): String {
        val encoded = entry.removePrefix("/").split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://cdn.jsdelivr.net/npm/$pkg@$version/$encoded"
    }

    private fun npmmirror(pkg: String, version: String): String {
        val bare = pkg.substringAfterLast('/')
        return "https://registry.npmmirror.com/$pkg/-/$bare-$version.tgz"
    }

    private fun expoFont(
        id: String,
        name: String,
        pkg: String,
        version: String,
        entry: String,
        fileName: String? = null
    ): CatalogFont {
        val cleanEntry = entry.removePrefix("/")
        val file = fileName ?: cleanEntry.substringAfterLast('/')
        return CatalogFont(
            id = id,
            name = name,
            fileName = file,
            directUrls = listOf(jsdelivr(pkg, version, cleanEntry)),
            archiveUrl = npmmirror(pkg, version),
            archiveEntry = cleanEntry
        )
    }

    /** 内置可下载字体目录（全部提供国内可用的下载源） */
    val catalog: List<CatalogFont> = listOf(
        expoFont(
            "noto_serif_sc", "思源宋体 Noto Serif SC",
            "@expo-google-fonts/noto-serif-sc", "0.4.4", "400Regular/NotoSerifSC_400Regular.ttf"
        ),
        expoFont(
            "noto_sans_sc", "思源黑体 Noto Sans SC",
            "@expo-google-fonts/noto-sans-sc", "0.4.4", "400Regular/NotoSansSC_400Regular.ttf"
        ),
        expoFont(
            "lxgw_wenkai", "霞鹜文楷 LXGW WenKai",
            "@fontpkg/lxgw-wen-kai", "1.520.0", "LXGWWenKai-Regular.ttf"
        ),
        expoFont(
            "lxgw_wenkai_gb", "霞鹜文楷 GB LXGW WenKai GB",
            "@fontpkg/lxgw-wen-kai-gb", "1.520.0", "LXGWWenKaiGB-Regular.ttf"
        ),
        expoFont(
            "lxgw_neo_zhisong", "霞鹜新致宋 LXGW Neo ZhiSong",
            "@fontpkg/lxgw-neo-zhi-song", "0.910.0", "LXGWNeoZhiSong.ttf"
        ),
        expoFont(
            "lxgw_neoxihei", "霞鹜新晰黑 LXGW Neo XiHei",
            "@fontpkg/lxgw-neo-xi-hei", "1.110.0", "LXGWNeoXiHei.ttf"
        ),
        expoFont(
            "lxgw_zhenkai", "霞鹜臻楷 LXGW ZhenKai",
            "@fontpkg/lxgw-zhen-kai", "0.60.0", "LXGWZhenKai.ttf"
        ),
        expoFont(
            "zcool_xiaowei", "站酷小薇 ZCOOL XiaoWei",
            "@expo-google-fonts/zcool-xiaowei", "0.4.1", "400Regular/ZCOOLXiaoWei_400Regular.ttf"
        ),
        expoFont(
            "zcool_kuaile", "站酷快乐体 ZCOOL KuaiLe",
            "@expo-google-fonts/zcool-kuaile", "0.4.1", "400Regular/ZCOOLKuaiLe_400Regular.ttf"
        ),
        expoFont(
            "zcool_qingke", "站酷庆科黄油体 ZCOOL QingKe HuangYou",
            "@expo-google-fonts/zcool-qingke-huangyou", "0.4.1", "400Regular/ZCOOLQingKeHuangYou_400Regular.ttf"
        ),
        expoFont(
            "ma_shan_zheng", "马善政楷书 Ma Shan Zheng",
            "@expo-google-fonts/ma-shan-zheng", "0.4.3", "400Regular/MaShanZheng_400Regular.ttf"
        ),
        expoFont(
            "long_cang", "龙藏体 Long Cang",
            "@expo-google-fonts/long-cang", "0.4.1", "400Regular/LongCang_400Regular.ttf"
        ),
        expoFont(
            "liu_jian_mao_cao", "柳建毛草 Liu Jian Mao Cao",
            "@expo-google-fonts/liu-jian-mao-cao", "0.4.1", "400Regular/LiuJianMaoCao_400Regular.ttf"
        ),
        expoFont(
            "zhi_mang_xing", "智芒星 Zhi Mang Xing",
            "@expo-google-fonts/zhi-mang-xing", "0.4.1", "400Regular/ZhiMangXing_400Regular.ttf"
        ),
        // ---------------------------------------------------- 免费商用非开源字体
        expoFont(
            "alibaba_puhuiti", "阿里巴巴普惠体 Alibaba PuHuiTi",
            "@fontpkg/alibaba-pu-hui-ti-3-0", "3.1.2", "AlibabaPuHuiTi-3-55-Regular.ttf"
        ),
        expoFont(
            "alibaba_puhuiti_bold", "阿里巴巴普惠体 粗体 Alibaba PuHuiTi Bold",
            "@fontpkg/alibaba-pu-hui-ti-3-0", "3.1.2", "AlibabaPuHuiTi-3-85-Bold.ttf"
        ),
        expoFont(
            "harmonyos_sans_sc", "鸿蒙黑体 HarmonyOS Sans SC",
            "@fontpkg/harmony-os-sans-sc", "1.0.3", "HarmonyOS_Sans_SC_Regular.ttf"
        ),
        expoFont(
            "harmonyos_sans_sc_bold", "鸿蒙黑体 粗体 HarmonyOS Sans SC Bold",
            "@fontpkg/harmony-os-sans-sc", "1.0.3", "HarmonyOS_Sans_SC_Bold.ttf"
        ),
        expoFont(
            "misans", "MiSans 小米",
            "@fontpkg/mi-sans", "4.3.0", "MiSans-Regular.otf"
        ),
        expoFont(
            "misans_semibold", "MiSans 半粗 Xiaomi MiSans Semibold",
            "@fontpkg/mi-sans", "4.3.0", "MiSans-Semibold.otf"
        ),
        expoFont(
            "alimama_daoliti", "阿里妈妈刀隶体 Alimama DaoLiTi",
            "@fontpkg/alimama-dao-li-ti", "1.0.5", "AlimamaDaoLiTi.ttf"
        ),
        expoFont(
            "alimama_shuheiti", "阿里妈妈数黑体 Alimama ShuHeiTi",
            "@fontpkg/alimama-shu-hei-ti", "1.0.5", "AlimamaShuHeiTi-Bold.ttf"
        ),
        expoFont(
            "dingtalk_jinbuti", "钉钉进步体 DingTalk JinBuTi",
            "@fontpkg/ding-talk-jin-bu-ti", "1.0.3", "DingTalk JinBuTi.ttf", "DingTalkJinBuTi.ttf"
        ),
        expoFont(
            "qijic", "凌东齐伋复刻体 Qijic",
            "@fontpkg/qijic", "0.0.3", "凌东齐伋复刻combo.ttf", "QijicCombo.ttf"
        ),
        expoFont(
            "honglei_zhuoshu", "鸿雷拙书 HongLei ZhuoShu",
            "@fontpkg/hong-lei-zhuo-shu", "1.0.1", "鸿雷拙书简体.ttf", "HongLeiZhuoShu.ttf"
        ),
        expoFont(
            "tiejili", "铁蒺藜体 Tiejili",
            "@fontpkg/tiejili-sc", "1.100.1", "TiejiliSC-Regular.ttf"
        ),
        expoFont(
            "siyin_song", "司音宋 Siyin Song",
            "@fontpkg/siyin-song", "1.0.1", "SiyinSong-Regular.ttf"
        ),
        expoFont(
            "tangxianbin_song", "汤宪滨宋 Tang XianBin Song",
            "@fontpkg/tang-xian-bin-song", "2.0.2", "汤宪滨宋 常规.ttf", "TangXianBinSong.ttf"
        )
    )

    fun typeface(fontFamily: String): Typeface = when (fontFamily) {
        Prefs.FONT_SERIF -> Typeface.SERIF
        Prefs.FONT_SANS -> Typeface.SANS_SERIF
        Prefs.FONT_MONO -> Typeface.MONOSPACE
        Prefs.FONT_DEFAULT -> Typeface.DEFAULT
        else -> loadFile(fontFamily) ?: Typeface.DEFAULT
    }

    private fun loadFile(path: String): Typeface? = try {
        val file = File(path)
        if (file.exists()) Typeface.createFromFile(file.path) else null
    } catch (_: Exception) {
        null
    }

    /** 已下载/导入的字体文件 */
    fun downloadedFonts(): List<File> =
        App.get().repository.storage().fontsDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun isDownloaded(fileName: String): Boolean =
        File(App.get().repository.storage().fontsDir, fileName).exists()

    // -------------------------------------------------------------- download

    /**
     * 下载字体到 [fontsDir]。成功返回 null，失败返回错误信息。
     * 优先直链，失败后回退到国内镜像压缩包并解包。
     */
    fun downloadFont(font: CatalogFont, fontsDir: File): String? {
        val dest = File(fontsDir, font.fileName)
        var lastError: String? = null

        for (url in font.directUrls) {
            try {
                fetch(url, dest)
                if (isValidFont(dest)) return null
                dest.delete()
                lastError = "字体文件校验失败"
            } catch (e: Exception) {
                dest.delete()
                lastError = e.message ?: "下载失败"
            }
        }

        val archiveUrl = font.archiveUrl
        val archiveEntry = font.archiveEntry
        if (archiveUrl != null && archiveEntry != null) {
            try {
                val tmp = File.createTempFile("fontdl", ".tgz", fontsDir)
                try {
                    fetch(archiveUrl, tmp)
                    if (!extractFromTarGz(tmp, archiveEntry, dest)) {
                        throw IOException("压缩包中未找到字体文件")
                    }
                } finally {
                    tmp.delete()
                }
                if (isValidFont(dest)) return null
                dest.delete()
                lastError = "字体文件校验失败"
            } catch (e: Exception) {
                dest.delete()
                lastError = e.message ?: "下载失败"
            }
        }
        return lastError ?: "下载失败"
    }

    private fun fetch(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 180000
            instanceFollowRedirects = true
        }
        conn.connect()
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun isValidFont(file: File): Boolean = try {
        file.length() > 4096 && Typeface.createFromFile(file.path) != null
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------ tar.gz extraction

    /** 从 gzip 压缩的 tar 中提取指定文件（npm 包结构，含 package/ 前缀）。 */
    private fun extractFromTarGz(archive: File, entry: String, dest: File): Boolean {
        val target = entry.removePrefix("/")
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                if (!readFully(input, header)) return false
                if (header.all { it == 0.toByte() }) return false

                val name = readString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()

                when (typeFlag) {
                    'L' -> { // GNU 长文件名
                        val bytes = readBytes(input, size)
                        pendingLongName = String(bytes, Charsets.UTF_8).trimEnd('\u0000')
                        skip(input, padding(size))
                        continue
                    }
                    'x', 'g', '1', '2' -> { // pax / 特殊条目，跳过
                        skip(input, size + padding(size))
                        continue
                    }
                }

                val effectiveName = pendingLongName ?: name
                pendingLongName = null
                val isFile = typeFlag == '0' || typeFlag == '\u0000'
                val matches = isFile &&
                    (effectiveName == target ||
                        effectiveName == "package/$target" ||
                        effectiveName.endsWith("/$target"))

                if (matches) {
                    dest.outputStream().use { out -> copyExact(input, out, size) }
                    return true
                }
                skip(input, size + padding(size))
            }
        }
    }

    private fun padding(size: Long): Long = (512 - (size % 512)) % 512

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copyExact(input: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(16 * 1024)
        var remaining = size
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n < 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) break
            read += n
        }
        return data
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    private fun readString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        var started = false
        val limit = offset + length
        for (i in offset until limit) {
            val c = buf[i].toInt() and 0xFF
            if (c == ' '.code && !started) continue
            if (c == ' '.code || c == 0) break
            if (c in '0'.code..'7'.code) {
                started = true
                result = result * 8 + (c - '0'.code)
            }
        }
        return result
    }
}
