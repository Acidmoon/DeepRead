package com.vibecoding.reader.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vibecoding.reader.data.parser.DocxParser
import com.vibecoding.reader.data.parser.PdfOutlineParser
import com.vibecoding.reader.data.parser.TxtParser
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BookImporter(
    private val context: Context,
    private val bookRepository: BookRepository
) {
    suspend fun importUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<Book>()
        val errors = mutableListOf<String>()
        for (uri in uris) {
            runCatching { importOne(uri) }
                .onSuccess { imported += it }
                .onFailure { errors += (it.message ?: "导入失败") }
        }
        ImportResult(imported, errors)
    }

    private suspend fun importOne(uri: Uri): Book {
        val name = queryDisplayName(uri) ?: "未命名文档"
        val format = BookFormat.fromFileName(name)
            ?: BookFormat.fromMime(context.contentResolver.getType(uri))
            ?: error("不支持的格式：$name")

        val bookId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val ext = when (format) {
            BookFormat.TXT -> "txt"
            BookFormat.PDF -> "pdf"
            BookFormat.DOCX -> "docx"
        }
        val dest = File(dir, "original.$ext")

        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取文件：$name")

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val title = TxtParser.titleFromFileName(name)
        val toc = extractToc(format, dest)
        saveToc(dir, toc)

        val now = System.currentTimeMillis()
        val book = Book(
            id = bookId,
            title = title,
            format = format,
            sourceUri = uri.toString(),
            localPath = dest.absolutePath,
            addedAt = now,
            lastOpenedAt = 0L,
            lastPosition = "",
            progressPercent = 0f,
            updatedAt = now
        )
        bookRepository.upsert(book)
        return book
    }

    private fun extractToc(format: BookFormat, file: File): List<TocEntry> {
        return when (format) {
            BookFormat.TXT -> {
                val text = TxtParser.readText(file)
                TxtParser.buildToc(text)
            }
            BookFormat.DOCX -> DocxParser.parse(file).toc
            BookFormat.PDF -> {
                PdfOutlineParser.ensureInitialized(context)
                val pageCount = runCatching {
                    android.graphics.pdf.PdfRenderer(
                        android.os.ParcelFileDescriptor.open(
                            file,
                            android.os.ParcelFileDescriptor.MODE_READ_ONLY
                        )
                    ).use { it.pageCount }
                }.getOrDefault(0)
                PdfOutlineParser.parseOutline(file, pageCount)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    companion object {
        /** 目录缓存格式版本；升级解析规则时递增，打开书籍时自动重解析 */
        const val TOC_CACHE_VERSION = 3

        fun loadToc(context: Context, bookId: String): List<TocEntry> {
            val file = File(context.filesDir, "books/$bookId/toc.json")
            if (!file.exists()) return emptyList()
            return runCatching {
                val root = JSONObject(file.readText())
                // 新格式：{ version, entries }
                if (root.has("entries")) {
                    val ver = root.optInt("version", 0)
                    if (ver < TOC_CACHE_VERSION) return emptyList() // 触发调用方重解析
                    parseEntries(root.getJSONArray("entries"))
                } else {
                    // 旧格式：纯数组 → 视为过期
                    emptyList()
                }
            }.getOrDefault(emptyList())
        }

        fun saveToc(dir: File, toc: List<TocEntry>) {
            val arr = JSONArray()
            toc.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("title", entry.title)
                        .put("position", entry.position)
                        .put("level", entry.level)
                        .put("kind", entry.kind.name)
                )
            }
            val root = JSONObject()
                .put("version", TOC_CACHE_VERSION)
                .put("entries", arr)
            File(dir, "toc.json").writeText(root.toString())
        }

        fun saveTocForBook(context: Context, bookId: String, toc: List<TocEntry>) {
            val dir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
            saveToc(dir, toc)
        }

        private fun parseEntries(arr: JSONArray): List<TocEntry> {
            return buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val kind = runCatching {
                        TocKind.valueOf(obj.optString("kind", TocKind.CHAPTER.name))
                    }.getOrDefault(TocKind.CHAPTER)
                    add(
                        TocEntry(
                            title = obj.getString("title"),
                            position = obj.getString("position"),
                            level = obj.optInt("level", 0),
                            kind = kind
                        )
                    )
                }
            }
        }
    }
}

data class ImportResult(
    val books: List<Book>,
    val errors: List<String>
)
