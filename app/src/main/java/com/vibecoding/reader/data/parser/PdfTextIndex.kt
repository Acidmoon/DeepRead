package com.vibecoding.reader.data.parser

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * 将 PDF 文字层按页缓存到书籍目录，供全文搜索使用。不做 OCR。
 */
object PdfTextIndex {
    private const val DIR = "text-index"
    private const val META = "meta.json"

    fun indexDir(bookDir: File): File = File(bookDir, DIR)

    fun isUpToDate(bookDir: File, pdfFile: File): Boolean {
        val metaFile = File(indexDir(bookDir), META)
        if (!metaFile.exists()) return false
        return runCatching {
            val meta = readMeta(metaFile)
            meta.length == pdfFile.length() &&
                meta.mtime == pdfFile.lastModified() &&
                meta.pages > 0 &&
                File(indexDir(bookDir), pageFileName(0)).exists()
        }.getOrDefault(false)
    }

    fun loadPages(bookDir: File): List<String>? {
        val dir = indexDir(bookDir)
        val metaFile = File(dir, META)
        if (!metaFile.exists()) return null
        val pages = runCatching { readMeta(metaFile).pages }.getOrDefault(0)
        if (pages <= 0) return null
        return (0 until pages).map { i ->
            val f = File(dir, pageFileName(i))
            if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        }
    }

    fun writePages(bookDir: File, pdfFile: File, pages: List<String>) {
        val dir = indexDir(bookDir).apply { mkdirs() }
        pages.forEachIndexed { i, text ->
            File(dir, pageFileName(i)).writeText(text)
        }
        File(dir, META).writeText(
            "length=${pdfFile.length()}\nmtime=${pdfFile.lastModified()}\npages=${pages.size}\n"
        )
    }

    /**
     * 抽取全部页的文字层。调用前需 [PdfOutlineParser.ensureInitialized]。
     */
    fun extractAll(pdfFile: File, password: String? = null): List<String> {
        val memory = MemoryUsageSetting.setupTempFileOnly()
        val doc = if (password.isNullOrEmpty()) {
            PDDocument.load(pdfFile, memory)
        } else {
            PDDocument.load(pdfFile, password, memory)
        }
        doc.use { document ->
            val stripper = PDFTextStripper()
            val count = document.numberOfPages
            return (0 until count).map { i ->
                stripper.startPage = i + 1
                stripper.endPage = i + 1
                runCatching { stripper.getText(document).orEmpty() }.getOrDefault("")
            }
        }
    }

    private data class Meta(val length: Long, val mtime: Long, val pages: Int)

    private fun readMeta(file: File): Meta {
        val map = file.readLines()
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()
        return Meta(
            length = map["length"]?.toLongOrNull() ?: -1L,
            mtime = map["mtime"]?.toLongOrNull() ?: -1L,
            pages = map["pages"]?.toIntOrNull() ?: 0
        )
    }

    private fun pageFileName(index: Int): String = "p-$index.txt"
}
