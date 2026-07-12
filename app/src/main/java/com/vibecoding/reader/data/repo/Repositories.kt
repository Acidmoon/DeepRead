package com.vibecoding.reader.data.repo

import com.vibecoding.reader.data.db.BookDao
import com.vibecoding.reader.data.db.BookEntity
import com.vibecoding.reader.data.db.BookmarkDao
import com.vibecoding.reader.data.db.BookmarkEntity
import com.vibecoding.reader.data.db.FolderDao
import com.vibecoding.reader.data.db.FolderEntity
import com.vibecoding.reader.data.db.ReadingSettingsEntity
import com.vibecoding.reader.data.db.SettingsDao
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.ReadingSettings
import com.vibecoding.reader.domain.model.ShelfItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

class FolderRepository(
    private val folderDao: FolderDao,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao
) {
    fun observeFolders(): Flow<List<BookFolder>> =
        folderDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeFolder(id: String): Flow<BookFolder?> =
        folderDao.observeById(id).map { it?.toDomain() }

    suspend fun getFolder(id: String): BookFolder? = folderDao.getById(id)?.toDomain()

    suspend fun createFolder(name: String): BookFolder {
        val now = System.currentTimeMillis()
        val folder = BookFolder(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "新建文件夹" },
            addedAt = now,
            updatedAt = now
        )
        folderDao.upsert(FolderEntity.fromDomain(folder))
        return folder
    }

    suspend fun renameFolder(id: String, name: String) {
        val existing = folderDao.getById(id) ?: return
        folderDao.upsert(
            existing.copy(
                name = name.trim().ifBlank { existing.name },
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun touchFolder(id: String) {
        folderDao.touch(id, System.currentTimeMillis())
    }

    /**
     * 删除文件夹。deleteBooks=true 时删除内含书籍；否则书籍回到根目录。
     */
    suspend fun deleteFolder(folder: BookFolder, deleteBooks: Boolean = true) {
        val books = bookDao.getByFolder(folder.id)
        if (deleteBooks) {
            books.forEach { entity ->
                bookmarkDao.deleteByBook(entity.id)
                bookDao.deleteById(entity.id)
                runCatching {
                    File(entity.localPath).parentFile
                        ?.takeIf { it.name == entity.id }
                        ?.deleteRecursively()
                }
            }
        } else {
            val now = System.currentTimeMillis()
            books.forEach { bookDao.moveToFolder(it.id, null, now) }
        }
        folderDao.deleteById(folder.id)
    }
}

class BookRepository(
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val folderDao: FolderDao? = null
) {
    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeRecent(limit: Int = 6): Flow<List<Book>> =
        bookDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeRootBooks(): Flow<List<Book>> =
        bookDao.observeRootBooks().map { list -> list.map { it.toDomain() } }

    fun observeBooksInFolder(folderId: String): Flow<List<Book>> =
        bookDao.observeBooksInFolder(folderId).map { list -> list.map { it.toDomain() } }

    fun observeBook(id: String): Flow<Book?> =
        bookDao.observeById(id).map { it?.toDomain() }

    suspend fun getBook(id: String): Book? = bookDao.getById(id)?.toDomain()

    suspend fun upsert(book: Book) {
        bookDao.upsert(BookEntity.fromDomain(book))
        book.folderId?.let { folderDao?.touch(it, System.currentTimeMillis()) }
    }

    suspend fun updateProgress(bookId: String, position: String, progress: Float) {
        val now = System.currentTimeMillis()
        bookDao.updateProgress(bookId, position, progress, now, now)
    }

    suspend fun moveToFolder(bookId: String, folderId: String?) {
        val now = System.currentTimeMillis()
        bookDao.moveToFolder(bookId, folderId, now)
        folderId?.let { folderDao?.touch(it, now) }
    }

    suspend fun deleteBook(book: Book) {
        bookmarkDao.deleteByBook(book.id)
        bookDao.deleteById(book.id)
        runCatching {
            val file = File(book.localPath)
            val parent = file.parentFile
            if (file.exists()) file.delete()
            parent?.listFiles()?.let { if (it.isEmpty()) parent.delete() }
            parent?.takeIf { it.name == book.id }?.deleteRecursively()
        }
    }
}

/** 书架聚合：根目录 = 文件夹 + 根书籍；文件夹内 = 仅书籍。 */
class ShelfRepository(
    private val folderDao: FolderDao,
    private val bookDao: BookDao
) {
    fun observeRootShelf(): Flow<List<ShelfItem>> {
        return combine(
            folderDao.observeAll(),
            bookDao.observeRootBooks(),
            bookDao.observeAll()
        ) { folders, rootBooks, allBooks ->
            val byFolder = allBooks.groupBy { it.folderId }
            val folderItems = folders.map { fe ->
                val inFolder = byFolder[fe.id].orEmpty()
                val covers = inFolder
                    .mapNotNull { it.coverPath }
                    .filter { it.isNotBlank() }
                    .take(4)
                ShelfItem.Folder(
                    folder = fe.toDomain(),
                    bookCount = inFolder.size,
                    previewCoverPaths = covers
                )
            }
            val bookItems = rootBooks.map { ShelfItem.BookItem(it.toDomain()) }
            (folderItems + bookItems).sortedByDescending { it.sortKey }
        }
    }

    fun observeFolderBooks(folderId: String): Flow<List<Book>> =
        bookDao.observeBooksInFolder(folderId).map { list -> list.map { it.toDomain() } }
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
