package com.vibecoding.reader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfTextIndexStoreTest {
    @Test
    fun writeAndLoadRoundTrip() {
        val dir = File.createTempFile("bookdir", "d").apply { delete(); mkdirs() }
        val pdf = File(dir, "original.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val pages = listOf("page one", "page two")
        PdfTextIndex.writePages(dir, pdf, pages)
        assertTrue(PdfTextIndex.isUpToDate(dir, pdf))
        assertEquals(pages, PdfTextIndex.loadPages(dir))
        pdf.appendBytes(byteArrayOf(6))
        assertFalse(PdfTextIndex.isUpToDate(dir, pdf))
        dir.deleteRecursively()
    }
}
