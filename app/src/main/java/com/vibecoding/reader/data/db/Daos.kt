package com.vibecoding.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY CASE WHEN lastOpenedAt = 0 THEN addedAt ELSE lastOpenedAt END DESC")
    fun observeAll(): Flow<List<BookEntity>>

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
