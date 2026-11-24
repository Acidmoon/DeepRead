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

    companion object {
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
