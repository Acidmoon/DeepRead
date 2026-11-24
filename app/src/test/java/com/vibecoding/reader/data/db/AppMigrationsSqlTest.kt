package com.vibecoding.reader.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * 仓库没有 v1–v7 的 exported schema，无法用 MigrationTestHelper 对照官方快照。
 * 本测试用独立 SQLite 建出根据后续 ALTER 反推的 v1 库，按序执行迁移 SQL，
 * 确认书架记录仍在，且 v8 所需列存在。
 */
class AppMigrationsSqlTest {

    @Test
    fun migrateV1ToV8KeepsBookAndAddsColumns() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st ->
                AppMigrations.V1_DDL.forEach { st.execute(it) }
                st.execute(
                    """
                    INSERT INTO books (id, title, author, format, sourceUri, localPath, coverPath,
                        addedAt, lastOpenedAt, lastPosition, progressPercent, remoteId, updatedAt)
                    VALUES ('b1', '旧书', null, 'TXT', 'content://x', '/tmp/a.txt', null,
                        1, 0, '', 0, null, 1)
                    """.trimIndent()
                )
                st.execute(
                    """
                    INSERT INTO reading_settings (id, backgroundColor, textColor, fontSizeSp,
                        lineSpacingMultiplier, pageTurnMode, horizontalPaddingDp, verticalPaddingDp)
                    VALUES (1, 1, 1, 20, 1.6, 'VERTICAL', 24, 20)
                    """.trimIndent()
                )
                AppMigrations.ALL_SQL_IN_ORDER.forEach { step ->
                    step.forEach { st.execute(it) }
                }

                val books = st.executeQuery("SELECT title, folderId, pdfPassword FROM books WHERE id='b1'")
                assertTrue(books.next())
                assertEquals("旧书", books.getString("title"))
                books.getString("folderId")
                books.getString("pdfPassword")
                books.close()

                val cols = mutableSetOf<String>()
                val rs = st.executeQuery("PRAGMA table_info(reading_settings)")
                while (rs.next()) cols += rs.getString("name")
                rs.close()
                listOf(
                    "pdfPageTurnMode", "pdfBackgroundColor", "screenDim",
                    "autoPageTurnEnabled", "autoPageIntervalSec", "autoScrollLinesPerSec",
                    "pdfRightToLeft", "keepScreenOn", "showStatusOverlay"
                ).forEach { name ->
                    assertTrue("missing $name", cols.contains(name))
                }

                val tables = mutableSetOf<String>()
                val ts = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
                while (ts.next()) tables += ts.getString("name")
                ts.close()
                assertTrue(tables.contains("folders"))
            }
        }
    }

    /**
     * 核对仓库跟踪的当前 exported schema，不是历史 v1–v7 快照回放。
     */
    @Test
    fun exportedV8SchemaContainsExpectedBookColumns() {
        val schema = File("schemas/com.vibecoding.reader.data.db.AppDatabase/8.json")
        assertTrue("exported schema missing: ${schema.absolutePath}", schema.isFile)
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 8") || text.contains("\"version\":8"))
        for (col in listOf("id", "title", "folderId", "pdfPassword", "localPath", "sourceUri")) {
            assertTrue("missing $col in exported v8 schema", text.contains("\"fieldPath\": \"$col\""))
        }
        assertEquals(8, AppMigrations.CURRENT_VERSION)
    }
}
