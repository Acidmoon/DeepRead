package com.vibecoding.reader.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfTextIndexStoreTest {
    @Test
    fun writeAndLoadRoundTrip() {
        val dir = File.createTempFile("idx", "d").apply { delete(); mkdirs() }
        val pdf = File(dir, "original.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val pages = listOf("page one", "page two")
        PdfTextIndex.writePages(dir, pdf, pages)
        assertTrue(PdfTextIndex.isUpToDate(dir, pdf))
        assertEquals(pages, PdfTextIndex.loadPages(dir))
        pdf.appendBytes(byteArrayOf(6))
        assertFalse(PdfTextIndex.isUpToDate(dir, pdf))
        dir.deleteRecursively()
    }

    @Test
    fun storageDirStaysUnderNoBackupRoot() {
        val noBackup = File.createTempFile("nobackup", "d").apply { delete(); mkdirs() }
        val dir = PdfTextIndex.storageDir(noBackup, "book-1")
        assertEquals(File(noBackup, "pdf-text-index/book-1").canonicalFile, dir.canonicalFile)
        assertTrue(dir.canonicalPath.startsWith(noBackup.canonicalPath + "/"))
        noBackup.deleteRecursively()
    }

    @Test
    fun deleteLegacyRemovesBookDirIndex() {
        val bookDir = File.createTempFile("book", "d").apply { delete(); mkdirs() }
        val legacy = File(bookDir, "text-index").apply { mkdirs() }
        File(legacy, "p-0.txt").writeText("secret plaintext")
        PdfTextIndex.deleteLegacyInBookDir(bookDir)
        assertFalse(legacy.exists())
        bookDir.deleteRecursively()
    }
}
