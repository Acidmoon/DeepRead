package com.vibecoding.reader.data.parser

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * 将 PDF 文字层按页缓存，供全文搜索使用。不做 OCR。
 *
 * 索引必须放在 [storageDir]（no_backup 下），不能写进可备份的书籍目录：
 * 加密 PDF 解锁后抽出的正文若落在 filesDir，会随系统备份以明文离开设备。
 */
object PdfTextIndex {
    private const val LEGACY_DIR = "text-index"
    private const val STORAGE_DIR = "pdf-text-index"
    private const val META = "meta.json"

    fun storageDir(noBackupFilesDir: File, bookId: String): File =
        File(noBackupFilesDir, "$STORAGE_DIR/$bookId")

    fun deleteStorage(noBackupFilesDir: File, bookId: String) {
        storageDir(noBackupFilesDir, bookId).deleteRecursively()
    }

    /** 删除早期写在 filesDir/books/{id}/text-index 的明文缓存，避免进入备份。 */
    fun deleteLegacyInBookDir(bookDir: File) {
        File(bookDir, LEGACY_DIR).deleteRecursively()
    }

    fun isUpToDate(indexDir: File, pdfFile: File): Boolean {
        val metaFile = File(indexDir, META)
        if (!metaFile.exists()) return false
        return runCatching {
            val meta = readMeta(metaFile)
            meta.length == pdfFile.length() &&
                meta.mtime == pdfFile.lastModified() &&
                meta.pages > 0 &&
                File(indexDir, pageFileName(0)).exists()
        }.getOrDefault(false)
    }

    fun loadPages(indexDir: File): List<String>? {
        val dir = indexDir
        val metaFile = File(dir, META)
        if (!metaFile.exists()) return null
        val pages = runCatching { readMeta(metaFile).pages }.getOrDefault(0)
        if (pages <= 0) return null
        return (0 until pages).map { i ->
            val f = File(dir, pageFileName(i))
            if (f.exists()) runCatching { f.readText() }.getOrDefault("") else ""
        }
    }

    fun writePages(indexDir: File, pdfFile: File, pages: List<String>) {
        val dir = indexDir.apply { mkdirs() }
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
