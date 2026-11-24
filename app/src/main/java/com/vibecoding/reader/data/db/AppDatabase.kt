package com.vibecoding.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = AppMigrations.CURRENT_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun settingsDao(): SettingsDao

    /**
     * 清空 pdfPassword 列之后调用：把 WAL 合入主库并 VACUUM，
     * 减少更新后页内未使用空间里残留的明文被系统备份带走。
     * 不能在事务中调用。
     */
    fun scrubAfterPasswordClear() {
        synchronized(scrubLock) {
            val db = openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                cursor.moveToFirst()
            }
            db.execSQL("VACUUM")
        }
    }

    companion object {
        private val scrubLock = Any()

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "reader.db"
                )
                    // 正常升级走 AppMigrations；仅 debug 对无迁移路径的异常旧库兜底重建
                    .addMigrations(*AppMigrations.ALL)
                    .also { builder ->
                        if (com.vibecoding.reader.BuildConfig.DEBUG) {
                            builder.fallbackToDestructiveMigration()
                        }
                    }
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
                            }
                            // 不再把用户选择的 BOTH（滑动+点按）改回 VERTICAL
                        }
                    }
            }
        }
    }
}
