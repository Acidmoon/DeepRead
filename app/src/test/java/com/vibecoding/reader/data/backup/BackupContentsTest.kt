package com.vibecoding.reader.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContentsTest {

    @Test
    fun skipsNoBackupAndCache() {
        assertFalse(
            BackupContents.shouldBackupFile(
                "/data/user/0/com.vibecoding.reader/no_backup/pdf-passwords.dat",
                includeDatabase = true
            )
        )
        assertFalse(
            BackupContents.shouldBackupFile(
                "/data/user/0/com.vibecoding.reader/cache/tmp",
                includeDatabase = true
            )
        )
        assertTrue(
            BackupContents.shouldBackupFile(
                "/data/user/0/com.vibecoding.reader/files/books/id/original.pdf",
                includeDatabase = false
            )
        )
    }

    @Test
    fun skipsDatabaseFilesWhenNotIncluded() {
        val db = "/data/user/0/com.vibecoding.reader/databases/reader.db"
        val wal = "/data/user/0/com.vibecoding.reader/databases/reader.db-wal"
        assertFalse(BackupContents.shouldBackupFile(db, includeDatabase = false))
        assertFalse(BackupContents.shouldBackupFile(wal, includeDatabase = false))
        assertTrue(BackupContents.shouldBackupFile(db, includeDatabase = true))
    }
}
