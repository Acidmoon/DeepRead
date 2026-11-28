package com.vibecoding.reader.data.import

import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.reader.BookLoadGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ImportPrecheckTest {

    @Test
    fun legacyDocDetectedByExtension() {
        assertTrue(ImportPrecheck.isLegacyMsWord("小说.doc", null))
        assertTrue(ImportPrecheck.isLegacyMsWord("path/Foo.DOC", "application/msword"))
        assertFalse(ImportPrecheck.isLegacyMsWord("小说.docx", "application/msword"))
        assertFalse(ImportPrecheck.isLegacyMsWord("a.md", "text/markdown"))
    }

    @Test
    fun resolveFormatPrefersFileName() {
        assertEquals(BookFormat.PDF, ImportPrecheck.resolveFormat("a.pdf", "application/octet-stream"))
        assertEquals(BookFormat.DOCX, ImportPrecheck.resolveFormat("x.docx", null))
        assertNull(ImportPrecheck.resolveFormat("x.doc", "application/msword"))
    }

    @Test
    fun sizeErrorKnownEbookAndPdf() {
        assertNull(
            ImportPrecheck.sizeError(BookLoadGuard.HARD_LIMIT_BYTES + 1, BookFormat.TXT)
        )
        assertNotNull(
            ImportPrecheck.sizeError(BookLoadGuard.HARD_LIMIT_BYTES + 1, BookFormat.EPUB)
        )
        assertNotNull(
            ImportPrecheck.sizeError(BookLoadGuard.TXT_HARD_LIMIT_BYTES + 1, BookFormat.TXT)
        )
        assertNull(
            ImportPrecheck.sizeError(BookLoadGuard.HARD_LIMIT_BYTES + 1, BookFormat.PDF)
        )
        assertNotNull(
            ImportPrecheck.sizeError(BookLoadGuard.PDF_HARD_LIMIT_BYTES + 1, BookFormat.PDF)
        )
        assertNull(ImportPrecheck.sizeError(-1, BookFormat.TXT))
        assertNull(ImportPrecheck.sizeError(0, BookFormat.TXT))
    }

    @Test
    fun spaceErrorIncludesMargin() {
        val needed = 10L * 1024 * 1024
        assertNotNull(
            ImportPrecheck.spaceError(needed, needed) // 缺 margin
        )
        assertNull(
            ImportPrecheck.spaceError(needed, needed + ImportPrecheck.SPACE_MARGIN_BYTES)
        )
        assertNull(ImportPrecheck.spaceError(0, 100))
    }

    @Test
    fun existingBySourceUri() {
        val books = listOf(
            "content://a/1" to "id-a",
            "content://b/2" to "id-b"
        )
        assertEquals("id-a", ImportPrecheck.existingBySourceUri("content://a/1", books))
        assertNull(ImportPrecheck.existingBySourceUri("content://missing", books))
        assertNull(ImportPrecheck.existingBySourceUri("", books))
        assertNull(ImportPrecheck.existingBySourceUri("  ", books))
    }

    @Test
    fun copyCappedWithinLimit() {
        val data = ByteArray(1024) { 1 }
        val out = ByteArrayOutputStream()
        val n = ImportPrecheck.copyCapped(ByteArrayInputStream(data), out, 2048)
        assertEquals(1024, n)
        assertEquals(1024, out.size())
    }

    @Test(expected = IOException::class)
    fun copyCappedAbortsWhenExceeded() {
        val data = ByteArray(100) { 1 }
        ImportPrecheck.copyCapped(ByteArrayInputStream(data), ByteArrayOutputStream(), 50)
    }

    @Test
    fun effectiveFileNameUsesMimeWhenNoExtension() {
        assertEquals("未命名文档.pdf", ImportPrecheck.effectiveFileName(null, "application/pdf"))
        assertEquals(
            "document.pdf",
            ImportPrecheck.effectiveFileName("document", "Application/PDF; charset=binary")
        )
        assertEquals("a.pdf", ImportPrecheck.effectiveFileName("a.pdf", "text/plain"))
        assertEquals("未命名文档", ImportPrecheck.effectiveFileName(null, null))
    }

    @Test
    fun resolveFormatAcceptsMimeCaseAndParameters() {
        assertEquals(BookFormat.PDF, ImportPrecheck.resolveFormat("document", "Application/PDF"))
        assertEquals(
            BookFormat.PDF,
            ImportPrecheck.resolveFormat("document", "application/pdf; charset=binary")
        )
        assertEquals(BookFormat.MD, ImportPrecheck.resolveFormat("x", "text/markdown"))
    }

    @Test
    fun noReadPermissionMessageMentionsSource() {
        val msg = ImportPrecheck.noReadPermissionMessage("笔记.pdf")
        assertTrue(msg.contains("权限"))
        assertTrue(msg.contains("笔记.pdf"))
    }
}
