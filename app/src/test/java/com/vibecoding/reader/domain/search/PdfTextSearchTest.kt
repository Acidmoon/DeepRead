package com.vibecoding.reader.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextSearchTest {
    @Test
    fun searchesAcrossPagesWithoutOcrAssumption() {
        val pages = listOf("第一章 开头", "中间页没有", "结尾出现关键词 旅程")
        val hits = PdfTextSearch.search(pages, "旅程")
        assertEquals(1, hits.size)
        assertEquals("page:2", hits[0].position)
        assertTrue(hits[0].snippet.contains("第 3 页"))
        assertTrue(hits[0].snippet.contains("旅程"))
    }

    @Test
    fun emptyQueryOrPages() {
        assertTrue(PdfTextSearch.search(emptyList(), "a").isEmpty())
        assertTrue(PdfTextSearch.search(listOf("abc"), "  ").isEmpty())
    }
}
