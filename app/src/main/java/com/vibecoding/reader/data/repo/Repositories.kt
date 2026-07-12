package com.vibecoding.reader.data.repo

import com.vibecoding.reader.data.db.BookDao
import com.vibecoding.reader.data.db.BookEntity
import com.vibecoding.reader.data.db.BookmarkDao
import com.vibecoding.reader.data.db.BookmarkEntity
import com.vibecoding.reader.data.db.ReadingSettingsEntity
import com.vibecoding.reader.data.db.SettingsDao
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.ReadingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

class BookRepository(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao
) {
    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeBook(id: String): Flow<Book?> =
        bookDao.observeById(id).map { it?.toDomain() }

    suspend fun getBook(id: String): Book? = bookDao.getById(id)?.toDomain()

    suspend fun upsert(book: Book) {
        bookDao.upsert(BookEntity.fromDomain(book))
    }

    suspend fun updateProgress(bookId: String, position: String, progress: Float) {
        val now = System.currentTimeMillis()
        bookDao.updateProgress(bookId, position, progress, now, now)
    }

    suspend fun deleteBook(book: Book) {
        bookmarkDao.deleteByBook(book.id)
        bookDao.deleteById(book.id)
        runCatching {
            val file = File(book.localPath)
            val parent = file.parentFile
            if (file.exists()) file.delete()
            // remove book folder if empty
            parent?.listFiles()?.let { if (it.isEmpty()) parent.delete() }
            // also try delete whole book dir
            parent?.takeIf { it.name == book.id }?.deleteRecursively()
        }
    }
}

class BookmarkRepository(
    private val bookmarkDao: BookmarkDao
) {
    fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    suspend fun add(bookmark: Bookmark) {
        bookmarkDao.upsert(BookmarkEntity.fromDomain(bookmark))
    }

    suspend fun delete(id: String) {
        bookmarkDao.deleteById(id)
    }
}

class SettingsRepository(
    private val settingsDao: SettingsDao
) {
    fun observe(): Flow<ReadingSettings> =
        settingsDao.observe().map { it?.toDomain() ?: ReadingSettings() }

    suspend fun get(): ReadingSettings =
        settingsDao.get()?.toDomain() ?: ReadingSettings()

    suspend fun save(settings: ReadingSettings) {
        settingsDao.upsert(ReadingSettingsEntity.fromDomain(settings))
    }
}
