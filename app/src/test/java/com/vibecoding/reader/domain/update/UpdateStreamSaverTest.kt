package com.vibecoding.reader.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class UpdateStreamSaverTest {
    @Test
    fun writesAndHashes() {
        val data = "hello-update".toByteArray()
        val sha = Sha256Digest.ofBytes(data)
        val dir = createTempDirectory("upd").toFile()
        val dest = File(dir, "a.apk")
        var last = 0L
        UpdateStreamSaver.save(
            input = ByteArrayInputStream(data),
            dest = dest,
            expectedBytes = data.size.toLong(),
            expectedSha256 = sha,
            maxBytes = 1024,
            cancelled = { false },
            onProgress = { d, _ -> last = d }
        )
        assertEquals(data.size.toLong(), last)
        assertTrue(dest.isFile)
        assertEquals(data.size.toLong(), dest.length())
        dir.deleteRecursively()
    }

    @Test
    fun hashMismatchDeletesFile() {
        val data = "hello-update".toByteArray()
        val dir = createTempDirectory("upd").toFile()
        val dest = File(dir, "a.apk")
        try {
            UpdateStreamSaver.save(
                input = ByteArrayInputStream(data),
                dest = dest,
                expectedBytes = data.size.toLong(),
                expectedSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                maxBytes = 1024,
                cancelled = { false },
                onProgress = { _, _ -> }
            )
            fail()
        } catch (e: UpdateError.Verification) {
            assertTrue(e.userMessage.contains("校验"))
            assertTrue(!dest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizeAndCancel() {
        val data = ByteArray(50) { 1 }
        val dir = createTempDirectory("upd").toFile()
        val dest = File(dir, "a.apk")
        try {
            UpdateStreamSaver.save(
                input = ByteArrayInputStream(data),
                dest = dest,
                expectedBytes = 10,
                expectedSha256 = Sha256Digest.ofBytes(data.copyOf(10)),
                maxBytes = 10,
                cancelled = { false },
                onProgress = { _, _ -> }
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(!dest.exists())
        }
        try {
            UpdateStreamSaver.save(
                input = ByteArrayInputStream(data),
                dest = dest,
                expectedBytes = data.size.toLong(),
                expectedSha256 = Sha256Digest.ofBytes(data),
                maxBytes = 1024,
                cancelled = { true },
                onProgress = { _, _ -> }
            )
            fail()
        } catch (e: UpdateError.Cancelled) {
            assertTrue(!dest.exists())
        }
        dir.deleteRecursively()
    }

    @Test
    fun spaceCheckIncludesMargin() {
        assertNotNull(UpdateStreamSaver.spaceError(10, 10))
        assertEquals(null, UpdateStreamSaver.spaceError(10, 10 + UpdateStreamSaver.SPACE_MARGIN_BYTES))
    }
}
