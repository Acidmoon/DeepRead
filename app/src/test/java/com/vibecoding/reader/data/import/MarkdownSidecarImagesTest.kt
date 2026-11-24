package com.vibecoding.reader.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownSidecarImagesTest {
    @Test
    fun findsMarkdownAndHtmlImages() {
        val md = "hello ![a](pics/one.png) and <img src=\"pics/two.jpg\">"
        val src = MarkdownSidecarImages.findLocalSources(md)
        assertEquals(listOf("pics/one.png", "pics/two.jpg"), src)
    }

    @Test
    fun copiesAndRewritesSafeImages() {
        val srcDir = File.createTempFile("mdsrc", "dir").apply { delete(); mkdirs() }
        val pics = File(srcDir, "pics").apply { mkdirs() }
        File(pics, "one.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        val bookDir = File.createTempFile("mdbook", "dir").apply { delete(); mkdirs() }
        val md = "![图](pics/one.png)\n"
        val (rewritten, count) = MarkdownSidecarImages.copyAndRewrite(md, srcDir, bookDir)
        assertEquals(1, count)
        assertTrue(rewritten.contains("media/"))
        assertFalse(rewritten.contains("pics/one.png"))
        assertTrue(File(bookDir, "media").listFiles()?.isNotEmpty() == true)
        srcDir.deleteRecursively()
        bookDir.deleteRecursively()
    }

    @Test
    fun skipsTraversalReferences() {
        val srcDir = File.createTempFile("mdsrc", "dir").apply { delete(); mkdirs() }
        val bookDir = File.createTempFile("mdbook", "dir").apply { delete(); mkdirs() }
        val secret = File.createTempFile("secret", ".png").apply { writeBytes(byteArrayOf(9)) }
        val md = "![x](../${secret.name})\n"
        val (rewritten, count) = MarkdownSidecarImages.copyAndRewrite(md, srcDir, bookDir)
        assertEquals(0, count)
        assertEquals(md, rewritten)
        srcDir.deleteRecursively()
        bookDir.deleteRecursively()
        secret.delete()
    }

    @Test
    fun unsafeRelativeDetectsParentEscape() {
        assertTrue(MarkdownSidecarImages.isUnsafeRelative("../secret.png"))
        assertTrue(MarkdownSidecarImages.isUnsafeRelative("a/../../secret.png"))
        assertFalse(MarkdownSidecarImages.isUnsafeRelative("pics/one.png"))
        assertFalse(MarkdownSidecarImages.isUnsafeRelative("./pics/one.png"))
    }
}
