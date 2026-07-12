package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.EbookDocument
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import com.vibecoding.reader.ui.reader.text.MarkdownBlocks
import java.io.File

/**
 * Markdown 解析：目录 + plainText + 富内容块（含本地图片）。
 */
object MarkdownParser {

    private val atxHeading = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val setextH1 = Regex("""^=+\s*$""")
    private val setextH2 = Regex("""^-+\s*$""")
    private val fence = Regex("""^```""")
    private val mdImage = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    fun parseFile(file: File): EbookDocument {
        val source = TxtParser.readText(file)
        // 不从 original.md 取标题；优先用文内一级标题，否则由导入显示名决定
        return parse(
            source = source,
            titleHint = null,
            baseDir = file.parentFile
        )
    }

    fun parse(
        source: String,
        titleHint: String? = null,
        baseDir: File? = null
    ): EbookDocument {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val toc = mutableListOf<TocEntry>()
        val plain = StringBuilder()
        val lines = normalized.split('\n')
        var i = 0
        var inFence = false

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()

            if (fence.containsMatchIn(trimmed)) {
                inFence = !inFence
                if (!inFence) {
                    // 结束 fence，前面代码已在 inFence 分支写入
                } else {
                    // 开始 fence：跳过标记行
                }
                i++
                continue
            }

            if (inFence) {
                plain.append(line).append('\n')
                i++
                continue
            }

            val atx = atxHeading.matchEntire(trimmed.trim())
            if (atx != null) {
                val level = atx.groupValues[1].length.coerceIn(1, 6) - 1
                val title = atx.groupValues[2].trim()
                val offset = plain.length
                if (plain.isNotEmpty() && !plain.endsWith("\n")) plain.append('\n')
                plain.append(title).append('\n')
                toc += TocEntry(
                    title = title.take(80),
                    position = ReaderPosition.CharOffset(offset).serialize(),
                    level = level.coerceAtMost(5),
                    kind = TocKind.CHAPTER
                )
                i++
                continue
            }

            if (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                val prevContent = trimmed.trim()
                if (prevContent.isNotEmpty() &&
                    !prevContent.startsWith("#") &&
                    (setextH1.matches(next) || setextH2.matches(next))
                ) {
                    val level = if (setextH1.matches(next)) 0 else 1
                    val offset = plain.length
                    if (plain.isNotEmpty() && !plain.endsWith("\n")) plain.append('\n')
                    plain.append(prevContent).append('\n')
                    toc += TocEntry(
                        title = prevContent.take(80),
                        position = ReaderPosition.CharOffset(offset).serialize(),
                        level = level,
                        kind = TocKind.CHAPTER
                    )
                    i += 2
                    continue
                }
            }

            // 图片行在 plain 中保留 alt 或 [图片]
            val cleaned = stripBlockPrefix(stripInlinePreserveImageAlt(line))
            plain.append(cleaned).append('\n')
            i++
        }

        val plainText = plain.toString().trimEnd() + if (plain.isNotEmpty()) "\n" else ""
        val blocks = MarkdownBlocks.parse(normalized, baseDir)
        // 若有一级/顶级标题，用作书名候选
        val fromHeading = toc.firstOrNull { it.level == 0 }?.title

        return EbookDocument(
            plainText = plainText,
            toc = toc,
            title = titleHint ?: fromHeading,
            markdownSource = normalized,
            blocks = blocks
        )
    }

    private fun stripBlockPrefix(line: String): String {
        var s = line
        s = s.replace(Regex("""^>\s?"""), "")
        s = s.replace(Regex("""^\s*[-*+]\s+"""), "• ")
        s = s.replace(Regex("""^\s*\d+\.\s+"""), "")
        if (s.trim().matches(Regex("""^(-{3,}|\*{3,}|_{3,})$"""))) return ""
        return s
    }

    private fun stripInlinePreserveImageAlt(text: String): String {
        var s = text
        s = s.replace(mdImage) { m ->
            val alt = m.groupValues[1].trim()
            if (alt.isNotEmpty()) "\n[图片: $alt]\n" else "\n[图片]\n"
        }
        s = s.replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
        s = s.replace(Regex("""(\*\*|__)(.*?)\1"""), "$2")
        s = s.replace(Regex("""(\*|_)(.*?)\1"""), "$2")
        s = s.replace(Regex("""`([^`]+)`"""), "$1")
        s = s.replace(Regex("""~~(.*?)~~"""), "$1")
        return s
    }
}
