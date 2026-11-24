package com.vibecoding.reader.data.storage

import android.content.Context
import com.vibecoding.reader.data.parser.PdfTextIndex
import com.vibecoding.reader.data.secure.PdfPasswordStore
import java.io.File

/** 删除书籍时同时清掉 filesDir 副本、no_backup 文字索引和保险箱里的密码。 */
class BookDiskCleanup(private val context: Context) {
    fun deleteFor(bookId: String, localPath: String) {
        runCatching {
            val file = File(localPath)
            if (file.exists()) file.delete()
            val parent = file.parentFile
            parent?.takeIf { it.name == bookId }?.deleteRecursively()
        }
        runCatching { PdfTextIndex.deleteStorage(context.noBackupFilesDir, bookId) }
        runCatching { PdfPasswordStore.get(context).remove(bookId) }
    }
}
