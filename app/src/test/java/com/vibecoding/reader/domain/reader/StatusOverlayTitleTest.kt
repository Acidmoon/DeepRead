package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusOverlayTitleTest {

    private fun chapter(title: String, offset: Int, kind: TocKind = TocKind.CHAPTER) =
        TocEntry(title = title, position = "char:$offset", kind = kind)

    private fun pdfOutline(title: String, page: Int) =
        TocEntry(title = title, position = "page:$page", kind = TocKind.CHAPTER)

    @Test
    fun ebook_picksCurrentChapterByOffset() {
        val toc = listOf(
            chapter("卷一", 0, TocKind.VOLUME),
            chapter("第一章", 10),
            chapter("第二章", 100),
            chapter("第三章", 200)
        )
        assertEquals("第一章", StatusOverlayTitle.forEbook(toc, 10))
        assertEquals("第一章", StatusOverlayTitle.forEbook(toc, 50))
        assertEquals("第二章", StatusOverlayTitle.forEbook(toc, 100))
        assertEquals("第三章", StatusOverlayTitle.forEbook(toc, 999))
    }

    @Test
    fun ebook_skipsVolumeTitles() {
        val toc = listOf(
            chapter("第一卷", 0, TocKind.VOLUME),
            chapter("第1章", 5),
            chapter("第二卷", 50, TocKind.VOLUME),
            chapter("第2章", 60)
        )
        // 卷起点仍落在上一章之后、下一章之前 → 第1章
        assertEquals("第1章", StatusOverlayTitle.forEbook(toc, 20))
        assertEquals("第2章", StatusOverlayTitle.forEbook(toc, 60))
    }

    @Test
    fun ebook_beforeFirstChapterUsesFirst() {
        val toc = listOf(chapter("开篇", 20))
        assertEquals("开篇", StatusOverlayTitle.forEbook(toc, 0))
    }

    @Test
    fun ebook_emptyOrNullReturnsNull() {
        assertNull(StatusOverlayTitle.forEbook(emptyList(), 10))
        assertNull(StatusOverlayTitle.forEbook(listOf(chapter("A", 0)), null))
    }

    @Test
    fun pdf_picksOutlineByPage() {
        val toc = listOf(
            pdfOutline("封面", 0),
            pdfOutline("序章", 3),
            pdfOutline("正文", 10)
        )
        assertEquals("封面", StatusOverlayTitle.forPdf(toc, 0))
        assertEquals("封面", StatusOverlayTitle.forPdf(toc, 2))
        assertEquals("序章", StatusOverlayTitle.forPdf(toc, 3))
        assertEquals("正文", StatusOverlayTitle.forPdf(toc, 99))
    }

    @Test
    fun pdf_emptyOrInvalidReturnsNull() {
        assertNull(StatusOverlayTitle.forPdf(emptyList(), 0))
        assertNull(StatusOverlayTitle.forPdf(listOf(pdfOutline("A", 0)), null))
        assertNull(StatusOverlayTitle.forPdf(listOf(pdfOutline("A", 0)), -1))
        // 仅 char 型目录对 PDF 无效
        assertNull(
            StatusOverlayTitle.forPdf(
                listOf(TocEntry("章", "char:0", kind = TocKind.CHAPTER)),
                0
            )
        )
    }

    @Test
    fun leftLabel_formatsTitleAndProgress() {
        assertEquals("DeepRead", StatusOverlayTitle.buildLeftLabel(null, null))
        assertEquals("进度 37%", StatusOverlayTitle.buildLeftLabel(0.37f, null))
        assertEquals("第一章 · 37%", StatusOverlayTitle.buildLeftLabel(0.37f, "第一章"))
        assertEquals("序", StatusOverlayTitle.buildLeftLabel(null, "序"))
        assertEquals("进度 0%", StatusOverlayTitle.buildLeftLabel(0f, "  "))
    }
}
