package com.vibecoding.reader.domain.reader

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
