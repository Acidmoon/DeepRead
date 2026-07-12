package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookOrganizeTest {
    private fun sample() = Book(
        id = "1",
        title = "original",
        format = BookFormat.TXT,
        localPath = "/x",
        addedAt = 1L,
        folderId = null
    )

    @Test
    fun renamePersistsNewTitle() {
        val b = BookOrganize.renamed(sample(), "  红楼梦  ", now = 99L)
        assertEquals("红楼梦", b.title)
        assertEquals(99L, b.updatedAt)
        assertEquals("1", b.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun renameRejectsBlank() {
        BookOrganize.renamed(sample(), "   ")
    }

    @Test
    fun moveChangesFolderId() {
        val into = BookOrganize.moved(sample(), "folder-a", now = 10L)
        assertEquals("folder-a", into.folderId)
        val root = BookOrganize.moved(into, null, now = 11L)
        assertNull(root.folderId)
        assertEquals(11L, root.updatedAt)
    }
}
