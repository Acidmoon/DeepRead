package com.vibecoding.reader.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 共享迁移 SQL，供 Room 与 JVM 迁移校验使用。
 * 仓库里没有 v1–v7 的历史 exported schema，v1 结构是根据后续 ALTER 反推并写在 [V1_DDL] 中。
 */
object AppMigrations {
    const val START_VERSION = 1
    const val CURRENT_VERSION = 8

    val V1_DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS books (
            id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL,
            author TEXT,
            format TEXT NOT NULL,
            sourceUri TEXT NOT NULL,
            localPath TEXT NOT NULL,
            coverPath TEXT,
            addedAt INTEGER NOT NULL,
            lastOpenedAt INTEGER NOT NULL,
            lastPosition TEXT NOT NULL,
            progressPercent REAL NOT NULL,
            remoteId TEXT,
            updatedAt INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS bookmarks (
            id TEXT NOT NULL PRIMARY KEY,
            bookId TEXT NOT NULL,
            position TEXT NOT NULL,
            label TEXT,
            createdAt INTEGER NOT NULL,
            remoteId TEXT,
            updatedAt INTEGER NOT NULL,
            FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)",
        """
        CREATE TABLE IF NOT EXISTS reading_settings (
            id INTEGER NOT NULL PRIMARY KEY,
            backgroundColor INTEGER NOT NULL,
            textColor INTEGER NOT NULL,
            fontSizeSp REAL NOT NULL,
            lineSpacingMultiplier REAL NOT NULL,
            pageTurnMode TEXT NOT NULL,
            horizontalPaddingDp REAL NOT NULL,
            verticalPaddingDp REAL NOT NULL
        )
        """.trimIndent()
    )

    val SQL_1_2 = listOf(
        "ALTER TABLE reading_settings ADD COLUMN pdfPageTurnMode TEXT NOT NULL DEFAULT 'BOTH'",
        "ALTER TABLE reading_settings ADD COLUMN pdfBackgroundColor INTEGER NOT NULL DEFAULT -16777216"
    )
    val SQL_2_3 = listOf(
        "CREATE TABLE IF NOT EXISTS folders (" +
            "id TEXT NOT NULL PRIMARY KEY, " +
            "name TEXT NOT NULL, " +
            "addedAt INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL)",
        "ALTER TABLE books ADD COLUMN folderId TEXT",
        "CREATE INDEX IF NOT EXISTS index_books_folderId ON books(folderId)"
    )
    val SQL_3_4 = listOf(
        "ALTER TABLE reading_settings ADD COLUMN screenDim REAL NOT NULL DEFAULT 0"
    )
    val SQL_4_5 = listOf(
        "ALTER TABLE reading_settings ADD COLUMN autoPageTurnEnabled INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE reading_settings ADD COLUMN autoPageIntervalSec REAL NOT NULL DEFAULT 8",
        "ALTER TABLE reading_settings ADD COLUMN autoScrollLinesPerSec REAL NOT NULL DEFAULT 1.2"
    )
    val SQL_5_6 = listOf(
        "ALTER TABLE reading_settings ADD COLUMN pdfRightToLeft INTEGER NOT NULL DEFAULT 0"
    )
    val SQL_6_7 = listOf(
        "ALTER TABLE books ADD COLUMN pdfPassword TEXT"
    )
    val SQL_7_8 = listOf(
        "ALTER TABLE reading_settings ADD COLUMN keepScreenOn INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE reading_settings ADD COLUMN showStatusOverlay INTEGER NOT NULL DEFAULT 1"
    )

    val MIGRATION_1_2 = sqlMigration(1, 2, SQL_1_2)
    val MIGRATION_2_3 = sqlMigration(2, 3, SQL_2_3)
    val MIGRATION_3_4 = sqlMigration(3, 4, SQL_3_4)
    val MIGRATION_4_5 = sqlMigration(4, 5, SQL_4_5)
    val MIGRATION_5_6 = sqlMigration(5, 6, SQL_5_6)
    val MIGRATION_6_7 = sqlMigration(6, 7, SQL_6_7)
    val MIGRATION_7_8 = sqlMigration(7, 8, SQL_7_8)

    val ALL = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        MIGRATION_7_8
    )

    val ALL_SQL_IN_ORDER: List<List<String>> = listOf(
        SQL_1_2, SQL_2_3, SQL_3_4, SQL_4_5, SQL_5_6, SQL_6_7, SQL_7_8
    )

    private fun sqlMigration(from: Int, to: Int, statements: List<String>): Migration =
        object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                statements.forEach { db.execSQL(it) }
            }
        }
}
