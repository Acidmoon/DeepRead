package com.vibecoding.reader.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.import.CoverGenerator
import com.vibecoding.reader.data.parser.EbookLoader
import com.vibecoding.reader.data.parser.PdfOutlineParser
import com.vibecoding.reader.data.parser.PdfTextIndex
import com.vibecoding.reader.data.secure.PdfPasswordAccess
import com.vibecoding.reader.data.secure.PdfPasswordStore
import com.vibecoding.reader.domain.search.PdfTextSearch
import com.vibecoding.reader.domain.search.TextSearch
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.BookmarkRepository
import com.vibecoding.reader.data.repo.SettingsRepository
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.EbookBlock
import com.vibecoding.reader.domain.model.ReadingSettings
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.reader.BookLoadGuard
import com.vibecoding.reader.domain.reader.ProgressSaveController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException

data class ReaderContentState(
    val loading: Boolean = true,
    val loadingMessage: String? = null,
    val error: String? = null,
    val text: String = "",
    val toc: List<TocEntry> = emptyList(),
    val initialPosition: String = "",
    val markdownSource: String? = null,
    /** 富内容（标题/段落/图片等） */
    val blocks: List<EbookBlock> = emptyList()
)

class ReaderViewModel(
    private val bookId: String,
    private val appContext: Context,
    private val bookRepository: BookRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val book: StateFlow<Book?> = bookRepository.observeBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepository.observeBookmarks(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<ReadingSettings> = settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingSettings())

    private val _content = MutableStateFlow(ReaderContentState())
    val content: StateFlow<ReaderContentState> = _content.asStateFlow()

    private val _pdfPageTexts = MutableStateFlow<List<String>?>(null)
    val pdfPageTexts: StateFlow<List<String>?> = _pdfPageTexts.asStateFlow()

    private val _pdfIndexMessage = MutableStateFlow<String?>(null)
    val pdfIndexMessage: StateFlow<String?> = _pdfIndexMessage.asStateFlow()

    private val _pdfUnlockPassword = MutableStateFlow<String?>(null)
    val pdfUnlockPassword: StateFlow<String?> = _pdfUnlockPassword.asStateFlow()

    private val _jumpPosition = MutableStateFlow<String?>(null)
    val jumpPosition: StateFlow<String?> = _jumpPosition.asStateFlow()

    private var loadJob: Job? = null
    private var pdfIndexJob: Job? = null
    private val progressSave = ProgressSaveController { position, progress ->
        bookRepository.updateProgress(bookId, position, progress)
    }

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val b = bookRepository.getBook(bookId)
            if (b == null) {
                _content.update {
                    it.copy(loading = false, loadingMessage = null, error = "书籍不存在")
                }
                return@launch
            }
            loadContent(b)
        }
    }

    private suspend fun loadContent(book: Book) {
        val file = File(book.localPath)
        val pdfPassword = if (book.format == BookFormat.PDF) {
            PdfPasswordAccess.resolve(appContext, book, bookRepository)
        } else null
        if (book.format == BookFormat.PDF) {
            _pdfUnlockPassword.value = pdfPassword
        }
        val size = BookLoadGuard.fileSize(file)
        val pre = BookLoadGuard.precheck(file, book.format)
        if (pre != null) {
            _content.update {
                it.copy(loading = false, loadingMessage = null, error = pre)
            }
            return
        }

        _content.update {
            it.copy(
                loading = true,
                loadingMessage = BookLoadGuard.loadingMessage(size),
                error = null
            )
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(BookLoadGuard.LOAD_TIMEOUT_MS) {
                    val cachedToc = BookImporter.loadToc(appContext, book.id)
                    when {
                        book.format.isEbook -> {
                            val bookDir = file.parentFile
                            val doc = EbookLoader.load(book.format, file, bookDir)
                            val toc = cachedToc.ifEmpty {
                                doc.toc.also {
                                    BookImporter.saveTocForBook(appContext, book.id, it)
                                }
                            }
                            val metaTitle = doc.title?.trim().orEmpty()
                            val betterTitle = metaTitle.isNotBlank() &&
                                !metaTitle.equals("original", ignoreCase = true) &&
                                (book.title.equals("original", ignoreCase = true) ||
                                    book.title == "未命名文档")
                            if (betterTitle ||
                                (!doc.author.isNullOrBlank() && book.author.isNullOrBlank())
                            ) {
                                bookRepository.upsert(
                                    book.copy(
                                        title = if (betterTitle) metaTitle else book.title,
                                        author = doc.author ?: book.author,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                            LoadedContent(doc.plainText, toc, doc.markdownSource, doc.blocks)
                        }
                        book.format == BookFormat.PDF -> {
                            // PDF 正文由 PdfRenderer 渲染；此处仅元数据/目录，避免整本读入
                            val toc = cachedToc.ifEmpty {
                                PdfOutlineParser.ensureInitialized(appContext)
                                val pageCount = runCatching {
                                    android.graphics.pdf.PdfRenderer(
                                        android.os.ParcelFileDescriptor.open(
                                            file,
                                            android.os.ParcelFileDescriptor.MODE_READ_ONLY
                                        )
                                    ).use { it.pageCount }
                                }.getOrDefault(0)
                                PdfOutlineParser.parseOutline(file, pageCount, pdfPassword).also {
                                    BookImporter.saveTocForBook(appContext, book.id, it)
                                }
                            }
                            LoadedContent("", toc, null, emptyList())
                        }
                        else -> error("不支持的格式")
                    }
                }
            }.recoverCatching { e ->
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    throw TimeoutException("加载超时")
                }
                throw e
            }
        }

        result.onSuccess { loaded ->
            _content.update {
                it.copy(
                    loading = false,
                    loadingMessage = null,
                    error = null,
                    text = loaded.text,
                    toc = loaded.toc,
                    initialPosition = book.lastPosition,
                    markdownSource = loaded.markdownSource,
                    blocks = loaded.blocks
                )
            }
            if (book.format == BookFormat.PDF) {
                startPdfTextIndex(file, file.parentFile, pdfPassword)
            }
        }.onFailure { e ->
            _content.update {
                it.copy(
                    loading = false,
                    loadingMessage = null,
                    error = BookLoadGuard.classifyError(e)
                )
            }
        }
    }

    fun saveProgress(position: String, progress: Float) {
        progressSave.onProgress(position, progress, viewModelScope)
    }

    fun saveProgressNow(position: String, progress: Float) {
        progressSave.updateLatest(position, progress)
        viewModelScope.launch {
            withContext(NonCancellable) {
                progressSave.flush()
            }
        }
    }

    override fun onCleared() {
        if (progressSave.hasUnsaved) {
            // viewModelScope 即将取消，这里短阻塞确保最后一次进度写入
            runBlocking {
                withContext(NonCancellable) {
                    progressSave.flush()
                }
            }
        }
        super.onCleared()
    }

    /** 加密 PDF 解锁成功后把密码记在本地，并补封面、目录和文字索引 */
    fun savePdfPassword(password: String) {
        viewModelScope.launch {
            val b = bookRepository.getBook(bookId) ?: return@launch
            runCatching { PdfPasswordStore.get(appContext).put(bookId, password) }
            if (!b.pdfPassword.isNullOrEmpty()) {
                bookRepository.upsert(
                    b.copy(pdfPassword = null, updatedAt = System.currentTimeMillis())
                )
            }
            _pdfUnlockPassword.value = password
            refreshEncryptedPdfExtras(b.copy(pdfPassword = null), password)
        }
    }

    fun searchBook(query: String): List<TextSearch.Hit> {
        val current = book.value
        return if (current?.format == BookFormat.PDF) {
            val pages = _pdfPageTexts.value ?: return emptyList()
            PdfTextSearch.search(pages, query)
        } else {
            TextSearch.search(_content.value.text, query)
        }
    }

    private suspend fun refreshEncryptedPdfExtras(book: Book, password: String) {
        val file = File(book.localPath)
        val dir = file.parentFile ?: return
        withContext(Dispatchers.IO) {
            PdfOutlineParser.ensureInitialized(appContext)
            val toc = PdfOutlineParser.parseOutline(file, 0, password)
            if (toc.isNotEmpty()) {
                BookImporter.saveTocForBook(appContext, book.id, toc)
                _content.update { it.copy(toc = toc) }
            }
            val cover = CoverGenerator.ensureCover(
                format = book.format,
                localFile = file,
                bookDir = dir,
                title = book.title,
                password = password
            )
            if (!cover.coverPath.isNullOrBlank() && cover.coverPath != book.coverPath) {
                bookRepository.upsert(
                    book.copy(coverPath = cover.coverPath, updatedAt = System.currentTimeMillis())
                )
            }
        }
        startPdfTextIndex(file, dir, password)
    }

    private fun startPdfTextIndex(file: File, bookDir: File?, password: String?) {
        if (bookDir == null) return
        pdfIndexJob?.cancel()
        pdfIndexJob = viewModelScope.launch(Dispatchers.IO) {
            _pdfIndexMessage.value = "正在建立文字索引…"
            val result = runCatching {
                val cached = if (PdfTextIndex.isUpToDate(bookDir, file)) {
                    PdfTextIndex.loadPages(bookDir)
                } else {
                    null
                }
                cached ?: run {
                    PdfOutlineParser.ensureInitialized(appContext)
                    val pages = PdfTextIndex.extractAll(file, password)
                    PdfTextIndex.writePages(bookDir, file, pages)
                    pages
                }
            }
            result.onSuccess { pages ->
                _pdfPageTexts.value = pages
                _pdfIndexMessage.value = null
            }.onFailure {
                _pdfPageTexts.value = null
                _pdfIndexMessage.value = "暂无法搜索：需要文字层，加密文档请先解锁（不做 OCR）"
            }
        }
    }

    fun updateSettings(settings: ReadingSettings) {
        viewModelScope.launch {
            settingsRepository.save(settings)
        }
    }

    fun addBookmark(position: String, label: String?) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            bookmarkRepository.add(
                Bookmark(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    position = position,
                    label = label?.take(80),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch {
            bookmarkRepository.delete(id)
        }
    }

    fun jumpTo(position: String) {
        _jumpPosition.value = position
    }

    fun consumeJump() {
        _jumpPosition.value = null
    }

    fun excerptForText(offset: Int, maxLen: Int = 40): String {
        val text = _content.value.text
        if (text.isEmpty()) return "书签"
        val start = offset.coerceIn(0, text.length)
        val end = (start + maxLen).coerceAtMost(text.length)
        return text.substring(start, end).replace('\n', ' ').trim().ifBlank { "书签" }
    }

    private data class LoadedContent(
        val text: String,
        val toc: List<TocEntry>,
        val markdownSource: String?,
        val blocks: List<EbookBlock>
    )

    class Factory(
        private val bookId: String,
        private val appContext: Context,
        private val bookRepository: BookRepository,
        private val bookmarkRepository: BookmarkRepository,
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(
                bookId,
                appContext,
                bookRepository,
                bookmarkRepository,
                settingsRepository
            ) as T
        }
    }
}
