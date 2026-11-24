package com.vibecoding.reader.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import com.vibecoding.reader.data.db.AppDatabase
import com.vibecoding.reader.data.repo.BookRepository
import com.vibecoding.reader.data.secure.PdfPasswordAccess
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * 系统全量备份 / 设备转移在打包前会启动本进程并调用 [onFullBackup]。
 * 先同步完成旧明文迁移，再决定是否带上 Room 数据库：
 * - 库中已无明文密码列：走默认规则，保留书架数据库和 files 里的书籍副本；
 * - 迁移失败仍有明文，或准备过程出错：本轮不备份数据库，只备份 files 与 SharedPreferences。
 *
 * 不宣称 VACUUM 能取证级清除页内残留；这一层只保证“仍可读的明文列”不会随库进入备份。
 */
class ReaderBackupAgent : BackupAgent() {

    @Throws(IOException::class)
    override fun onFullBackup(data: FullBackupDataOutput) {
        val includeDatabase = runCatching {
            runBlocking {
                val app = applicationContext
                val db = AppDatabase.get(app)
                val repo = BookRepository(db.bookDao(), db.bookmarkDao(), db.folderDao())
                PdfPasswordAccess.prepareDatabaseForBackup(app, repo)
            }
        }.getOrDefault(false)

        if (includeDatabase) {
            super.onFullBackup(data)
        } else {
            backupWithoutDatabase(data)
        }
    }

    // fullBackupOnly=true 时系统走全量备份，这两个密钥值回调不会被使用。
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit

    @Throws(IOException::class)
    private fun backupWithoutDatabase(data: FullBackupDataOutput) {
        backupTree(filesDir, data)
        backupTree(File(applicationInfo.dataDir, "shared_prefs"), data)
    }

    @Throws(IOException::class)
    private fun backupTree(root: File?, data: FullBackupDataOutput) {
        if (root == null || !root.exists()) return
        root.walkTopDown().forEach { file ->
            if (file.isFile && BackupContents.shouldBackupFile(file.absolutePath, includeDatabase = false)) {
                fullBackupFile(file, data)
            }
        }
    }
}

/**
 * 备份纳入规则的纯逻辑，便于单测。
 */
object BackupContents {
    fun shouldBackupFile(path: String, includeDatabase: Boolean): Boolean {
        val n = path.replace('\\', '/')
        if (n.contains("/no_backup/") || n.endsWith("/no_backup")) return false
        if (n.contains("/cache/") || n.contains("/code_cache/")) return false
        if (!includeDatabase && isDatabasePath(n)) return false
        return true
    }

    fun isDatabasePath(path: String): Boolean {
        val n = path.replace('\\', '/')
        if (n.contains("/databases/")) return true
        return n.endsWith(".db") || n.endsWith(".db-wal") || n.endsWith(".db-shm")
    }
}
