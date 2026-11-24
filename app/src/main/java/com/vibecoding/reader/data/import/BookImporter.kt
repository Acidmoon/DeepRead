package com.vibecoding.reader.data.import

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.vibecoding.reader.data.parser.EbookLoader
import com.vibecoding.reader.data.parser.EpubParser
import com.vibecoding.reader.data.parser.ImagePathResolver
import com.vibecoding.reader.data.parser.SafeLocalPath
import com.vibecoding.reader.data.secure.PdfPasswordAccess
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
        var reusedCount = 0
        for (uri in uris) {
            runCatching { importOne(uri, folderId) }
                .onSuccess { outcome ->
                    imported += outcome.book
                    if (outcome.reused) reusedCount++
                }
                .onFailure { errors += (it.message ?: "导入失败") }
        }
        ImportResult(imported, errors, reusedCount)
    }

    /**
     * 从其他应用 ACTION_VIEW 打开：已在书架则复用，否则导入到根目录。
     */
    suspend fun importOrReuse(uri: Uri): ImportOutcome = withContext(Dispatchers.IO) {
        importOne(uri, folderId = null)
    }

    /**
     * 从系统已有目录导入：
     * - 若 [existingFolderId] 为空：在书架新建同名文件夹，目录内支持的文件全部放入
     * - 若已在某文件夹内：文件导入到当前文件夹（不再新建）
     * 递归扫描子目录中的支持格式。同名但位于不同子目录的文件都会保留。
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
        var reusedCount = 0
        for (doc in files) {
            val uri = doc.uri
            val name = doc.name ?: "未命名"
            runCatching { importOne(uri, targetFolderId, displayNameOverride = name) }
                .onSuccess { outcome ->
                    imported += outcome.book
                    if (outcome.reused) reusedCount++
                }
                .onFailure { errors += "$name: ${it.message ?: "失败"}" }
        }
        targetFolderId.let { id -> folderRepository?.touchFolder(id) }

        TreeImportResult(
            folder = createdFolder,
            result = ImportResult(imported, errors, reusedCount)
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
        // 不再按纯文件名去重，避免不同子目录里的同名文件被悄悄丢掉
        return out
    }

    private suspend fun importOne(
        uri: Uri,
        folderId: String? = null,
        displayNameOverride: String? = null
    ): ImportOutcome {
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (e: SecurityException) {
            error(ImportPrecheck.noReadPermissionMessage(uri.toString()))
        }
        val rawName = displayNameOverride ?: try {
            queryDisplayName(uri)
        } catch (e: SecurityException) {
            error(ImportPrecheck.noReadPermissionMessage(uri.toString()))
        }
        val name = ImportPrecheck.effectiveFileName(rawName, mime)
        if (ImportPrecheck.isLegacyMsWord(name, mime)) {
            error(ImportPrecheck.legacyMsWordMessage(name))
        }
        val format = ImportPrecheck.resolveFormat(name, mime)
            ?: error(ImportPrecheck.unsupportedFormatMessage(name))

        val sourceUri = uri.toString()
        val existing = bookRepository.getBySourceUri(sourceUri)
        if (existing != null) {
            val local = File(existing.localPath)
            if (local.exists() && local.isFile && local.length() > 0L) {
                return ImportOutcome(existing, reused = true)
            }
        }

        val sourceSize = querySize(uri)
        ImportPrecheck.sizeError(sourceSize, format)?.let { error(it) }
        val usable = context.filesDir.usableSpace
        ImportPrecheck.spaceError(
            neededBytes = if (sourceSize > 0L) sourceSize else 0L,
            usableSpace = usable
        )?.let { error(it) }

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

        try {
            val maxBytes = ImportPrecheck.limitFor(format)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        ImportPrecheck.copyCapped(input, output, maxBytes)
                    }
                } ?: error("无法读取文件：$name")
            } catch (e: SecurityException) {
                error(ImportPrecheck.noReadPermissionMessage(name))
            }

            if (dest.length() <= 0L) error("文件为空或无法读取")

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            if (format == BookFormat.MD) {
                copyMarkdownSidecarImages(uri, dest, dir)
            }

            // 书名优先用导入时的显示文件名，勿被本地副本 original.* 覆盖
            var title = TxtParser.titleFromFileName(name).let { t ->
                if (t.equals("original", ignoreCase = true)) "未命名文档" else t
            }
            var author: String? = null
            var preParsedEpub: EpubParser.EpubParseResult? = null
            val toc = when {
                format == BookFormat.EPUB -> {
                    val parsed = EpubParser.parse(dest, mediaDir = File(dir, "media"))
                    preParsedEpub = parsed
                    val meta = parsed.document.title?.trim().orEmpty()
                    if (meta.isNotBlank() && !meta.equals("original", ignoreCase = true)) {
                        title = meta
                    }
                    author = parsed.document.author
                    parsed.document.toc
                }
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
                title = title,
                preParsedEpub = preParsedEpub
            )

            val now = System.currentTimeMillis()
            val book = Book(
                id = bookId,
                title = title,
                author = author,
                format = format,
                sourceUri = sourceUri,
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
            return ImportOutcome(book, reused = false)
        } catch (e: Throwable) {
            runCatching { dir.deleteRecursively() }
            throw e
        }
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
            title = book.title,
            password = PdfPasswordAccess.resolve(context, book, bookRepository)
        )
        if (result.coverPath.isNullOrBlank()) return@withContext book
        val updated = book.copy(
            coverPath = result.coverPath,
            updatedAt = System.currentTimeMillis()
        )
        bookRepository.upsert(updated)
        updated
    }


    private fun copyMarkdownSidecarImages(uri: Uri, destMd: File, bookDir: File) {
        val raw = TxtParser.readText(destMd)
        val sourceBase = sourceFileBase(uri)
        val (rewritten, copied) = if (sourceBase != null && sourceBase.isDirectory) {
            MarkdownSidecarImages.copyAndRewrite(raw, sourceBase, bookDir)
        } else {
            copyMarkdownImagesFromDocument(uri, raw, bookDir)
        }
        if (copied > 0 && rewritten != raw) {
            destMd.writeText(rewritten)
        }
    }

    private fun sourceFileBase(uri: Uri): File? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        return File(path).parentFile
    }

    private fun copyMarkdownImagesFromDocument(
        uri: Uri,
        raw: String,
        bookDir: File
    ): Pair<String, Int> {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: return raw to 0
        // 单文件 content URI 通常没有可枚举的父目录，此时无法复制相对图片（不是实现遗漏）
        val parent = doc.parentFile ?: return raw to 0
        val media = File(bookDir, "media").apply { mkdirs() }
        val mapped = LinkedHashMap<String, String>()
        var copied = 0
        for (src in MarkdownSidecarImages.findLocalSources(raw)) {
            if (ImagePathResolver.isRemote(src)) continue
            if (MarkdownSidecarImages.isUnsafeRelative(src)) continue
            val child = resolveDocumentChild(parent, src) ?: continue
            val destName = SafeLocalPath.safeFileName(child.name ?: "image", mapped.size)
            val dest = File(media, destName)
            val ok = runCatching {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: false
                dest.exists() && dest.length() > 0L
            }.getOrDefault(false)
            if (ok) {
                mapped[src] = "media/$destName"
                copied++
            } else {
                runCatching { dest.delete() }
            }
        }
        return MarkdownSidecarImages.applyRewrite(raw, mapped) to copied
    }

    private fun resolveDocumentChild(start: DocumentFile, rel: String): DocumentFile? {
        val parts = ImagePathResolver.decode(rel).replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        var cur = start
        for (part in parts) {
            cur = cur.listFiles().firstOrNull { it.name == part } ?: return null
        }
        return cur.takeIf { it.isFile }
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

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return -1L
    }

    companion object {
        const val TOC_CACHE_VERSION = 5

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

data class ImportOutcome(
    val book: Book,
    val reused: Boolean
)

data class ImportResult(
    val books: List<Book>,
    val errors: List<String>,
    val reusedCount: Int = 0
)

data class TreeImportResult(
    /** 若因导入新建了书架文件夹，则非空 */
    val folder: BookFolder?,
    val result: ImportResult
)
