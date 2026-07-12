package com.vibecoding.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<FolderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE folders SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /** 最近阅读：打开过的书，按最近打开时间倒序 */
    @Query(
        """
        SELECT * FROM books WHERE lastOpenedAt > 0
        ORDER BY lastOpenedAt DESC LIMIT :limit
        """
    )
    fun observeRecent(limit: Int = 6): Flow<List<BookEntity>>

    /** 根目录书籍：folderId 为空 */
    @Query(
        """
        SELECT * FROM books WHERE folderId IS NULL
        ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC
        """
    )
    fun observeRootBooks(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT * FROM books WHERE folderId = :folderId
        ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC
        """
    )
    fun observeBooksInFolder(folderId: String): Flow<List<BookEntity>>

    @Query("SELECT COUNT(*) FROM books WHERE folderId = :folderId")
    fun observeCountInFolder(folderId: String): Flow<Int>

    @Query(
        """
        SELECT * FROM books WHERE folderId = :folderId AND coverPath IS NOT NULL AND coverPath != ''
        ORDER BY addedAt DESC LIMIT :limit
        """
    )
    suspend fun getPreviewCovers(folderId: String, limit: Int = 4): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query(
        """
        UPDATE books SET lastPosition = :position, progressPercent = :progress,
        lastOpenedAt = :openedAt, updatedAt = :updatedAt WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        position: String,
        progress: Float,
        openedAt: Long,
        updatedAt: Long
    )

    @Query("UPDATE books SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: String, folderId: String?, updatedAt: Long)

    @Query("SELECT * FROM books WHERE folderId = :folderId")
    suspend fun getByFolder(folderId: String): List<BookEntity>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeByBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM reading_settings WHERE id = 1 LIMIT 1")
    fun observe(): Flow<ReadingSettingsEntity?>

    @Query("SELECT * FROM reading_settings WHERE id = 1 LIMIT 1")
    suspend fun get(): ReadingSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ReadingSettingsEntity)
}
