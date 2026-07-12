package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class StructuredDocument(
    val plainText: String,
    val toc: List<TocEntry>
)

/**
 * 轻量 DOCX 解析：读取 word/document.xml，提取段落文本与标题样式。
 * 不做复杂版式还原。
 */
object DocxParser {
    fun parse(file: File): StructuredDocument {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: return StructuredDocument("", emptyList())
            zip.getInputStream(entry).use { input ->
                return parseDocumentXml(input)
            }
        }
    }

    private fun parseDocumentXml(input: InputStream): StructuredDocument {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        val textBuilder = StringBuilder()
        val toc = mutableListOf<TocEntry>()
        val paragraph = StringBuilder()
        var styleId: String? = null
        var inText = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            paragraph.clear()
                            styleId = null
                        }
                        "pStyle" -> {
                            styleId = parser.getAttributeValue(
                                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                                "val"
                            ) ?: parser.getAttributeValue(null, "val")
                        }
                        "t" -> inText = true
                        "br", "cr" -> paragraph.append('\n')
                        "tab" -> paragraph.append('\t')
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) paragraph.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inText = false
                        "p" -> {
                            val line = paragraph.toString().trimEnd()
                            val offset = textBuilder.length
                            if (line.isNotEmpty()) {
                                textBuilder.append(line)
                                val headingLevel = headingLevel(styleId, line)
                                if (headingLevel != null) {
                                    toc += TocEntry(
                                        title = line.take(80),
                                        position = ReaderPosition.CharOffset(offset).serialize(),
                                        level = headingLevel
                                    )
                                }
                            }
                            textBuilder.append('\n')
                        }
                    }
                }
            }
            event = parser.next()
        }

        val plain = textBuilder.toString().trimEnd() + if (textBuilder.isNotEmpty()) "\n" else ""
        // 若无 heading 样式，回退到 TXT 启发式
        val finalToc = toc.ifEmpty { TxtParser.buildToc(plain) }
        return StructuredDocument(plain, finalToc)
    }

    private fun headingLevel(styleId: String?, line: String): Int? {
        if (styleId != null) {
            val lower = styleId.lowercase()
            val match = Regex("""heading\s*(\d)""").find(lower)
                ?: Regex("""标题\s*(\d)""").find(styleId)
            if (match != null) {
                return (match.groupValues[1].toIntOrNull() ?: 1).coerceIn(0, 5)
            }
            if (lower.contains("title") || styleId.contains("标题")) return 0
        }
        // 部分文档标题无样式：短行且像章节
        if (line.length in 2..40 && (
                line.startsWith("第") && (line.contains("章") || line.contains("节")) ||
                    line.matches(Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE))
                )
        ) {
            return 0
        }
        return null
    }
}
