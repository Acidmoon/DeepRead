package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.Book

/**
 * 书架整理纯逻辑（重命名 / 移动），便于单测。
 */
object BookOrganize {

    fun renamed(book: Book, newTitle: String, now: Long = System.currentTimeMillis()): Book {
        val t = newTitle.trim()
        require(t.isNotBlank()) { "书名不能为空" }
        return book.copy(title = t, updatedAt = now)
    }

    fun moved(
        book: Book,
        folderId: String?,
        now: Long = System.currentTimeMillis()
    ): Book = book.copy(folderId = folderId, updatedAt = now)
}
