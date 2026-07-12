package com.vibecoding.reader.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test
    fun findsKnownKeywordAndValidOffset() {
        val text = "前言一段。\n第一章 开端\n正文里出现关键词「长安」一次。\n结尾。"
        val hits = TextSearch.search(text, "长安")
        assertEquals(1, hits.size)
        assertEquals(text.indexOf("长安"), hits[0].offset)
        assertTrue(hits[0].position.startsWith("char:"))
        assertTrue(hits[0].snippet.contains("长安"))
        assertTrue(hits[0].offset in text.indices)
    }

    @Test
    fun caseInsensitiveAndMultipleHits() {
        val text = "Hello world. hello again. HELLO end."
        val hits = TextSearch.search(text, "hello", maxResults = 10)
        assertEquals(3, hits.size)
        assertTrue(hits[0].offset < hits[1].offset)
        assertTrue(hits[1].offset < hits[2].offset)
    }

    @Test
    fun emptyQueryReturnsEmpty() {
        assertTrue(TextSearch.search("abc", "  ").isEmpty())
        assertTrue(TextSearch.search("", "a").isEmpty())
    }

    @Test
    fun respectsMaxResults() {
        val text = "aa aa aa aa aa"
        val hits = TextSearch.search(text, "aa", maxResults = 2)
        assertEquals(2, hits.size)
    }
}
