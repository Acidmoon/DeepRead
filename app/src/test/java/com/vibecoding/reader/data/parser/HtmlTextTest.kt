package com.vibecoding.reader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {
    @Test
    fun stripsTagsAndKeepsText() {
        val html = "<html><body><h1>Hello</h1><p>World<br/>Next</p></body></html>"
        val text = HtmlText.extract(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World"))
        assertTrue(text.contains("Next"))
        assertTrue(!text.contains("<"))
    }

    @Test
    fun decodesEntities() {
        assertEquals("a & b", HtmlText.extract("a &amp; b"))
    }
}
