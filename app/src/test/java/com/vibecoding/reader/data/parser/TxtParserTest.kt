package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.TocKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtParserTest {

    @Test
    fun buildTocDetectsChineseChaptersOnly() {
        val text = """
            序言
            这是序言内容，不算章。

            第一章 开始
            正文一段。

            随便一句话
            1. 这不是章

            第二章 继续
            更多正文。

            第一节 不该识别
            第三回 不该识别
        """.trimIndent()
        val toc = TxtParser.buildToc(text)
        assertEquals(2, toc.size)
        assertTrue(toc.all { it.kind == TocKind.CHAPTER })
        assertTrue(toc[0].title.startsWith("第一章"))
        assertTrue(toc[1].title.startsWith("第二章"))
    }

    @Test
    fun buildTocDetectsVolumeAndChapter() {
        val text = """
            第一卷 风起
            开卷语

            第一章 少年
            内容A

            第二章 远行
            内容B

            第二卷 云涌
            第三章 对决
            内容C
        """.trimIndent()
        val toc = TxtParser.buildToc(text)
        assertEquals(5, toc.size)
        assertEquals(TocKind.VOLUME, toc[0].kind)
        assertEquals(0, toc[0].level)
        assertEquals(TocKind.CHAPTER, toc[1].kind)
        assertEquals(1, toc[1].level)
        assertEquals(TocKind.VOLUME, toc[3].kind)
        assertEquals(0, toc[3].level)
        assertEquals(TocKind.CHAPTER, toc[4].kind)
        assertEquals(1, toc[4].level)
    }

    @Test
    fun buildTocEnglishChapterAndVolume() {
        val text = """
            Volume 1: Beginnings
            intro

            Chapter 1 The Start
            body

            Chapter 2
            more

            Volume II
            Chapter 3
        """.trimIndent()
        val toc = TxtParser.buildToc(text)
        assertTrue(toc.any { it.kind == TocKind.VOLUME && it.title.contains("Volume 1", true) })
        assertTrue(toc.any { it.kind == TocKind.CHAPTER && it.title.contains("Chapter 1", true) })
        assertTrue(toc.count { it.kind == TocKind.CHAPTER } >= 3)
    }

    @Test
    fun rejectsNonChapterHeadings() {
        assertNull(TxtParser.classifyHeading("序章开篇"))
        assertNull(TxtParser.classifyHeading("1. 引言"))
        assertNull(TxtParser.classifyHeading("第一节 开始"))
        assertNull(TxtParser.classifyHeading("第三回 风云"))
        assertNull(TxtParser.classifyHeading("这是正文里提到第一章的句子很长很长超过限制也会被长度挡下吗"))
        // 正文中夹带不算（无行首结构时整行需完整匹配）
        assertNull(TxtParser.classifyHeading("他说第一章很好看"))
    }

    @Test
    fun acceptsStrictHeadings() {
        assertEquals(TocKind.CHAPTER, TxtParser.classifyHeading("第一章")?.first)
        assertEquals(TocKind.CHAPTER, TxtParser.classifyHeading("第12章 远征")?.first)
        assertEquals(TocKind.CHAPTER, TxtParser.classifyHeading("Chapter 3: Fire")?.first)
        assertEquals(TocKind.VOLUME, TxtParser.classifyHeading("第一卷 序")?.first)
        assertEquals(TocKind.VOLUME, TxtParser.classifyHeading("卷2")?.first)
        assertEquals(TocKind.VOLUME, TxtParser.classifyHeading("Volume 2")?.first)
    }

    @Test
    fun chaptersOnlyHaveLevelZero() {
        val text = "第一章 A\n文\n第二章 B\n"
        val toc = TxtParser.buildToc(text)
        assertTrue(toc.isNotEmpty())
        assertTrue(toc.all { it.level == 0 && it.kind == TocKind.CHAPTER })
    }

    @Test
    fun titleFromFileName() {
        assertEquals("红楼梦", TxtParser.titleFromFileName("红楼梦.txt"))
        assertEquals("demo", TxtParser.titleFromFileName("demo"))
    }

    @Test
    fun falseNegativesNotFromBodyLines() {
        val text = """
            第一章
            他说「第一章」很精彩，这行不是标题。
            第二章
        """.trimIndent()
        val toc = TxtParser.buildToc(text)
        assertEquals(2, toc.size)
        assertFalse(toc.any { it.title.contains("他说") })
    }
}
