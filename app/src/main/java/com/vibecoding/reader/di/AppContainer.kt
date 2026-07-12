package com.vibecoding.reader.di

import android.content.Context
import com.vibecoding.reader.data.db.AppDatabase
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.BookmarkRepository
import com.vibecoding.reader.data.repo.FolderRepository
import com.vibecoding.reader.data.repo.SettingsRepository
import com.vibecoding.reader.data.repo.ShelfRepository
import com.vibecoding.reader.domain.sync.NoOpSyncPort
import com.vibecoding.reader.domain.sync.SyncPort

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)

    val bookRepository = BookRepository(
        db.bookDao(),
        db.bookmarkDao(),
        db.folderDao()
    )
    val folderRepository = FolderRepository(
        db.folderDao(),
        db.bookDao(),
        db.bookmarkDao()
    )
    val shelfRepository = ShelfRepository(db.folderDao(), db.bookDao())
    val bookmarkRepository = BookmarkRepository(db.bookmarkDao())
    val settingsRepository = SettingsRepository(db.settingsDao())
    val bookImporter = BookImporter(appContext, bookRepository, folderRepository)
    val syncPort: SyncPort = NoOpSyncPort()
}
