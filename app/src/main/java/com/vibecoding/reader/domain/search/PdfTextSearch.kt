package com.vibecoding.reader.domain.search

import com.vibecoding.reader.domain.model.ReaderPosition

/**
 * 在已抽取的 PDF 文字层中搜索（不做 OCR）。
 */
object PdfTextSearch {

    fun search(
        pages: List<String>,
        query: String,
        maxResults: Int = 100,
        snippetRadius: Int = 24
    ): List<TextSearch.Hit> {
        if (pages.isEmpty() || query.trim().isEmpty() || maxResults <= 0) return emptyList()
        val hits = ArrayList<TextSearch.Hit>(minOf(maxResults, 32))
        pages.forEachIndexed { index, text ->
            if (hits.size >= maxResults) return hits
            val found = TextSearch.search(
                text = text,
                query = query,
                maxResults = maxResults - hits.size,
                snippetRadius = snippetRadius
            )
            for (hit in found) {
                hits += TextSearch.Hit(
                    offset = index,
                    snippet = "第 ${index + 1} 页：${hit.snippet}",
                    position = ReaderPosition.PageIndex(index).serialize()
                )
            }
        }
        return hits
    }
}
