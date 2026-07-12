package com.vibecoding.reader.ui.reader.text

import com.vibecoding.reader.data.parser.ImagePathResolver
import com.vibecoding.reader.domain.model.EbookBlock
import java.io.File

/**
 * Markdown → [EbookBlock]（含图片路径解析）。
 */
object MarkdownBlocks {
    private val atx = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val bullet = Regex("""^\s*[-*+]\s+(.+)$""")
    private val ordered = Regex("""^\s*\d+\.\s+(.+)$""")
    private val quote = Regex("""^>\s?(.*)$""")
    private val fence = Regex("""^```""")
    private val hr = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
    private val onlyImage = Regex("""^!\[([^\]]*)\]\(([^)]+)\)\s*$""")
    private val inlineImage = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    fun parse(source: String, baseDir: File? = null): List<EbookBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<EbookBlock>()
        val para = StringBuilder()
        var inFence = false
        val code = StringBuilder()
        var charOffset = 0

        fun flushPara() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) {
                // 段内可能夹杂图片：拆分
                splitParagraphWithImages(t, charOffset, baseDir, blocks)
                charOffset += t.length + 1
            }
            para.clear()
        }

        for (raw in lines) {
            val line = raw
            if (fence.containsMatchIn(line.trim())) {
                if (inFence) {
                    val codeText = code.toString().trimEnd()
                    blocks += EbookBlock.Code(charOffset, codeText)
                    charOffset += codeText.length + 1
                    code.clear()
                    inFence = false
                } else {
                    flushPara()
                    inFence = true
                }
                continue
            }
            if (inFence) {
                code.append(line).append('\n')
                continue
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushPara()
                continue
            }

            val imgOnly = onlyImage.matchEntire(trimmed)
            if (imgOnly != null) {
                flushPara()
                val alt = imgOnly.groupValues[1]
                val src = imgOnly.groupValues[2]
                val path = ImagePathResolver.resolveLocal(baseDir, src)
                if (path != null) {
                    blocks += EbookBlock.Image(charOffset, path, alt)
                } else {
                    blocks += EbookBlock.Paragraph(charOffset, alt.ifBlank { "[图片]" })
                }
                charOffset += 1
                continue
            }

            val h = atx.matchEntire(trimmed)
            if (h != null) {
                flushPara()
                val title = stripInlineKeepImagesAsText(h.groupValues[2].trim())
                blocks += EbookBlock.Heading(charOffset, h.groupValues[1].length, title)
                charOffset += title.length + 1
                continue
            }
            if (hr.matches(trimmed)) {
                flushPara()
                blocks += EbookBlock.Divider(charOffset)
                charOffset += 1
                continue
            }
            val b = bullet.matchEntire(line)
            if (b != null) {
                flushPara()
                val t = stripInlineKeepImagesAsText(b.groupValues[1])
                // 列表项整行是图
                val listImg = onlyImage.matchEntire(b.groupValues[1].trim())
                if (listImg != null) {
                    val path = ImagePathResolver.resolveLocal(baseDir, listImg.groupValues[2])
                    if (path != null) {
                        blocks += EbookBlock.Image(charOffset, path, listImg.groupValues[1])
                        charOffset += 1
                        continue
                    }
                }
                blocks += EbookBlock.Bullet(charOffset, t)
                charOffset += t.length + 1
                continue
            }
            val o = ordered.matchEntire(line)
            if (o != null) {
                flushPara()
                val t = stripInlineKeepImagesAsText(o.groupValues[1])
                blocks += EbookBlock.Bullet(charOffset, t)
                charOffset += t.length + 1
                continue
            }
            val q = quote.matchEntire(trimmed)
            if (q != null) {
                flushPara()
                val t = stripInlineKeepImagesAsText(q.groupValues[1])
                blocks += EbookBlock.Quote(charOffset, t)
                charOffset += t.length + 1
                continue
            }
            if (para.isNotEmpty()) para.append(' ')
            para.append(trimmed)
        }
        if (inFence && code.isNotEmpty()) {
            val codeText = code.toString().trimEnd()
            blocks += EbookBlock.Code(charOffset, codeText)
        }
        flushPara()
        return blocks
    }

    private fun splitParagraphWithImages(
        text: String,
        startOffset: Int,
        baseDir: File?,
        out: MutableList<EbookBlock>
    ) {
        var last = 0
        var offset = startOffset
        for (m in inlineImage.findAll(text)) {
            val before = text.substring(last, m.range.first).trim()
            if (before.isNotEmpty()) {
                out += EbookBlock.Paragraph(offset, stripInlineKeepImagesAsText(before))
                offset += before.length + 1
            }
            val alt = m.groupValues[1]
            val src = m.groupValues[2]
            val path = ImagePathResolver.resolveLocal(baseDir, src)
            if (path != null) {
                out += EbookBlock.Image(offset, path, alt)
            } else {
                out += EbookBlock.Paragraph(offset, alt.ifBlank { "[图片]" })
            }
            offset += 1
            last = m.range.last + 1
        }
        val rest = text.substring(last).trim()
        if (rest.isNotEmpty()) {
            out += EbookBlock.Paragraph(offset, stripInlineKeepImagesAsText(rest))
        }
        if (last == 0 && text.isNotEmpty() && out.none { it.charOffset >= startOffset }) {
            out += EbookBlock.Paragraph(startOffset, stripInlineKeepImagesAsText(text))
        }
    }

    private fun stripInlineKeepImagesAsText(text: String): String {
        var s = text
        // 段内图已在 split 中处理；此处去掉残留语法
        s = s.replace(inlineImage) { mr ->
            mr.groupValues[1].ifBlank { "[图片]" }
        }
        s = s.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
        s = s.replace(Regex("""(\*\*|__)(.*?)\1"""), "$2")
        s = s.replace(Regex("""(\*|_)(.*?)\1"""), "$2")
        s = s.replace(Regex("""`([^`]+)`"""), "$1")
        s = s.replace(Regex("""~~(.*?)~~"""), "$1")
        return s
    }
}
