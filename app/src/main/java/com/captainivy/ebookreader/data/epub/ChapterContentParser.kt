package com.captainivy.ebookreader.data.epub

import android.graphics.Bitmap
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** 章节内容块 */
sealed class Block

class TextBlock(
    val text: CharSequence,
    val style: TextStyle,
    val align: TextAlign = TextAlign.START
) : Block()

class ImageBlock(val bitmap: Bitmap, val width: Int, val height: Int) : Block()

/** 水平分隔线 */
object HorizontalRuleBlock : Block()

enum class TextStyle { NORMAL, H1, H2, H3, H4, H5, H6, LIST, QUOTE, CODE, CAPTION }

/** 段落对齐方式 */
enum class TextAlign { START, CENTER, END }

/**
 * 脚注/尾注引用标记：绘制为上标，并携带注释目标信息供点击弹窗使用。
 */
class NoteRefSpan(
    val noteId: String,
    val targetHref: String
) : SuperscriptSpan()

class ChapterContent(
    val blocks: List<Block>,
    val plainLength: Int,
    /** 锚点 id -> 章节内纯文本偏移，用于目录精确跳转 */
    val anchors: Map<String, Int> = emptyMap()
)

/** 图片加载器：把相对章节的 href 解析成 Bitmap（可为空） */
fun interface ImageLoader {
    fun load(href: String): Bitmap?
}

/**
 * 把 XHTML 章节内容解析为可排版的块序列。
 * 支持标题、段落、有序/无序列表、引用、代码块、水平线、图片与图注等。
 */
class ChapterContentParser {

    fun parse(
        xhtml: String,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ): ChapterContent {
        val doc = Jsoup.parse(xhtml)
        val body = doc.body() ?: doc
        val out = ArrayList<Block>()
        val anchors = HashMap<String, Int>()
        processChildren(
            body, TextStyle.NORMAL, TextAlign.START, out, anchors,
            chapterHref, images, maxImageWidth
        )
        val plainLength = out.sumOf { b -> if (b is TextBlock) b.text.length else 0 }
        return ChapterContent(out, plainLength, anchors)
    }

    /** 仅统计纯文本长度，用于导入时计算进度分母 */
    fun countChars(xhtml: String): Int {
        val body = Jsoup.parse(xhtml).body() ?: return 0
        return body.text().length
    }

    /**
     * 顺序处理容器的子节点：普通文本进入缓冲，块级元素拆分为独立块。
     * `style` 为当前容器继承的默认样式，`align` 为继承的对齐方式。
     */
    private fun processChildren(
        container: Element,
        style: TextStyle,
        align: TextAlign,
        out: MutableList<Block>,
        anchors: MutableMap<String, Int>,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ) {
        var buffer = SpannableStringBuilder()

        fun flush() {
            if (buffer.isNotBlank()) out.add(TextBlock(SpannableStringBuilder(buffer), style, align))
            buffer = SpannableStringBuilder()
        }

        for (node in container.childNodes()) {
            when (node) {
                is TextNode -> buffer.append(node.text())
                is Element -> {
                    val name = node.normalName()
                    recordAnchor(node, out, buffer, anchors)
                    when {
                        name == "br" -> buffer.append('\n')
                        name == "wbr" -> Unit
                        name == "img" -> {
                            flush()
                            loadImage(node, chapterHref, images, maxImageWidth)?.let {
                                out.add(ImageBlock(it, it.width, it.height))
                            }
                        }
                        name == "svg" -> {
                            flush()
                            loadSvgImage(node, chapterHref, images, maxImageWidth)?.let {
                                out.add(ImageBlock(it, it.width, it.height))
                            }
                        }
                        name == "image" -> {
                            flush()
                            val href = node.attr("xlink:href").ifBlank { node.attr("href") }
                            resolveImage(href, chapterHref, images, maxImageWidth)?.let {
                                out.add(ImageBlock(it, it.width, it.height))
                            }
                        }
                        name == "hr" -> {
                            flush()
                            out.add(HorizontalRuleBlock)
                        }
                        name == "figure" -> {
                            flush()
                            processFigure(node, out, anchors, chapterHref, images, maxImageWidth)
                        }
                        name == "ul" || name == "ol" -> {
                            flush()
                            processList(node, name == "ol", out, anchors, chapterHref, images, maxImageWidth)
                        }
                        isBlockTag(name) -> {
                            flush()
                            val own = styleForTag(name)
                            val childStyle = if (own == TextStyle.NORMAL) style else own
                            val childAlign = alignOf(node) ?: align
                            processChildren(
                                node, childStyle, childAlign, out, anchors,
                                chapterHref, images, maxImageWidth
                            )
                        }
                        else -> collectInlineElement(node, buffer, chapterHref)
                    }
                }
            }
        }
        flush()
    }

    /** 处理列表：为每个 li 添加项目符号/序号，并递归处理嵌套块 */
    private fun processList(
        list: Element,
        ordered: Boolean,
        out: MutableList<Block>,
        anchors: MutableMap<String, Int>,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ) {
        var index = 1
        for (child in list.children()) {
            if (child.normalName() != "li") continue
            val inline = SpannableStringBuilder()
            collectInline(child, inline, chapterHref)
            if (inline.isNotBlank()) {
                val marker = if (ordered) "$index. " else "• "
                inline.insert(0, marker)
                out.add(TextBlock(inline, TextStyle.LIST, alignOf(child) ?: TextAlign.START))
            }
            // 嵌套列表/块级内容
            for (sub in child.children()) {
                val sname = sub.normalName()
                when {
                    sname == "ul" || sname == "ol" ->
                        processList(sub, sname == "ol", out, anchors, chapterHref, images, maxImageWidth)
                    sname == "img" ->
                        loadImage(sub, chapterHref, images, maxImageWidth)?.let {
                            out.add(ImageBlock(it, it.width, it.height))
                        }
                    isBlockTag(sname) ->
                        processChildren(
                            sub, TextStyle.NORMAL, alignOf(sub) ?: TextAlign.START,
                            out, anchors, chapterHref, images, maxImageWidth
                        )
                }
            }
            index++
        }
    }

    /** 处理 figure：图片（居中）+ 图注 */
    private fun processFigure(
        figure: Element,
        out: MutableList<Block>,
        anchors: MutableMap<String, Int>,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ) {
        figure.selectFirst("img")?.let { img ->
            loadImage(img, chapterHref, images, maxImageWidth)?.let {
                out.add(ImageBlock(it, it.width, it.height))
            }
        }
        figure.selectFirst("figcaption")?.text()?.trim()?.let { caption ->
            if (caption.isNotEmpty()) {
                out.add(TextBlock(SpannableStringBuilder(caption), TextStyle.CAPTION, TextAlign.CENTER))
            }
        }
        // figure 内其它块级内容
        for (child in figure.children()) {
            val name = child.normalName()
            if (name == "img" || name == "figcaption") continue
            if (isBlockTag(name)) {
                processChildren(
                    child, TextStyle.NORMAL, alignOf(child) ?: TextAlign.START,
                    out, anchors, chapterHref, images, maxImageWidth
                )
            }
        }
    }

    /** 收集元素的内联文本，遇到块级子元素停下 */
    private fun collectInline(el: Element, sb: SpannableStringBuilder, chapterHref: String) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    val name = node.normalName()
                    when {
                        name == "br" -> sb.append('\n')
                        name == "img" -> Unit
                        isBlockTag(name) -> Unit
                        else -> collectInlineElement(node, sb, chapterHref)
                    }
                }
            }
        }
    }

    /** 处理一个内联元素并施加样式 */
    private fun collectInlineElement(
        el: Element,
        sb: SpannableStringBuilder,
        chapterHref: String
    ) {
        val name = el.normalName()
        val note = name == "a" && isNoteRef(el)
        val start = sb.length
        collectInline(el, sb, chapterHref)
        var end = sb.length
        // 注释引用若无可读文本，补一个标记符号
        if (note && end == start) {
            sb.append("[注]")
            end = sb.length
        }
        if (end <= start) return
        when (name) {
            "b", "strong" -> sb.setSpan(
                StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            "i", "em" -> sb.setSpan(
                StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            "u" -> sb.setSpan(
                UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (note) {
            val href = el.attr("href").ifBlank { el.attr("xlink:href") }
            val (target, id) = resolveNoteTarget(href, chapterHref)
            sb.setSpan(
                NoteRefSpan(id, target), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                RelativeSizeSpan(0.72f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** 判断链接是否为脚注/尾注引用 */
    private fun isNoteRef(el: Element): Boolean {
        val type = el.attr("epub:type").ifBlank { el.attr("type") }.lowercase()
        if (type.contains("noteref")) return true
        if (el.attr("role").lowercase() == "doc-noteref") return true
        val href = el.attr("href")
        if (href.isEmpty() || !href.contains('#')) return false
        // 锚点命名含 note/fn/脚注 等特征，或位于 <sup> 中，或引用文本为纯序号
        if (NOTE_ID.containsMatchIn(href.substringAfter('#'))) return true
        if (el.parent()?.normalName() == "sup") return true
        return el.text().trim().matches(NUMERIC_REF)
    }

    /** 解析注释目标文件与锚点 id */
    private fun resolveNoteTarget(href: String, chapterHref: String): Pair<String, String> {
        if (href.startsWith("#")) return chapterHref to href.substring(1)
        val clean = href.substringBefore('#')
        val id = href.substringAfter('#', "")
        val target = resolvePath(dirOf(chapterHref), clean)
        return target to id
    }

    /** 记录元素锚点 id 对应的章节内纯文本偏移 */
    private fun recordAnchor(
        el: Element,
        out: List<Block>,
        buffer: SpannableStringBuilder,
        anchors: MutableMap<String, Int>
    ) {
        val id = el.id().ifBlank { el.attr("xml:id") }
        if (id.isEmpty() || anchors.containsKey(id)) return
        var len = buffer.length
        for (b in out) if (b is TextBlock) len += b.text.length
        anchors[id] = len
    }

    private fun loadImage(
        img: Element,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ): Bitmap? {
        val src = img.attr("src").ifBlank { img.attr("xlink:href") }
        return resolveImage(src, chapterHref, images, maxImageWidth)
    }

    /**
     * 处理 SVG 封面：EPUB 封面页常用 `<svg><image xlink:href="..."/></svg>`，
     * 直接取其中的 `<image>` 引用加载位图，否则整页会显示为空白。
     */
    private fun loadSvgImage(
        svg: Element,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ): Bitmap? {
        val image = svg.selectFirst("image") ?: return null
        val href = image.attr("xlink:href").ifBlank { image.attr("href") }
        return resolveImage(href, chapterHref, images, maxImageWidth)
    }

    private fun resolveImage(
        src: String,
        chapterHref: String,
        images: ImageLoader?,
        maxImageWidth: Int
    ): Bitmap? {
        if (images == null || maxImageWidth <= 0) return null
        if (src.isBlank()) return null
        val resolved = resolvePath(dirOf(chapterHref), src)
        return try {
            images.load(resolved)
        } catch (_: Exception) {
            null
        }
    }

    private fun alignOf(el: Element): TextAlign? {
        when (el.attr("align").lowercase()) {
            "center" -> return TextAlign.CENTER
            "right", "end" -> return TextAlign.END
        }
        val style = el.attr("style")
        if (style.isNotEmpty()) {
            val m = TEXT_ALIGN.find(style)?.groupValues?.get(1)?.lowercase()
            when (m) {
                "center" -> return TextAlign.CENTER
                "right", "end" -> return TextAlign.END
            }
        }
        return null
    }

    private fun isBlockTag(name: String): Boolean = name in BLOCK_TAGS

    private fun styleForTag(name: String): TextStyle = when (name) {
        "h1" -> TextStyle.H1
        "h2" -> TextStyle.H2
        "h3" -> TextStyle.H3
        "h4" -> TextStyle.H4
        "h5" -> TextStyle.H5
        "h6" -> TextStyle.H6
        "li" -> TextStyle.LIST
        "blockquote" -> TextStyle.QUOTE
        "pre" -> TextStyle.CODE
        else -> TextStyle.NORMAL
    }

    companion object {
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "aside", "nav",
            "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd",
            "blockquote", "pre", "figure", "figcaption", "table", "thead", "tbody",
            "tr", "td", "th", "hr", "main", "body"
        )

        private val TEXT_ALIGN =
            Regex("text-align\\s*:\\s*(center|right|end|left|start)", RegexOption.IGNORE_CASE)

        /** 注释锚点 id 特征 */
        private val NOTE_ID =
            Regex("(fn|ftn|note|foot|endnote|注释|脚注)", RegexOption.IGNORE_CASE)

        /** 纯序号引用文本，如 1、[1]、(1) */
        private val NUMERIC_REF = Regex("^[\\[（(]?\\d{1,3}[\\]）)]?$")

        fun dirOf(path: String): String {
            val i = path.lastIndexOf('/')
            return if (i < 0) "" else path.substring(0, i)
        }

        fun resolvePath(baseDir: String, href: String): String {
            val clean = href.substringBefore('#')
            val decoded = try {
                java.net.URLDecoder.decode(clean, "UTF-8")
            } catch (_: Exception) {
                clean
            }
            val path = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
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
}
