package com.vibecoding.reader.data.parser

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import java.io.File

object PdfOutlineParser {
    @Volatile
    private var initialized = false

    fun ensureInitialized(context: android.content.Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    fun parseOutline(file: File, pageCount: Int): List<TocEntry> {
        return runCatching {
            PDDocument.load(file).use { doc ->
                val bookmarks = doc.documentCatalog?.documentOutline ?: return emptyList()
                val result = mutableListOf<TocEntry>()
                var item = bookmarks.firstChild
                while (item != null) {
                    collect(item, 0, doc, result)
                    item = item.nextSibling
                }
                result
            }
        }.getOrElse {
            // 无 outline 时用页码列表（稀疏）
            buildPageListToc(pageCount)
        }.ifEmpty { buildPageListToc(pageCount) }
    }

    private fun collect(
        item: PDOutlineItem,
        level: Int,
        doc: PDDocument,
        out: MutableList<TocEntry>
    ) {
        val title = item.title?.trim().orEmpty().ifBlank { "未命名" }
        val pageIndex = runCatching {
            val page = item.findDestinationPage(doc)
            if (page != null) doc.pages.indexOf(page) else -1
        }.getOrDefault(-1)
        if (pageIndex >= 0) {
            out += TocEntry(
                title = title.take(80),
                position = ReaderPosition.PageIndex(pageIndex).serialize(),
                level = level
            )
        }
        var child = item.firstChild
        while (child != null) {
            collect(child, level + 1, doc, out)
            child = child.nextSibling
        }
    }

    private fun buildPageListToc(pageCount: Int): List<TocEntry> {
        if (pageCount <= 0) return emptyList()
        val step = when {
            pageCount <= 30 -> 1
            pageCount <= 100 -> 5
            else -> 10
        }
        return (0 until pageCount step step).map { i ->
            TocEntry(
                title = "第 ${i + 1} 页",
                position = ReaderPosition.PageIndex(i).serialize(),
                level = 0
            )
        }
    }
}
