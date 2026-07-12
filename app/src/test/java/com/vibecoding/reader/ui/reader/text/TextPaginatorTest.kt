package com.vibecoding.reader.ui.reader.text

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPaginatorLogicTest {
    @Test
    fun pageIndexForOffset() {
        val pages = listOf(
            TextPageBreak(0, 100),
            TextPageBreak(100, 200),
            TextPageBreak(200, 300)
        )
        assertEquals(0, TextPaginator.pageIndexForOffset(pages, 0))
        assertEquals(0, TextPaginator.pageIndexForOffset(pages, 50))
        assertEquals(1, TextPaginator.pageIndexForOffset(pages, 100))
        assertEquals(2, TextPaginator.pageIndexForOffset(pages, 250))
    }

    @Test
    fun progressForOffset() {
        assertEquals(0.5f, TextPaginator.progressForOffset(50, 100), 0.001f)
        assertEquals(1f, TextPaginator.progressForOffset(100, 100), 0.001f)
    }

    @Test
    fun buildChaptersFromToc() {
        val toc = listOf(
            TocEntry("第一章", ReaderPosition.CharOffset(0).serialize()),
            TocEntry("第二章", ReaderPosition.CharOffset(100).serialize()),
            TocEntry("第三章", ReaderPosition.CharOffset(250).serialize())
        )
        val chapters = TextPaginator.buildChapters(400, toc)
        assertEquals(3, chapters.size)
        assertEquals(0, chapters[0].start)
        assertEquals(100, chapters[0].end)
        assertEquals(100, chapters[1].start)
        assertEquals(250, chapters[1].end)
        assertEquals(250, chapters[2].start)
        assertEquals(400, chapters[2].end)
    }

    @Test
    fun buildChaptersWithoutToc() {
        val chapters = TextPaginator.buildChapters(1000, emptyList())
        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].start)
        assertEquals(1000, chapters[0].end)
    }

    @Test
    fun splitBlocksRespectsBounds() {
        val text = "abc\n".repeat(500)
        val blocks = TextPaginator.splitBlocks(text, 0, text.length, targetBlockChars = 200)
        assertTrue(blocks.isNotEmpty())
        assertEquals(0, blocks.first().startOffset)
        assertEquals(text.length, blocks.last().endOffset)
        // 块应连续
        for (i in 1 until blocks.size) {
            assertEquals(blocks[i - 1].endOffset, blocks[i].startOffset)
        }
    }
}
