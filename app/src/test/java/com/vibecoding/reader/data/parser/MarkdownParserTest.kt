package com.vibecoding.reader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsIntoToc() {
        val md = """
            # 书名
            引言段落。

            ## 第一章
            内容 A

            ### 小节
            细节

            ## 第二章
            内容 B
        """.trimIndent()
        val doc = MarkdownParser.parse(md, "demo")
        assertTrue(doc.toc.size >= 3)
        assertTrue(doc.toc.any { it.title.contains("第一章") })
        assertTrue(doc.plainText.contains("引言段落"))
        assertTrue(doc.markdownSource!!.contains("# 书名"))
    }

    @Test
    fun stripsInlineMarkdownInPlainText() {
        val md = "这是 **粗体** 和 [链接](http://a.com) 以及 `code`。"
        val doc = MarkdownParser.parse(md)
        assertTrue(doc.plainText.contains("粗体"))
        assertTrue(doc.plainText.contains("链接"))
        assertTrue(doc.plainText.contains("code"))
        assertTrue(!doc.plainText.contains("**"))
    }

    @Test
    fun markdownBlocksParse() {
        val blocks = com.vibecoding.reader.ui.reader.text.MarkdownBlocks.parse(
            """
            # Title
            hello

            - item1
            > quote
            ```
            code
            ```
            """.trimIndent()
        )
        assertTrue(blocks.any { it is com.vibecoding.reader.domain.model.EbookBlock.Heading })
        assertTrue(blocks.any { it is com.vibecoding.reader.domain.model.EbookBlock.Bullet })
        assertTrue(blocks.any { it is com.vibecoding.reader.domain.model.EbookBlock.Quote })
        assertTrue(blocks.any { it is com.vibecoding.reader.domain.model.EbookBlock.Code })
    }

    @Test
    fun parsesImageAsBlockWhenFileExists() {
        val dir = java.io.File.createTempFile("mdimg", "dir").apply {
            delete()
            mkdirs()
        }
        val img = java.io.File(dir, "a.png")
        // 最小非法 png 也算文件存在；渲染失败会在 UI 降级
        img.writeBytes(byteArrayOf(1, 2, 3))
        val md = "前言\n\n![示意](a.png)\n\n后记\n"
        val doc = MarkdownParser.parse(md, baseDir = dir)
        assertTrue(doc.blocks.any { it is com.vibecoding.reader.domain.model.EbookBlock.Image })
        val image = doc.blocks.filterIsInstance<com.vibecoding.reader.domain.model.EbookBlock.Image>().first()
        assertEquals("示意", image.alt)
        dir.deleteRecursively()
    }
}
