package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.ReaderPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun charProgressRoundTrip() {
        val len = 1000
        val mid = ReadingProgress.charOffsetFromProgress(0.5f, len)
        assertEquals(500, mid)
        assertEquals(0.5f, ReadingProgress.progressFromCharOffset(mid, len), 0.001f)
        val pos = ReadingProgress.positionFromProgress(0.25f, len)
        val parsed = ReaderPosition.parse(pos) as ReaderPosition.CharOffset
        assertEquals(250, parsed.offset)
        assertTrue(parsed.offset in 0 until len)
    }

    @Test
    fun clampsProgress() {
        assertEquals(0, ReadingProgress.charOffsetFromProgress(-1f, 100))
        assertEquals(99, ReadingProgress.charOffsetFromProgress(2f, 100))
    }

    @Test
    fun pdfPageProgress() {
        assertEquals(0, ReadingProgress.pageFromProgress(0f, 10))
        assertEquals(9, ReadingProgress.pageFromProgress(1f, 10))
        val pos = ReadingProgress.pagePositionFromProgress(0.5f, 10)
        val page = (ReaderPosition.parse(pos) as ReaderPosition.PageIndex).page
        assertTrue(page in 0..9)
    }
}
