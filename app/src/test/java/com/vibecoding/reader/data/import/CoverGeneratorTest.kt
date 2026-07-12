package com.vibecoding.reader.data.import

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGeneratorTest {
    @Test
    fun buildExcerptSkipsBlankAndLimitsLength() {
        val text = """
            
            第一章 开始
            这是第一段正文，用来做封面节选。
            
            第二段继续描写风景。
        """.trimIndent()
        val excerpt = CoverGenerator.buildExcerpt(text, maxChars = 40)
        assertTrue(excerpt.isNotBlank())
        assertFalse(excerpt.startsWith("\n"))
        assertTrue(excerpt.length <= 41) // 可能带省略号
    }

    @Test
    fun buildExcerptEmpty() {
        assertTrue(CoverGenerator.buildExcerpt("   ").isEmpty())
    }
}
