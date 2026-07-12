package com.vibecoding.reader.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.parser.DocxParser
import com.vibecoding.reader.data.parser.PdfOutlineParser
import com.vibecoding.reader.data.parser.TxtParser
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.BookmarkRepository
import com.vibecoding.reader.data.repo.SettingsRepository
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.ReadingSettings
import com.vibecoding.reader.domain.model.TocEntry
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
import java.io.File
import java.util.UUID

data class ReaderContentState(
    val loading: Boolean = true,
    val error: String? = null,
    val text: String = "",
    val toc: List<TocEntry> = emptyList(),
    val initialPosition: String = ""
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
    private var lastSavedPosition: String? = null

    init {
        viewModelScope.launch {
            val b = bookRepository.getBook(bookId)
            if (b == null) {
                _content.update { it.copy(loading = false, error = "书籍不存在") }
                return@launch
            }
            loadContent(b)
        }
    }

    private suspend fun loadContent(book: Book) {
        _content.update { it.copy(loading = true, error = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(book.localPath)
                if (!file.exists()) error("本地文件丢失，请重新导入")
                // 打开时预加载目录：命中版本化缓存则直接用，否则解析并写回，保证打开「目录」瞬时可用
                val cachedToc = BookImporter.loadToc(appContext, book.id)
                when (book.format) {
                    BookFormat.TXT -> {
                        val text = TxtParser.readText(file)
                        val toc = cachedToc.ifEmpty {
                            TxtParser.buildToc(text).also {
                                BookImporter.saveTocForBook(appContext, book.id, it)
                            }
                        }
                        text to toc
                    }
                    BookFormat.DOCX -> {
                        val doc = DocxParser.parse(file)
                        val toc = cachedToc.ifEmpty {
                            doc.toc.also {
                                BookImporter.saveTocForBook(appContext, book.id, it)
                            }
                        }
                        doc.plainText to toc
                    }
                    BookFormat.PDF -> {
                        val toc = cachedToc.ifEmpty {
                            // 无缓存时解析 outline 并落盘
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
                        "" to toc
                    }
                }
            }
        }
        result.onSuccess { (text, toc) ->
            _content.update {
                it.copy(
                    loading = false,
                    text = text,
                    toc = toc,
                    initialPosition = book.lastPosition
                )
            }
        }.onFailure { e ->
            _content.update {
                it.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    /**
     * 防抖写入进度，避免左右翻页时频繁打库导致卡顿。
     */
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
