package com.vibecoding.reader.data.secure

import android.content.Context
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.domain.model.Book

object PdfPasswordAccess {
    /**
     * 优先读 no_backup 保险箱；若只有库里的旧明文则迁入保险箱并清空列。
     */
    suspend fun resolve(context: Context, book: Book, bookRepository: BookRepository): String? {
        val store = PdfPasswordStore.get(context)
        val stored = runCatching { store.get(book.id) }.getOrNull()
        if (!stored.isNullOrEmpty()) {
            if (!book.pdfPassword.isNullOrEmpty()) {
                bookRepository.upsert(
                    book.copy(pdfPassword = null, updatedAt = System.currentTimeMillis())
                )
            }
            return stored
        }
        val legacy = book.pdfPassword?.takeIf { it.isNotEmpty() } ?: return null
        val migrated = runCatching { store.put(book.id, legacy); true }.getOrDefault(false)
        if (migrated) {
            bookRepository.upsert(
                book.copy(pdfPassword = null, updatedAt = System.currentTimeMillis())
            )
        }
        return legacy
    }
}
