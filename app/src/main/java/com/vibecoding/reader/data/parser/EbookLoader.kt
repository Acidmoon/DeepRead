package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.EbookDocument
import java.io.File

/**
 * 电子书统一加载入口：TXT / MD / EPUB / DOCX → [EbookDocument]
 *
 * @param bookDir 书籍私有目录；EPUB 图片会解压到 bookDir/media
 */
object EbookLoader {

    fun load(format: BookFormat, file: File, bookDir: File? = null): EbookDocument {
        require(format.isEbook) { "非电子书格式: $format" }
        val dir = bookDir ?: file.parentFile
        return when (format) {
            BookFormat.TXT -> {
                val text = TxtParser.readText(file)
                EbookDocument(
                    plainText = text,
                    toc = TxtParser.buildToc(text),
                    // 本地副本常为 original.txt，书名由导入时的显示名决定，这里不从文件名取
                    title = null
                )
            }
            BookFormat.MD -> MarkdownParser.parseFile(file)
            BookFormat.EPUB -> {
                val media = dir?.let { File(it, "media") }
                EpubParser.parse(file, mediaDir = media).document
            }
            BookFormat.DOCX -> {
                val doc = DocxParser.parse(file)
                EbookDocument(
                    plainText = doc.plainText,
                    toc = doc.toc,
                    title = null
                )
            }
            BookFormat.PDF -> error("PDF 不是电子书流式格式")
        }
    }
}
