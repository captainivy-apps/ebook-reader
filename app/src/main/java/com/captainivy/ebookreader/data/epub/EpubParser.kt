package com.captainivy.ebookreader.data.epub

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * 解析 epub：容器 -> OPF（manifest/spine/metadata/cover） -> 目录 -> 各章节字数。
 */
class EpubParser {

    fun parse(archive: EpubArchive): EpubData {
        val containerXml = archive.readText("META-INF/container.xml")
            ?: throw IllegalArgumentException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val opfPath = parseContainer(containerXml)
            ?: throw IllegalArgumentException("不是有效的 EPUB：未找到 OPF")

        val opfXml = archive.readText(opfPath)
            ?: throw IllegalArgumentException("无法读取 OPF：$opfPath")
        val opfDir = ChapterContentParser.dirOf(opfPath)
        val opf = parseOpf(opfXml)

        val manifestById = opf.manifest.associateBy { it.id }
        val hrefToSpine = HashMap<String, Int>()
        val spineItems = ArrayList<ManifestItem>()
        opf.spineIds.forEachIndexed { index, id ->
            val item = manifestById[id] ?: return@forEachIndexed
            spineItems.add(item)
            hrefToSpine[ChapterContentParser.resolvePath(opfDir, item.href)] = index
        }

        // 目录
        val toc = TocParser(archive, opfDir, manifestById, hrefToSpine, opf.spineTocId).parse()
            ?: buildFlatToc(spineItems, archive, opfDir)

        // 每章标题映射（目录中第一个指向该章的标题）
        val titleBySpine = HashMap<Int, String>()
        fun walk(entries: List<TocEntry>) {
            for (e in entries) {
                if (e.title.isNotBlank() && !titleBySpine.containsKey(e.spineIndex)) {
                    titleBySpine[e.spineIndex] = e.title
                }
                walk(e.children)
            }
        }
        walk(toc)

        val contentParser = ChapterContentParser()
        var cumulative = 0
        val spineChapters = ArrayList<SpineChapter>(spineItems.size)
        spineItems.forEachIndexed { index, item ->
            val href = ChapterContentParser.resolvePath(opfDir, item.href)
            val xhtml = try {
                archive.readText(href).orEmpty()
            } catch (_: Exception) {
                ""
            }
            val chars = if (xhtml.isBlank()) 0 else contentParser.countChars(xhtml)
            val title = titleBySpine[index]
                ?: firstHeading(xhtml)
                ?: "第 ${index + 1} 章"
            spineChapters.add(
                SpineChapter(
                    index = index,
                    href = href,
                    title = title,
                    charCount = chars
                )
            )
            cumulative += chars
        }

        val metadata = EpubMetadata(
            title = opf.title.ifBlank { "未命名" },
            author = opf.author.ifBlank { "未知作者" },
            language = opf.language,
            coverHref = opf.coverHref?.let { ChapterContentParser.resolvePath(opfDir, it) }
        )

        return EpubData(metadata, spineChapters, toc, cumulative)
    }

    // --------------------------------------------------------------- container

    private fun parseContainer(xml: String): String? {
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val full = parser.getAttributeValue(null, "full-path")
                if (!full.isNullOrBlank()) return normalizeZipPath(full)
            }
            event = parser.next()
        }
        return null
    }

    // --------------------------------------------------------------------- OPF

    private class OpfResult(
        val manifest: List<ManifestItem>,
        val spineIds: List<String>,
        val spineTocId: String?,
        val title: String,
        val author: String,
        val language: String?,
        val coverHref: String?
    )

    private fun parseOpf(xml: String): OpfResult {
        val parser = newParser(xml)
        val manifest = ArrayList<ManifestItem>()
        val spineIds = ArrayList<String>()
        var spineTocId: String? = null
        var title = ""
        var author = ""
        var language: String? = null
        var coverId: String? = null
        var guideCoverHref: String? = null
        var inMetadata = false
        var inManifest = false
        var inSpine = false
        var inGuide = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "metadata" -> inMetadata = true
                        "manifest" -> inManifest = true
                        "spine" -> {
                            inSpine = true
                            spineTocId = parser.getAttributeValue(null, "toc")
                        }
                        "guide" -> inGuide = true
                        "item" -> if (inManifest) {
                            manifest.add(
                                ManifestItem(
                                    id = parser.getAttributeValue(null, "id").orEmpty(),
                                    href = parser.getAttributeValue(null, "href").orEmpty(),
                                    mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                                    properties = parser.getAttributeValue(null, "properties").orEmpty()
                                )
                            )
                        }
                        "itemref" -> if (inSpine) {
                            parser.getAttributeValue(null, "idref")?.let { spineIds.add(it) }
                        }
                        "meta" -> if (inMetadata) {
                            val metaName = parser.getAttributeValue(null, "name")
                            if (metaName == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                        }
                        "reference" -> if (inGuide) {
                            val type = parser.getAttributeValue(null, "type")
                            if (type == "cover") {
                                guideCoverHref = parser.getAttributeValue(null, "href")
                            }
                        }
                        else -> if (inMetadata) {
                            when {
                                name == "title" || name.endsWith(":title") ->
                                    title = parser.nextText().trim()
                                name == "creator" || name.endsWith(":creator") ->
                                    author = parser.nextText().trim()
                                name == "language" || name.endsWith(":language") ->
                                    language = parser.nextText().trim()
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "metadata" -> inMetadata = false
                    "manifest" -> inManifest = false
                    "spine" -> inSpine = false
                    "guide" -> inGuide = false
                }
            }
            event = parser.next()
        }

        val coverHref = resolveCover(manifest, coverId, guideCoverHref)

        return OpfResult(
            manifest = manifest,
            spineIds = spineIds,
            spineTocId = spineTocId,
            title = title,
            author = author,
            language = language,
            coverHref = coverHref
        )
    }

    private fun resolveCover(
        manifest: List<ManifestItem>,
        coverId: String?,
        guideCoverHref: String?
    ): String? {
        if (!coverId.isNullOrBlank()) {
            manifest.firstOrNull { it.id == coverId }?.let { return it.href }
        }
        if (!guideCoverHref.isNullOrBlank()) return guideCoverHref
        manifest.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?.let { return it.href }
        return manifest.firstOrNull { it.mediaType.startsWith("image/") }?.href
    }

    // ------------------------------------------------------------------- utils

    private fun buildFlatToc(
        spineItems: List<ManifestItem>,
        archive: EpubArchive,
        opfDir: String
    ): List<TocEntry> = spineItems.mapIndexed { index, item ->
        val href = ChapterContentParser.resolvePath(opfDir, item.href)
        val xhtml = try {
            archive.readText(href).orEmpty()
        } catch (_: Exception) {
            ""
        }
        TocEntry(
            title = firstHeading(xhtml) ?: "第 ${index + 1} 章",
            spineIndex = index,
            level = 0,
            children = emptyList()
        )
    }

    private fun firstHeading(xhtml: String): String? {
        if (xhtml.isBlank()) return null
        return try {
            val doc = org.jsoup.Jsoup.parse(xhtml)
            for (tag in listOf("h1", "h2", "h3", "title")) {
                val el = doc.selectFirst(tag) ?: continue
                val t = el.text().trim()
                if (t.isNotEmpty()) return t
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), null)
        return parser
    }

    private fun normalizeZipPath(path: String): String {
        val stack = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }
}
