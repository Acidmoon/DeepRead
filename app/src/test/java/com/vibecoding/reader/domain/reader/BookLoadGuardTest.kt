package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeoutException

class BookLoadGuardTest {
    @Test
    fun sizeThresholds() {
        assertFalse(BookLoadGuard.isLarge(1024))
        assertTrue(BookLoadGuard.isLarge(BookLoadGuard.LARGE_WARN_BYTES))
        assertTrue(BookLoadGuard.exceedsHardLimit(BookLoadGuard.HARD_LIMIT_BYTES + 1))
        assertFalse(BookLoadGuard.exceedsHardLimit(BookLoadGuard.HARD_LIMIT_BYTES))
    }

    @Test
    fun precheckMissingFile() {
        val f = File("definitely-missing-book-xyz.txt")
        val err = BookLoadGuard.precheck(f)
        assertNotNull(err)
        assertTrue(err!!.contains("丢失") || err.contains("文件"))
    }

    @Test
    fun precheckEmptyFile() {
        val f = File.createTempFile("emptybk", ".txt")
        f.writeBytes(ByteArray(0))
        val err = BookLoadGuard.precheck(f)
        assertNotNull(err)
        f.delete()
    }

    @Test
    fun precheckOkSmallFile() {
        val f = File.createTempFile("okbook", ".txt")
        f.writeText("hello world content")
        assertNull(BookLoadGuard.precheck(f))
        f.delete()
    }

    /** 造一个稀疏大文件，避免测试真的写几十 MB 数据 */
    private fun sparseFile(suffix: String, bytes: Long): File {
        val f = File.createTempFile("bigbook", suffix)
        java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        return f
    }

    @Test
    fun precheckLargeEbookRejected() {
        val f = sparseFile(".txt", BookLoadGuard.HARD_LIMIT_BYTES + 1)
        val err = BookLoadGuard.precheck(f, BookFormat.TXT)
        assertNotNull(err)
        assertTrue(err!!.contains("过大"))
        f.delete()
    }

    @Test
    fun precheckLargePdfAllowed() {
        // PDF 按页流式渲染，超过 64MB 也可打开
        val f = sparseFile(".pdf", BookLoadGuard.HARD_LIMIT_BYTES + 1)
        assertNull(BookLoadGuard.precheck(f, BookFormat.PDF))
        f.delete()
    }

    @Test
    fun precheckHugePdfStillRejected() {
        val f = sparseFile(".pdf", BookLoadGuard.PDF_HARD_LIMIT_BYTES + 1)
        assertNotNull(BookLoadGuard.precheck(f, BookFormat.PDF))
        f.delete()
    }

    @Test
    fun classifyTimeoutAndOom() {
        assertTrue(BookLoadGuard.classifyError(TimeoutException()).contains("超时"))
        assertTrue(BookLoadGuard.classifyError(OutOfMemoryError()).contains("内存"))
        assertTrue(BookLoadGuard.classifyError(IOException("文件损坏")).contains("损坏") ||
            BookLoadGuard.classifyError(IOException("文件损坏")).contains("损坏"))
    }

    @Test
    fun loadingMessageMentionsSizeForLarge() {
        val msg = BookLoadGuard.loadingMessage(BookLoadGuard.LARGE_WARN_BYTES)
        assertTrue(msg.contains("大文件"))
    }

    @Test
    fun formatSize() {
        assertEquals("512B", BookLoadGuard.formatSize(512))
        assertTrue(BookLoadGuard.formatSize(2048).contains("KB"))
    }
}
