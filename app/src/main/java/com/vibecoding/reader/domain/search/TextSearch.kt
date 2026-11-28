package com.vibecoding.reader.domain.search

import com.vibecoding.reader.domain.reader.TextSource
import com.vibecoding.reader.domain.model.ReaderPosition

/**
 * 书内全文搜索（纯函数，便于单测）。
 * 在 [text] 中查找 [query]（忽略大小写），返回最多 [maxResults] 条命中。
 *
 * 窗口化 [TextSource] 走分块流式扫描，不会把全书读回内存。
 */
object TextSearch {

    data class Hit(
        val offset: Int,
        val snippet: String,
        val position: String
    )

    fun search(
        text: String,
        query: String,
        maxResults: Int = 100,
        snippetRadius: Int = 24
    ): List<Hit> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty() || maxResults <= 0) return emptyList()

        val hay = text.lowercase()
        val needle = q.lowercase()
        if (needle.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>(minOf(maxResults, 32))
        var startAt = 0
        while (hits.size < maxResults) {
            val idx = hay.indexOf(needle, startAt)
            if (idx < 0) break
            val snipStart = (idx - snippetRadius).coerceAtLeast(0)
            val snipEnd = (idx + needle.length + snippetRadius).coerceAtMost(text.length)
            var snippet = text.substring(snipStart, snipEnd).replace('\n', ' ').trim()
            if (snipStart > 0) snippet = "…$snippet"
            if (snipEnd < text.length) snippet = "$snippet…"
            hits += Hit(
                offset = idx,
                snippet = snippet,
                position = ReaderPosition.CharOffset(idx).serialize()
            )
            startAt = idx + maxOf(needle.length, 1)
        }
        return hits
    }

    /**
     * 在 [source] 上搜索。若已有全文则走内存路径；否则按块读取，
     * 相邻块重叠 [query] 长度，避免命中落在分块边界时漏检。
     */
    fun search(
        source: TextSource,
        query: String,
        maxResults: Int = 100,
        snippetRadius: Int = 24,
        chunkChars: Int = 64 * 1024
    ): List<Hit> {
        val full = source.fullText
        if (full != null) return search(full, query, maxResults, snippetRadius)

        val q = query.trim()
        if (q.isEmpty() || source.totalLength <= 0 || maxResults <= 0) return emptyList()
        val needle = q.lowercase()
        if (needle.isEmpty()) return emptyList()

        val hits = ArrayList<Hit>(minOf(maxResults, 32))
        val overlap = needle.length.coerceAtLeast(1)
        val total = source.totalLength
        var chunkStart = 0
        var acceptFrom = 0
        val step = chunkChars.coerceAtLeast(overlap + 1)
        while (chunkStart < total && hits.size < maxResults) {
            val chunkEnd = minOf(total, chunkStart + step)
            val window = source.readRange(chunkStart, chunkEnd)
            val hay = window.text.lowercase()
            var local = 0
            while (hits.size < maxResults) {
                val idx = hay.indexOf(needle, local)
                if (idx < 0) break
                val global = window.startOffset + idx
                if (global >= acceptFrom) {
                    val snipStart = (global - snippetRadius).coerceAtLeast(0)
                    val snipEnd = (global + needle.length + snippetRadius).coerceAtMost(total)
                    val snippetText = if (snipStart >= window.startOffset && snipEnd <= window.endOffset) {
                        window.slice(snipStart, snipEnd)
                    } else {
                        source.readRange(snipStart, snipEnd).text
                    }.replace('\n', ' ').trim()
                    var snippet = snippetText
                    if (snipStart > 0) snippet = "…$snippet"
                    if (snipEnd < total) snippet = "$snippet…"
                    hits += Hit(
                        offset = global,
                        snippet = snippet,
                        position = ReaderPosition.CharOffset(global).serialize()
                    )
                }
                local = idx + maxOf(needle.length, 1)
            }
            if (chunkEnd >= total) break
            // 上一块未能完整容纳的命中从 (end - needle + 1) 起补搜，避免边界漏检或重复
            acceptFrom = (chunkEnd - needle.length + 1).coerceAtLeast(chunkStart + 1)
            chunkStart = (chunkEnd - overlap).coerceAtLeast(0)
            if (chunkStart >= chunkEnd) break
        }
        return hits
    }
}
