package com.vibecoding.reader.di

import android.content.Context
import android.net.Uri
import com.vibecoding.reader.BuildConfig
import com.vibecoding.reader.data.db.AppDatabase
import com.vibecoding.reader.data.import.BookImporter
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.repo.BookmarkRepository
import com.vibecoding.reader.data.repo.FolderRepository
import com.vibecoding.reader.data.repo.SettingsRepository
import com.vibecoding.reader.data.repo.ShelfRepository
import com.vibecoding.reader.data.secure.PdfPasswordAccess
import com.vibecoding.reader.data.storage.BookDiskCleanup
import com.vibecoding.reader.data.update.GithubUpdateRepository
import com.vibecoding.reader.data.update.UpdateApkVerifier
import com.vibecoding.reader.data.update.UpdateController
import com.vibecoding.reader.data.update.UpdateFileStore
import com.vibecoding.reader.data.update.UrlConnectionUpdateHttpClient
import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.reader.ExternalOpenIntent
import com.vibecoding.reader.domain.sync.NoOpSyncPort
import com.vibecoding.reader.domain.sync.SyncPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExternalOpenController {
    private val _pendingUri = MutableStateFlow<Uri?>(null)
    val pendingUri: StateFlow<Uri?> = _pendingUri.asStateFlow()

    fun offer(uri: Uri) {
        if (!ExternalOpenIntent.shouldOffer(_pendingUri.value?.toString(), uri.toString())) {
            return
        }
        _pendingUri.value = uri
    }

    fun consume() {
        _pendingUri.value = null
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val diskCleanup = BookDiskCleanup(appContext)

    val bookRepository = BookRepository(
        db.bookDao(),
        db.bookmarkDao(),
        db.folderDao(),
        diskCleanup
    )
    val folderRepository = FolderRepository(
        db.folderDao(),
        db.bookDao(),
        db.bookmarkDao(),
        diskCleanup
    )
    val shelfRepository = ShelfRepository(db.folderDao(), db.bookDao())
    val bookmarkRepository = BookmarkRepository(db.bookmarkDao())
    val settingsRepository = SettingsRepository(db.settingsDao())
    val bookImporter = BookImporter(appContext, bookRepository, folderRepository)
    val syncPort: SyncPort = NoOpSyncPort()
    val externalOpen = ExternalOpenController()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val updateController = UpdateController(
        context = appContext,
        enabled = BuildConfig.UPDATE_CHECKS_ENABLED,
        currentVersionCode = BuildConfig.VERSION_CODE,
        repository = GithubUpdateRepository(
            http = UrlConnectionUpdateHttpClient(),
            repo = GithubRepo(BuildConfig.UPDATE_GITHUB_OWNER, BuildConfig.UPDATE_GITHUB_REPO),
            userAgent = "DeepRead/${BuildConfig.VERSION_NAME} (Android)"
        ),
        store = UpdateFileStore(appContext),
        verifier = UpdateApkVerifier(appContext.packageManager, appContext.packageName),
        scope = appScope
    )

    init {
        appScope.launch {
            runCatching { PdfPasswordAccess.migrateLegacyAll(appContext, bookRepository) }
        }
    }
}
