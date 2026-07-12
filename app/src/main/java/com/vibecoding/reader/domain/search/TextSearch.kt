package com.vibecoding.reader.domain.search

import com.vibecoding.reader.domain.model.ReaderPosition

/**
 * 书内全文搜索（纯函数，便于单测）。
 * 在 [text] 中查找 [query]（忽略大小写），返回最多 [maxResults] 条命中。
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
}
