package com.vibecoding.reader.data.import

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.vibecoding.reader.data.parser.EbookLoader
import com.vibecoding.reader.data.parser.PdfOutlineParser
import com.vibecoding.reader.data.parser.TxtParser
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.FolderRepository
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.BookFolder
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
    private val bookRepository: BookRepository,
    private val folderRepository: FolderRepository? = null
) {
    suspend fun importUris(
        uris: List<Uri>,
        folderId: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val imported = mutableListOf<Book>()
        val errors = mutableListOf<String>()
        for (uri in uris) {
            runCatching { importOne(uri, folderId) }
                .onSuccess { imported += it }
                .onFailure { errors += (it.message ?: "导入失败") }
        }
        ImportResult(imported, errors)
    }

    /**
     * 从系统已有目录导入：
     * - 若 [existingFolderId] 为空：在书架新建同名文件夹，目录内支持的文件全部放入
     * - 若已在某文件夹内：文件导入到当前文件夹（不再新建）
     * 递归扫描子目录中的支持格式。
     */
    suspend fun importFromTree(
        treeUri: Uri,
        existingFolderId: String? = null
    ): TreeImportResult = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开所选文件夹")
        if (!root.isDirectory) error("所选不是文件夹")

        val folderName = root.name?.takeIf { it.isNotBlank() } ?: "导入文件夹"
        val targetFolderId: String
        val createdFolder: BookFolder?
        if (existingFolderId != null) {
            targetFolderId = existingFolderId
            createdFolder = null
        } else {
            val repo = folderRepository
                ?: error("无法创建文件夹")
            createdFolder = repo.createFolder(folderName)
            targetFolderId = createdFolder.id
        }

        val files = listSupportedDocuments(root)
        if (files.isEmpty()) {
            return@withContext TreeImportResult(
                folder = createdFolder,
                result = ImportResult(emptyList(), listOf("未找到可导入的文件（TXT/MD/EPUB/PDF/DOCX）"))
            )
        }

        val imported = mutableListOf<Book>()
        val errors = mutableListOf<String>()
        for (doc in files) {
            val uri = doc.uri
            val name = doc.name ?: "未命名"
            runCatching { importOne(uri, targetFolderId, displayNameOverride = name) }
                .onSuccess { imported += it }
                .onFailure { errors += "$name: ${it.message ?: "失败"}" }
        }
        targetFolderId.let { id -> folderRepository?.touchFolder(id) }

        TreeImportResult(
            folder = createdFolder,
            result = ImportResult(imported, errors)
        )
    }

    private fun listSupportedDocuments(root: DocumentFile): List<DocumentFile> {
        val out = ArrayList<DocumentFile>()
        fun walk(dir: DocumentFile) {
            val children = dir.listFiles()
            for (child in children) {
                when {
                    child.isDirectory -> walk(child)
                    child.isFile -> {
                        val name = child.name ?: continue
                        if (BookFormat.fromFileName(name) != null) {
                            out += child
                        }
                    }
                }
            }
        }
        walk(root)
        // 同名文件去重（保留先扫到的）
        return out.distinctBy { it.name?.lowercase() }
    }

    private suspend fun importOne(
        uri: Uri,
        folderId: String? = null,
        displayNameOverride: String? = null
    ): Book {
        val name = displayNameOverride
            ?: queryDisplayName(uri)
            ?: "未命名文档"
        val format = BookFormat.fromFileName(name)
            ?: BookFormat.fromMime(context.contentResolver.getType(uri))
            ?: error("不支持的格式：$name（支持 TXT / MD / EPUB / PDF / DOCX）")

        val bookId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val ext = when (format) {
            BookFormat.TXT -> "txt"
            BookFormat.MD -> "md"
            BookFormat.EPUB -> "epub"
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

        // 书名优先用导入时的显示文件名，勿被本地副本 original.* 覆盖
        var title = TxtParser.titleFromFileName(name).let { t ->
            if (t.equals("original", ignoreCase = true)) "未命名文档" else t
        }
        var author: String? = null
        val toc = when {
            format.isEbook -> {
                val doc = EbookLoader.load(format, dest, dir)
                // 仅当解析出「有意义」的元数据标题时才覆盖（排除 original）
                val meta = doc.title?.trim().orEmpty()
                if (meta.isNotBlank() && !meta.equals("original", ignoreCase = true)) {
                    title = meta
                }
                author = doc.author
                doc.toc
            }
            format == BookFormat.PDF -> extractPdfToc(dest)
            else -> emptyList()
        }
        saveToc(dir, toc)

        val cover = CoverGenerator.ensureCover(
            format = format,
            localFile = dest,
            bookDir = dir,
            title = title
        )

        val now = System.currentTimeMillis()
        val book = Book(
            id = bookId,
            title = title,
            author = author,
            format = format,
            sourceUri = uri.toString(),
            localPath = dest.absolutePath,
            coverPath = cover.coverPath,
            addedAt = now,
            lastOpenedAt = 0L,
            lastPosition = "",
            progressPercent = 0f,
            updatedAt = now,
            folderId = folderId
        )
        bookRepository.upsert(book)
        return book
    }

    suspend fun ensureBookCover(book: Book): Book = withContext(Dispatchers.IO) {
        val coverPath = book.coverPath
        if (!coverPath.isNullOrBlank() && File(coverPath).exists()) {
            return@withContext book
        }
        val dir = CoverGenerator.bookDirOf(book.localPath) ?: return@withContext book
        val local = File(book.localPath)
        if (!local.exists()) return@withContext book
        val result = CoverGenerator.ensureCover(
            format = book.format,
            localFile = local,
            bookDir = dir,
            title = book.title
        )
        if (result.coverPath.isNullOrBlank()) return@withContext book
        val updated = book.copy(
            coverPath = result.coverPath,
            updatedAt = System.currentTimeMillis()
        )
        bookRepository.upsert(updated)
        updated
    }

    private fun extractPdfToc(file: File): List<TocEntry> {
        PdfOutlineParser.ensureInitialized(context)
        val pageCount = runCatching {
            android.graphics.pdf.PdfRenderer(
                android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
            ).use { it.pageCount }
        }.getOrDefault(0)
        return PdfOutlineParser.parseOutline(file, pageCount)
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
        const val TOC_CACHE_VERSION = 4

        fun loadToc(context: Context, bookId: String): List<TocEntry> {
            val file = File(context.filesDir, "books/$bookId/toc.json")
            if (!file.exists()) return emptyList()
            return runCatching {
                val root = JSONObject(file.readText())
                if (root.has("entries")) {
                    val ver = root.optInt("version", 0)
                    if (ver < TOC_CACHE_VERSION) return emptyList()
                    parseEntries(root.getJSONArray("entries"))
                } else {
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

data class TreeImportResult(
    /** 若因导入新建了书架文件夹，则非空 */
    val folder: BookFolder?,
    val result: ImportResult
)
