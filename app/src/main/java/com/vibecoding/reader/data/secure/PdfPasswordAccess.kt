package com.vibecoding.reader.data.secure

import android.content.Context
import com.vibecoding.reader.data.db.AppDatabase
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.domain.model.Book
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PdfPasswordAccess {
    private val migrateMutex = Mutex()

    /**
     * 只有保险箱已经持有密码时，才允许清空库里的明文列。
     * 写入失败时必须保留明文，否则会丢密码。
     */
    fun canClearPlaintextColumn(vaultHoldsPassword: Boolean): Boolean = vaultHoldsPassword

    /**
     * 库中仍有明文密码时，这一次系统备份不得带上数据库。
     * 不宣称页内残留被取证级擦除；只保证仍可读的明文列不会随库文件被备份。
     */
    fun includeDatabaseInBackup(leftoverPlaintextCount: Int): Boolean =
        leftoverPlaintextCount <= 0

    /**
     * 优先读 no_backup 保险箱；若只有库里的旧明文则迁入保险箱并清空列。
     */
    suspend fun resolve(
        context: Context,
        book: Book,
        bookRepository: BookRepository,
        scrubAfterClear: Boolean = true
    ): String? {
        val store = PdfPasswordStore.get(context)
        val stored = runCatching { store.get(book.id) }.getOrNull()
        if (!stored.isNullOrEmpty()) {
            if (!book.pdfPassword.isNullOrEmpty() && canClearPlaintextColumn(true)) {
                clearPlaintextColumn(book, bookRepository)
                if (scrubAfterClear) scrubDatabase(context)
            }
            return stored
        }
        val legacy = book.pdfPassword?.takeIf { it.isNotEmpty() } ?: return null
        val migrated = runCatching { store.put(book.id, legacy); true }.getOrDefault(false)
        if (canClearPlaintextColumn(migrated)) {
            clearPlaintextColumn(book, bookRepository)
            if (scrubAfterClear) scrubDatabase(context)
        }
        return legacy
    }

    /**
     * 把所有仍留在库中的明文密码迁入保险箱，并做一次 WAL checkpoint + VACUUM。
     * 与备份代理共用同一把锁，避免启动异步迁移与 onFullBackup 并发。
     */
    suspend fun migrateLegacyAll(context: Context, bookRepository: BookRepository) {
        migrateMutex.withLock {
            migrateLegacyAllLocked(context, bookRepository)
        }
    }

    /**
     * 供 [android.app.backup.BackupAgent.onFullBackup] 在打包前同步调用。
     * @return 是否可以把数据库纳入这一次备份
     */
    suspend fun prepareDatabaseForBackup(
        context: Context,
        bookRepository: BookRepository
    ): Boolean {
        return migrateMutex.withLock {
            migrateLegacyAllLocked(context, bookRepository)
            includeDatabaseInBackup(bookRepository.getWithPlaintextPassword().size)
        }
    }

    suspend fun clearPlaintextIfVaultOk(
        context: Context,
        book: Book,
        bookRepository: BookRepository,
        vaultWriteSucceeded: Boolean
    ) {
        if (!canClearPlaintextColumn(vaultWriteSucceeded)) return
        if (book.pdfPassword.isNullOrEmpty()) return
        clearPlaintextColumn(book, bookRepository)
        scrubDatabase(context)
    }

    private suspend fun migrateLegacyAllLocked(
        context: Context,
        bookRepository: BookRepository
    ) {
        val leftovers = bookRepository.getWithPlaintextPassword()
        if (leftovers.isEmpty()) return
        var cleared = 0
        for (book in leftovers) {
            resolve(context, book, bookRepository, scrubAfterClear = false)
            val after = bookRepository.getBook(book.id)?.pdfPassword
            if (after.isNullOrEmpty()) cleared++
        }
        if (cleared > 0) {
            scrubDatabase(context)
        }
    }

    private suspend fun clearPlaintextColumn(book: Book, bookRepository: BookRepository) {
        bookRepository.upsert(
            book.copy(pdfPassword = null, updatedAt = System.currentTimeMillis())
        )
    }

    private fun scrubDatabase(context: Context) {
        runCatching { AppDatabase.get(context).scrubAfterPasswordClear() }
    }
}
