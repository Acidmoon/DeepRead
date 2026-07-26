package com.vibecoding.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vibecoding.reader.domain.model.PageTurnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        FolderEntity::class,
        BookEntity::class,
        BookmarkEntity::class,
        ReadingSettingsEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // 各版本只新增了列/表，迁移均为追加式，保证升级不丢书架与进度
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v2：PDF 独立设置
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN pdfPageTurnMode TEXT NOT NULL DEFAULT 'BOTH'"
                )
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN pdfBackgroundColor INTEGER NOT NULL DEFAULT -16777216"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v3：书架文件夹
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS folders (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE books ADD COLUMN folderId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_books_folderId ON books(folderId)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v4：应用内亮度叠层
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN screenDim REAL NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v5：自动翻页
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN autoPageTurnEnabled INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN autoPageIntervalSec REAL NOT NULL DEFAULT 8"
                )
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN autoScrollLinesPerSec REAL NOT NULL DEFAULT 1.2"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v6：PDF 从右往左翻页
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN pdfRightToLeft INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v7：书记住 PDF 打开密码
                db.execSQL("ALTER TABLE books ADD COLUMN pdfPassword TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v8：阅读不息屏 + 底部状态浮层可隐
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN keepScreenOn INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE reading_settings ADD COLUMN showStatusOverlay INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reader.db"
                )
                    // 正常升级走上面的迁移；仅对无迁移路径的异常旧库兜底重建
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                        }
                    }).build().also { built ->
                        instance = built
                        CoroutineScope(Dispatchers.IO).launch {
                            val existing = built.settingsDao().get()
                            if (existing == null) {
                                built.settingsDao().upsert(ReadingSettingsEntity.default())
                            } else if (existing.pageTurnMode == PageTurnMode.BOTH.name) {
                                // 产品默认改为上下滚动：迁移旧版默认 BOTH
                                built.settingsDao().upsert(
                                    existing.copy(pageTurnMode = PageTurnMode.VERTICAL.name)
                                )
                            }
                        }
                    }
            }
        }
    }
}
