package com.vibecoding.reader.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.parser.EbookLoader
import com.vibecoding.reader.data.parser.PdfOutlineParser
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _jumpPosition = MutableStateFlow<String?>(null)
    val jumpPosition: StateFlow<String?> = _jumpPosition.asStateFlow()

    private var progressJob: Job? = null
    private var loadJob: Job? = null
    private var lastSavedPosition: String? = null

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
        val size = BookLoadGuard.fileSize(file)
        val pre = BookLoadGuard.precheck(file)
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
                                PdfOutlineParser.parseOutline(file, pageCount).also {
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
        if (position == lastSavedPosition) return
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(350)
            lastSavedPosition = position
            bookRepository.updateProgress(bookId, position, progress.coerceIn(0f, 1f))
        }
    }

    fun saveProgressNow(position: String, progress: Float) {
        progressJob?.cancel()
        lastSavedPosition = position
        viewModelScope.launch {
            bookRepository.updateProgress(bookId, position, progress.coerceIn(0f, 1f))
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
