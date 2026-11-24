package com.vibecoding.reader.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.import.ImportResult
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.FolderRepository
import com.vibecoding.reader.data.repo.ShelfRepository
import com.vibecoding.reader.domain.bookshelf.ShelfSearch
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.ShelfItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections

data class BookshelfUiState(
    val isImporting: Boolean = false,
    val message: String? = null,
    /** null = 根书架；非空 = 文件夹内 */
    val currentFolderId: String? = null,
    val currentFolderName: String? = null
)

class BookshelfViewModel(
    private val bookRepository: BookRepository,
    private val folderRepository: FolderRepository,
    private val shelfRepository: ShelfRepository,
    private val bookImporter: BookImporter,
    initialFolderId: String? = null
) : ViewModel() {

    private val _folderId = MutableStateFlow(initialFolderId)
    private val _ui = MutableStateFlow(
        BookshelfUiState(currentFolderId = initialFolderId)
    )
    val ui: StateFlow<BookshelfUiState> = _ui.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawShelfItems: StateFlow<List<ShelfItem>> = _folderId
        .flatMapLatest { folderId ->
            if (folderId == null) {
                shelfRepository.observeRootShelf()
            } else {
                shelfRepository.observeFolderBooks(folderId).map { books ->
                    books.map { ShelfItem.BookItem(it) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shelfItems: StateFlow<List<ShelfItem>> = combine(
        rawShelfItems,
        _searchQuery,
        bookRepository.observeBooks()
    ) { items, query, allBooks ->
        val extra = if (_folderId.value == null && query.isNotBlank()) {
            allBooks.filter { it.folderId != null }
        } else {
            emptyList()
        }
        ShelfSearch.filter(items, query, extra)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isShelfEmpty: StateFlow<Boolean> = rawShelfItems
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private val coverEnsuredIds = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        if (initialFolderId != null) {
            viewModelScope.launch {
                val f = folderRepository.getFolder(initialFolderId)
                _ui.update {
                    it.copy(
                        currentFolderId = initialFolderId,
                        currentFolderName = f?.name
                    )
                }
            }
        }

        viewModelScope.launch {
            rawShelfItems.collect { items ->
                items.filterIsInstance<ShelfItem.BookItem>().forEach { item ->
                    val book = item.book
                    if (coverEnsuredIds.add(book.id)) {
                        launch {
                            runCatching { bookImporter.ensureBookCover(book) }
                        }
                    }
                }
            }
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(isImporting = true, message = null) }
            val folderId = _folderId.value
            val result = bookImporter.importUris(uris, folderId = folderId)
            result.books.forEach { coverEnsuredIds.add(it.id) }
            folderId?.let { folderRepository.touchFolder(it) }
            _ui.update {
                it.copy(
                    isImporting = false,
                    message = formatImportMessage(result, folderId != null)
                )
            }
        }
    }

    /**
     * 从系统已有目录导入：
     * 根书架 → 新建同名文件夹并导入；
     * 文件夹内 → 导入到当前文件夹。
     */
    fun importFromTree(treeUri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(isImporting = true, message = null) }
            runCatching {
                bookImporter.importFromTree(
                    treeUri = treeUri,
                    existingFolderId = _folderId.value
                )
            }.onSuccess { treeResult ->
                treeResult.result.books.forEach { coverEnsuredIds.add(it.id) }
                val folderHint = treeResult.folder?.let { "到「${it.name}」" }
                    ?: if (_folderId.value != null) "到当前文件夹" else ""
                val msg = buildString {
                    val fresh = treeResult.result.books.size - treeResult.result.reusedCount
                    if (fresh > 0) {
                        append("已从目录导入 ${fresh} 本")
                        append(folderHint)
                    }
                    if (treeResult.result.reusedCount > 0) {
                        if (isNotEmpty()) append("，")
                        append("${treeResult.result.reusedCount} 本已在书架中，未重复导入")
                    }
                    if (treeResult.result.errors.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append(treeResult.result.errors.first())
                        if (treeResult.result.errors.size > 1) {
                            append(" 等${treeResult.result.errors.size}个问题")
                        }
                    }
                    if (isEmpty()) append("目录中没有可导入的文件")
                }
                _ui.update { it.copy(isImporting = false, message = msg) }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        isImporting = false,
                        message = e.message ?: "文件夹导入失败"
                    )
                }
            }
        }
    }

    private fun formatImportMessage(
        result: ImportResult,
        intoFolder: Boolean
    ): String? = buildString {
        val fresh = result.books.size - result.reusedCount
        if (fresh > 0) {
            append("已导入 $fresh 本")
            if (intoFolder) append("到当前文件夹")
        }
        if (result.reusedCount > 0) {
            if (isNotEmpty()) append("，")
            append("${result.reusedCount} 本已在书架中，未重复导入")
        }
        if (result.errors.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append(result.errors.first())
            if (result.errors.size > 1) append(" 等${result.errors.size}个错误")
        }
    }.ifBlank { null }

    fun createFolder(name: String) {
        viewModelScope.launch {
            // 仅在根目录创建文件夹
            if (_folderId.value != null) {
                _ui.update { it.copy(message = "请在根书架创建文件夹") }
                return@launch
            }
            val folder = folderRepository.createFolder(name)
            _ui.update { it.copy(message = "已创建文件夹「${folder.name}」") }
        }
    }

    fun renameFolder(folder: BookFolder, name: String) {
        viewModelScope.launch {
            folderRepository.renameFolder(folder.id, name)
            if (_folderId.value == folder.id) {
                _ui.update { it.copy(currentFolderName = name.trim().ifBlank { folder.name }) }
            }
            _ui.update { it.copy(message = "已重命名") }
        }
    }

    fun deleteFolder(folder: BookFolder, deleteBooks: Boolean) {
        viewModelScope.launch {
            folderRepository.deleteFolder(folder, deleteBooks = deleteBooks)
            _ui.update {
                it.copy(
                    message = if (deleteBooks) "已删除文件夹及其中书籍"
                    else "已删除文件夹，书籍已移回根目录"
                )
            }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
            coverEnsuredIds.remove(book.id)
            _ui.update { it.copy(message = "已删除「${book.title}」") }
        }
    }

    fun moveBookToFolder(book: Book, folderId: String?) {
        viewModelScope.launch {
            bookRepository.moveToFolder(book.id, folderId)
            _ui.update {
                it.copy(
                    message = if (folderId == null) "已移到根书架" else "已移入文件夹"
                )
            }
        }
    }

    fun renameBook(book: Book, newTitle: String) {
        viewModelScope.launch {
            runCatching {
                bookRepository.renameBook(book.id, newTitle)
            }.onSuccess {
                _ui.update { it.copy(message = "已重命名为「${newTitle.trim()}」") }
            }.onFailure { e ->
                _ui.update { it.copy(message = e.message ?: "重命名失败") }
            }
        }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    class Factory(
        private val bookRepository: BookRepository,
        private val folderRepository: FolderRepository,
        private val shelfRepository: ShelfRepository,
        private val bookImporter: BookImporter,
        private val folderId: String? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(
                bookRepository,
                folderRepository,
                shelfRepository,
                bookImporter,
                folderId
            ) as T
        }
    }
}
