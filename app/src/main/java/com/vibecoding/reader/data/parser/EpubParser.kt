package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.EbookDocument
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 轻量 EPUB 解析（不依赖第三方）：
 * container.xml → OPF → spine 正文 + nav/ncx 目录 → 纯文本流。
 * 封面路径通过 [extractCover] 单独写出。
 */
object EpubParser {

    data class EpubParseResult(
        val document: EbookDocument,
        val coverEntryName: String?
    )

    /**
     * @param mediaDir 若提供，则将 EPUB 内图片解压到该目录并在 blocks 中引用
     */
    fun parse(file: File, mediaDir: File? = null): EpubParseResult {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip) ?: error("无效 EPUB：缺少 container.xml / OPF")
            val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            val opfXml = readZipText(zip, opfPath) ?: error("无法读取 OPF")
            val opf = parseOpf(opfXml, opfDir)

            mediaDir?.mkdirs()
            val extracted = mutableMapOf<String, String>() // zip entry -> abs path

            fun extractImage(zipPath: String): String? {
                val key = zipPath.replace('\\', '/')
                extracted[key]?.let { return it }
                if (mediaDir == null) return null
                val entry = zip.getEntry(key)
                    ?: zip.entries().asSequence().firstOrNull { it.name.equals(key, true) }
                    ?: return null
                val name = key.substringAfterLast('/').ifBlank { "img_${extracted.size}.bin" }
                val safe = name.replace(Regex("""[^\w.\-]+"""), "_")
                val out = File(mediaDir, "${extracted.size}_$safe")
                return runCatching {
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && out.length() > 0) {
                        extracted[key] = out.absolutePath
                        out.absolutePath
                    } else null
                }.getOrNull()
            }

            val allBlocks = mutableListOf<com.vibecoding.reader.domain.model.EbookBlock>()
            val plain = StringBuilder()
            val hrefToOffset = linkedMapOf<String, Int>()

            // 按 spine 顺序拼接正文 + 图片块
            for (itemId in opf.spineIds) {
                val href = opf.manifest[itemId] ?: continue
                val fullPath = resolvePath(opfDir, href)
                val html = readZipText(zip, fullPath) ?: continue
                val chapterBase = fullPath.substringBeforeLast('/', missingDelimiterValue = "")

                val offset = plain.length
                val (chapterPlain, chapterBlocks) = HtmlContentParser.parseToBlocks(
                    html = html,
                    startOffset = offset
                ) { src ->
                    if (ImagePathResolver.isRemote(src)) return@parseToBlocks null
                    val decoded = ImagePathResolver.decode(src)
                    val zipImg = resolvePath(chapterBase, decoded)
                    extractImage(zipImg)
                        ?: extractImage(resolvePath(opfDir, decoded))
                        ?: extractImage(decoded.substringAfterLast('/'))
                }

                if (chapterPlain.isBlank() && chapterBlocks.isEmpty()) continue

                val normHref = normalizeHref(href)
                hrefToOffset[normHref] = offset
                hrefToOffset[fullPath.substringAfterLast('/')] = offset

                if (plain.isNotEmpty() && !plain.endsWith("\n")) plain.append("\n\n")
                plain.append(chapterPlain)
                if (!chapterPlain.endsWith("\n")) plain.append('\n')
                allBlocks += chapterBlocks
            }

            // 目录：优先 nav，其次 ncx，再次用 spine 文件名
            val navToc = opf.navHref?.let { nav ->
                val path = resolvePath(opfDir, nav)
                readZipText(zip, path)?.let { parseNavToc(it, hrefToOffset, plain.length) }
            }.orEmpty()

            val ncxToc = if (navToc.isEmpty() && opf.ncxHref != null) {
                val path = resolvePath(opfDir, opf.ncxHref)
                readZipText(zip, path)?.let { parseNcxToc(it, hrefToOffset, plain.length) }.orEmpty()
            } else emptyList()

            val finalToc = when {
                navToc.isNotEmpty() -> navToc
                ncxToc.isNotEmpty() -> ncxToc
                else -> {
                    // 回退：每个 spine 一章
                    opf.spineIds.mapNotNull { id ->
                        val href = opf.manifest[id] ?: return@mapNotNull null
                        val off = hrefToOffset[normalizeHref(href)]
                            ?: hrefToOffset[href.substringAfterLast('/')]
                            ?: return@mapNotNull null
                        TocEntry(
                            title = href.substringAfterLast('/').substringBeforeLast('.'),
                            position = ReaderPosition.CharOffset(off).serialize(),
                            level = 0,
                            kind = TocKind.CHAPTER
                        )
                    }
                }
            }

            // 若仍无目录，对纯文本做章节启发式
            val plainText = plain.toString()
            val tocResolved = finalToc.ifEmpty { TxtParser.buildToc(plainText) }

            return EpubParseResult(
                document = EbookDocument(
                    plainText = plainText,
                    toc = tocResolved,
                    title = opf.title,
                    author = opf.creator,
                    blocks = allBlocks
                ),
                coverEntryName = opf.coverHref?.let { resolvePath(opfDir, it) }
            )
        }
    }

    fun extractCover(epubFile: File, coverEntryName: String?, outFile: File): Boolean {
        if (coverEntryName.isNullOrBlank()) return false
        return runCatching {
            ZipFile(epubFile).use { zip ->
                val entry = zip.getEntry(coverEntryName) ?: return false
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            outFile.exists() && outFile.length() > 0
        }.getOrDefault(false)
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = readZipText(zip, "META-INF/container.xml") ?: return null
        val regex = Regex("""full-path\s*=\s*"([^"]+)"""")
        return regex.find(container)?.groupValues?.get(1)
    }

    private data class OpfData(
        val title: String?,
        val creator: String?,
        val manifest: Map<String, String>, // id -> href
        val spineIds: List<String>,
        val navHref: String?,
        val ncxHref: String?,
        val coverHref: String?
    )

    private fun parseOpf(xml: String, opfDir: String): OpfData {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val manifest = linkedMapOf<String, String>()
        val manifestProps = mutableMapOf<String, String>() // id -> properties
        val manifestMedia = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        var title: String? = null
        var creator: String? = null
        var inTitle = false
        var inCreator = false
        var coverId: String? = null
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase().orEmpty()
                    when (name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                                ?: attr(parser, "id")
                            val href = parser.getAttributeValue(null, "href")
                                ?: attr(parser, "href")
                            val props = parser.getAttributeValue(null, "properties")
                                ?: attr(parser, "properties")
                            val media = parser.getAttributeValue(null, "media-type")
                                ?: attr(parser, "media-type")
                            if (id != null && href != null) {
                                manifest[id] = href
                                if (props != null) manifestProps[id] = props
                                if (media != null) manifestMedia[id] = media
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref")
                                ?: attr(parser, "idref")
                            if (idref != null) spine += idref
                        }
                        "title" -> inTitle = true
                        "creator" -> inCreator = true
                        "meta" -> {
                            val nameAttr = parser.getAttributeValue(null, "name")
                                ?: attr(parser, "name")
                            val content = parser.getAttributeValue(null, "content")
                                ?: attr(parser, "content")
                            if (nameAttr == "cover" && content != null) coverId = content
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim().orEmpty()
                    if (t.isNotEmpty()) {
                        if (inTitle && title == null) title = t
                        if (inCreator && creator == null) creator = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name?.lowercase()) {
                        "title" -> inTitle = false
                        "creator" -> inCreator = false
                    }
                }
            }
            event = parser.next()
        }

        val navHref = manifest.entries.firstOrNull { (id, _) ->
            manifestProps[id]?.contains("nav") == true
        }?.value

        val ncxHref = manifest.entries.firstOrNull { (id, href) ->
            manifestMedia[id]?.contains("ncx") == true ||
                href.endsWith(".ncx", ignoreCase = true)
        }?.value

        val coverHref = when {
            coverId != null && manifest.containsKey(coverId) -> manifest[coverId]
            else -> manifest.entries.firstOrNull { (id, href) ->
                manifestProps[id]?.contains("cover-image") == true ||
                    href.contains("cover", ignoreCase = true) &&
                    (href.endsWith(".jpg") || href.endsWith(".jpeg") ||
                        href.endsWith(".png") || href.endsWith(".webp"))
            }?.value
        }

        return OpfData(
            title = title,
            creator = creator,
            manifest = manifest,
            spineIds = spine,
            navHref = navHref,
            ncxHref = ncxHref,
            coverHref = coverHref
        )
    }

    private fun attr(parser: XmlPullParser, local: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == local) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun parseNavToc(
        navHtml: String,
        hrefToOffset: Map<String, Int>,
        textLen: Int
    ): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        // 简易提取 <a href="...">title</a>
        val regex = Regex(
            """<a[^>]+href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in regex.findAll(navHtml)) {
            val href = m.groupValues[1].substringBefore('#').trim()
            val title = HtmlText.extract(m.groupValues[2]).trim()
            if (title.isEmpty()) continue
            val off = resolveOffset(href, hrefToOffset) ?: continue
            if (off > textLen) continue
            entries += TocEntry(
                title = title.take(80),
                position = ReaderPosition.CharOffset(off).serialize(),
                level = 0,
                kind = TocKind.CHAPTER
            )
        }
        return entries.distinctBy { it.position to it.title }
    }

    private fun parseNcxToc(
        ncxXml: String,
        hrefToOffset: Map<String, Int>,
        textLen: Int
    ): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(ncxXml.reader())
        var event = parser.eventType
        var currentLabel: String? = null
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name?.lowercase()) {
                        "text" -> inText = true
                        "content" -> {
                            val src = parser.getAttributeValue(null, "src")
                                ?: attr(parser, "src")
                            val title = currentLabel?.trim().orEmpty()
                            if (src != null && title.isNotEmpty()) {
                                val href = src.substringBefore('#')
                                val off = resolveOffset(href, hrefToOffset)
                                if (off != null && off <= textLen) {
                                    entries += TocEntry(
                                        title = title.take(80),
                                        position = ReaderPosition.CharOffset(off).serialize(),
                                        level = 0,
                                        kind = TocKind.CHAPTER
                                    )
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) {
                        val t = parser.text?.trim().orEmpty()
                        if (t.isNotEmpty()) currentLabel = t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name?.lowercase() == "text") inText = false
                    if (parser.name?.lowercase() == "navpoint") currentLabel = null
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.position to it.title }
    }

    private fun resolveOffset(href: String, map: Map<String, Int>): Int? {
        val norm = normalizeHref(href)
        map[norm]?.let { return it }
        map[norm.substringAfterLast('/')]?.let { return it }
        // 尝试解码相对路径尾段
        return map.entries.firstOrNull { (k, _) ->
            k.endsWith(norm.substringAfterLast('/'))
        }?.value
    }

    private fun normalizeHref(href: String): String =
        href.replace('\\', '/').substringBefore('#').trim()

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = href.replace('\\', '/').substringBefore('#')
        if (baseDir.isEmpty()) return clean
        if (clean.startsWith("/")) return clean.trimStart('/')
        // 简易相对路径
        val baseParts = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (part in clean.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (baseParts.isNotEmpty()) baseParts.removeAt(baseParts.lastIndex)
                else -> baseParts += part
            }
        }
        return baseParts.joinToString("/")
    }

    private fun readZipText(zip: ZipFile, path: String): String? {
        val entry = zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull {
            it.name.equals(path, ignoreCase = true)
        } ?: return null
        return zip.getInputStream(entry).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
        }
    }
}

/** 极简 HTML → 纯文本 */
object HtmlText {
    fun extract(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</div>"), "\n")
        s = s.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        s = s.replace(Regex("(?i)</li>"), "\n")
        s = s.replace(Regex("(?i)<li[^>]*>"), "• ")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \t]+\n"), "\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun decodeEntities(s: String): String {
        return s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
    }
}
