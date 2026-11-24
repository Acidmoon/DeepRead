package com.vibecoding.reader.data.parser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SafeLocalPathTest {
    @Test
    fun resolvesRelativeInsideBase() {
        val dir = File.createTempFile("safeimg", "dir").apply { delete(); mkdirs() }
        val img = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val resolved = SafeLocalPath.resolveUnder(dir, "a.png")
        assertNotNull(resolved)
        assertTrue(resolved!!.canonicalFile == img.canonicalFile)
        dir.deleteRecursively()
    }

    @Test
    fun rejectsPathTraversal() {
        val dir = File.createTempFile("safeimg", "dir").apply { delete(); mkdirs() }
        val outside = File.createTempFile("secret", ".png").apply { writeBytes(byteArrayOf(9)) }
        val escaped = SafeLocalPath.resolveUnder(dir, "../${outside.name}")
        // even if canonical happens to exist, it must not be outside dir
        if (escaped != null) {
            assertTrue(SafeLocalPath.isInside(dir, escaped))
        } else {
            assertNull(escaped)
        }
        // explicit escape to a known outside file via absolute path
        assertNull(SafeLocalPath.resolveUnder(dir, outside.absolutePath))
        dir.deleteRecursively()
        outside.delete()
    }

    @Test
    fun rejectsRemote() {
        val dir = File.createTempFile("safeimg", "dir").apply { delete(); mkdirs() }
        assertNull(SafeLocalPath.resolveUnder(dir, "https://example.com/a.png"))
        dir.deleteRecursively()
    }
}
