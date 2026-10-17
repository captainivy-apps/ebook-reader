package com.captainivy.ebookreader.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * 目录解析：优先 EPUB3 nav 文档，其次 EPUB2 NCX。
 */
class TocParser(
    private val archive: EpubArchive,
    private val opfDir: String,
    private val manifestById: Map<String, ManifestItem>,
    private val hrefToSpine: Map<String, Int>,
    private val spineTocId: String?
) {

    fun parse(): List<TocEntry>? {
        val nav = parseNav()
        if (!nav.isNullOrEmpty()) return nav
        val ncx = parseNcx()
        if (!ncx.isNullOrEmpty()) return ncx
        return null
    }

    private fun resolveSpine(href: String): Int {
        if (href.isBlank()) return -1
        val resolved = ChapterContentParser.resolvePath(opfDir, href)
        hrefToSpine[resolved]?.let { return it }
        return hrefToSpine.entries
            .firstOrNull { it.key.equals(resolved, ignoreCase = true) }?.value ?: -1
    }

    // ------------------------------------------------------------- EPUB3 nav

    private fun parseNav(): List<TocEntry>? {
        val item = manifestById.values
            .firstOrNull { it.properties.split(' ').any { p -> p == "nav" } }
            ?: manifestById.values.firstOrNull {
                it.mediaType.contains("xhtml") && it.href.substringAfterLast('/')
                    .contains("nav", ignoreCase = true)
            } ?: return null

        val href = ChapterContentParser.resolvePath(opfDir, item.href)
        val xml = try {
            archive.readText(href)
        } catch (_: Exception) {
            null
        } ?: return null

        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val navEl = doc.select("nav").firstOrNull {
            it.attr("epub:type") == "toc" || it.attr("type") == "toc"
        } ?: doc.selectFirst("nav") ?: return null
        val rootOl = navEl.children().firstOrNull { it.normalName() == "ol" }
            ?: navEl.selectFirst("ol") ?: return null

        val carried = intArrayOf(-1)
        return walkNavList(rootOl, 0, carried).ifEmpty { null }
    }

    private fun walkNavList(ol: Element, level: Int, carried: IntArray): List<TocEntry> {
        val entries = ArrayList<TocEntry>()
        for (li in ol.children().toList().filter { it.normalName() == "li" }) {
            val a = li.children().firstOrNull { it.normalName() == "a" }
            val span = li.children().firstOrNull { it.normalName() == "span" }
            val title = (a?.text() ?: span?.text() ?: "").trim()
            val href = a?.attr("href").orEmpty()
            var spine = resolveSpine(href)
            if (spine < 0) spine = carried[0] else carried[0] = spine

            val childOl = li.children().firstOrNull { it.normalName() == "ol" }
            val children = if (childOl != null) walkNavList(childOl, level + 1, carried) else emptyList()

            if (title.isNotEmpty() || children.isNotEmpty()) {
                entries.add(
                    TocEntry(
                        title = title.ifEmpty { "未命名" },
                        spineIndex = spine,
                        level = level,
                        children = children,
                        anchor = anchorOf(href)
                    )
                )
            }
        }
        return entries
    }

    // ------------------------------------------------------------- EPUB2 NCX

    private fun parseNcx(): List<TocEntry>? {
        val item = spineTocId?.let { manifestById[it] }
            ?: manifestById.values.firstOrNull { it.mediaType.contains("ncx") }
            ?: return null
        val href = ChapterContentParser.resolvePath(opfDir, item.href)
        val xml = try {
            archive.readText(href)
        } catch (_: Exception) {
            null
        } ?: return null

        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val navMap = doc.selectFirst("navMap") ?: return null
        val carried = intArrayOf(-1)
        return walkNavPoints(navMap, 0, carried).ifEmpty { null }
    }

    private fun walkNavPoints(parent: Element, level: Int, carried: IntArray): List<TocEntry> {
        val entries = ArrayList<TocEntry>()
        for (np in parent.children().toList().filter { it.normalName() == "navpoint" || it.normalName() == "navPoint" }) {
            val label = np.children().firstOrNull {
                it.normalName() == "navlabel" || it.normalName() == "navLabel"
            }?.selectFirst("text")?.text()?.trim().orEmpty()
            val src = np.children().firstOrNull { it.normalName() == "content" }
                ?.attr("src").orEmpty()
            var spine = resolveSpine(src)
            if (spine < 0) spine = carried[0] else carried[0] = spine

            val children = walkNavPoints(np, level + 1, carried)
            if (label.isNotEmpty() || children.isNotEmpty()) {
                entries.add(
                    TocEntry(
                        title = label.ifEmpty { "未命名" },
                        spineIndex = spine,
                        level = level,
                        children = children,
                        anchor = anchorOf(src)
                    )
                )
            }
        }
        return entries
    }

    /** 提取 href 中的片段标识（#anchor），无则返回 null */
    private fun anchorOf(href: String): String? =
        href.substringAfter('#', "").ifBlank { null }
}
