package com.vibecoding.reader.domain.bookshelf

import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfSearchTest {
    private fun book(id: String, title: String, author: String? = null, folderId: String? = null) = Book(
        id = id,
        title = title,
        author = author,
        format = BookFormat.TXT,
        localPath = "/tmp/$id.txt",
        addedAt = 1L,
        folderId = folderId
    )

    @Test
    fun filtersByTitleAndAuthor() {
        val items = listOf(
            ShelfItem.BookItem(book("1", "西游记", "吴承恩")),
            ShelfItem.BookItem(book("2", "水浒传", "施耐庵"))
        )
        val byTitle = ShelfSearch.filter(items, "西游")
        assertEquals(1, byTitle.size)
        val byAuthor = ShelfSearch.filter(items, "施耐庵")
        assertEquals(1, byAuthor.size)
        assertTrue(ShelfSearch.filter(items, "  ").size == 2)
    }

    @Test
    fun includesBooksFromFoldersWhenSearchingRoot() {
        val folder = BookFolder(id = "f1", name = "古典", addedAt = 1L)
        val items = listOf(
            ShelfItem.Folder(folder, bookCount = 1, previewCoverPaths = emptyList()),
            ShelfItem.BookItem(book("1", "现代诗"))
        )
        val hidden = book("2", "红楼梦", folderId = "f1")
        val result = ShelfSearch.filter(items, "红楼", listOf(hidden))
        assertTrue(result.any { it is ShelfItem.BookItem && it.book.title == "红楼梦" })
        assertTrue(result.none { it is ShelfItem.Folder })
    }
}
