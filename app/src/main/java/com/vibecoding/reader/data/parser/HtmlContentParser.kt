package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.EbookBlock
import java.io.File

/**
 * HTML 片段 → 文本/图片块序列（EPUB 章节用）。
 */
object HtmlContentParser {

    private val imgTag = Regex(
        """<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*/?>""",
        RegexOption.IGNORE_CASE
    )
    private val headingTag = Regex(
        """<h([1-6])\b[^>]*>(.*?)</h\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * @param html 章节 HTML
     * @param resolveImage 将 src 解析为本地绝对路径，失败返回 null
     * @param startOffset plainText 起始偏移
     */
    fun parseToBlocks(
        html: String,
        startOffset: Int,
        resolveImage: (String) -> String?
    ): Pair<String, List<EbookBlock>> {
        // 先把 img 换成占位，再按块切
        val parts = mutableListOf<Part>()
        var last = 0
        val imgMatches = imgTag.findAll(html).toList()
        for (m in imgMatches) {
            if (m.range.first > last) {
                parts += Part.Html(html.substring(last, m.range.first))
            }
            val src = m.groupValues[1]
            val alt = Regex(
                """\balt\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).find(m.value)?.groupValues?.get(1).orEmpty()
            parts += Part.Image(src, alt)
            last = m.range.last + 1
        }
        if (last < html.length) {
            parts += Part.Html(html.substring(last))
        }

        val plain = StringBuilder()
        val blocks = mutableListOf<EbookBlock>()
        var offset = startOffset

        for (part in parts) {
            when (part) {
                is Part.Html -> {
                    // 抽 heading
                    var segment = part.html
                    val hMatches = headingTag.findAll(segment).toList()
                    if (hMatches.isEmpty()) {
                        val text = HtmlText.extract(segment)
                        if (text.isNotBlank()) {
                            blocks += EbookBlock.Paragraph(offset, text)
                            plain.append(text).append('\n')
                            offset += text.length + 1
                        }
                    } else {
                        var hLast = 0
                        for (hm in hMatches) {
                            if (hm.range.first > hLast) {
                                val before = HtmlText.extract(segment.substring(hLast, hm.range.first))
                                if (before.isNotBlank()) {
                                    blocks += EbookBlock.Paragraph(offset, before)
                                    plain.append(before).append('\n')
                                    offset += before.length + 1
                                }
                            }
                            val level = hm.groupValues[1].toIntOrNull() ?: 1
                            val title = HtmlText.extract(hm.groupValues[2]).trim()
                            if (title.isNotEmpty()) {
                                blocks += EbookBlock.Heading(offset, level, title)
                                plain.append(title).append('\n')
                                offset += title.length + 1
                            }
                            hLast = hm.range.last + 1
                        }
                        if (hLast < segment.length) {
                            val after = HtmlText.extract(segment.substring(hLast))
                            if (after.isNotBlank()) {
                                blocks += EbookBlock.Paragraph(offset, after)
                                plain.append(after).append('\n')
                                offset += after.length + 1
                            }
                        }
                    }
                }
                is Part.Image -> {
                    val path = resolveImage(part.src)
                    if (path != null && File(path).exists()) {
                        blocks += EbookBlock.Image(offset, path, part.alt)
                        val marker = if (part.alt.isNotBlank()) "[图片: ${part.alt}]" else "[图片]"
                        plain.append(marker).append('\n')
                        offset += marker.length + 1
                    } else if (part.alt.isNotBlank()) {
                        blocks += EbookBlock.Paragraph(offset, "[图片: ${part.alt}]")
                        plain.append("[图片: ${part.alt}]").append('\n')
                        offset += part.alt.length + 8
                    }
                }
            }
        }
        return plain.toString() to blocks
    }

    private sealed class Part {
        data class Html(val html: String) : Part()
        data class Image(val src: String, val alt: String) : Part()
    }
}
