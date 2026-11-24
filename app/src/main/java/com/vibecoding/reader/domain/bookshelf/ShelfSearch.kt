package com.vibecoding.reader.domain.bookshelf

import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.ShelfItem

/** 书架按书名 / 作者 / 文件夹名过滤。 */
object ShelfSearch {

    fun matchesBook(book: Book, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return book.title.contains(q, ignoreCase = true) ||
            book.author.orEmpty().contains(q, ignoreCase = true)
    }

    fun matchesFolder(folder: BookFolder, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return folder.name.contains(q, ignoreCase = true)
    }

    /**
     * @param booksInFolders 根目录搜索时，把子文件夹里匹配的书一并列出
     */
    fun filter(
        items: List<ShelfItem>,
        query: String,
        booksInFolders: List<Book> = emptyList()
    ): List<ShelfItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        val out = ArrayList<ShelfItem>()
        val visible = mutableSetOf<String>()
        for (item in items) {
            when (item) {
                is ShelfItem.Folder -> if (matchesFolder(item.folder, q)) out += item
                is ShelfItem.BookItem -> if (matchesBook(item.book, q)) {
                    out += item
                    visible += item.book.id
                }
            }
        }
        for (book in booksInFolders) {
            if (book.id !in visible && matchesBook(book, q)) {
                out += ShelfItem.BookItem(book)
            }
        }
        return out
    }
}
