package com.vibecoding.reader.ui.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.domain.model.Book
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val isImporting: Boolean = false,
    val message: String? = null
)

class BookshelfViewModel(
    private val bookRepository: BookRepository,
    private val bookImporter: BookImporter
) : ViewModel() {

    val books: StateFlow<List<Book>> = bookRepository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(BookshelfUiState())
    val ui: StateFlow<BookshelfUiState> = _ui.asStateFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(isImporting = true, message = null) }
            val result = bookImporter.importUris(uris)
            val msg = buildString {
                if (result.books.isNotEmpty()) {
                    append("已导入 ${result.books.size} 本")
                }
                if (result.errors.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append(result.errors.first())
                    if (result.errors.size > 1) append(" 等${result.errors.size}个错误")
                }
            }.ifBlank { null }
            _ui.update { it.copy(isImporting = false, message = msg) }
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
            _ui.update { it.copy(message = "已删除「${book.title}」") }
        }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    class Factory(
        private val bookRepository: BookRepository,
        private val bookImporter: BookImporter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BookshelfViewModel(bookRepository, bookImporter) as T
        }
    }
}
