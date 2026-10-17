package com.captainivy.ebookreader.data.epub

import org.jsoup.Jsoup

/**
 * 解析脚注/尾注内容：按文件缓存 id -> 文本，供点击引用时弹窗展示。
 */
class NoteResolver(private val archive: EpubArchive) {

    private val cache = HashMap<String, Map<String, String>>()

    /** 返回 href（文件路径） + id（锚点）对应的注释文本，找不到返回 null */
    fun resolve(href: String, id: String): String? {
        if (href.isBlank() || id.isBlank()) return null
        val map = cache.getOrPut(href) { loadIdMap(href) }
        return map[id]
    }

    private fun loadIdMap(href: String): Map<String, String> {
        val xml = try {
            archive.readText(href)
        } catch (_: Exception) {
            null
        } ?: return emptyMap()
        val doc = try {
            Jsoup.parse(xml)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = HashMap<String, String>()
        val whitespace = Regex("\\s+")
        for (el in doc.getAllElements()) {
            val id = el.id()
            if (id.isBlank() || out.containsKey(id)) continue
            val text = el.text().trim().replace(whitespace, " ")
            if (text.isNotEmpty()) out[id] = text
        }
        return out
    }
}
