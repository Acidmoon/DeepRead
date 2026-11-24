package com.vibecoding.reader.data.update

import android.content.Context
import java.io.File

class UpdateFileStore(context: Context) {
    private val appContext = context.applicationContext
    val directory: File = File(appContext.cacheDir, DIR_NAME)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun usableSpace(): Long {
        directory.mkdirs()
        return directory.usableSpace
    }

    fun newApkFile(versionCode: Int): File {
        directory.mkdirs()
        return File(directory, "deepread-$versionCode.apk")
    }

    var pendingInstallFile: File?
        get() = prefs.getString(KEY_PENDING, null)?.let { File(it) }?.takeIf { it.exists() }
        set(value) {
            val ed = prefs.edit()
            if (value == null) ed.remove(KEY_PENDING) else ed.putString(KEY_PENDING, value.absolutePath)
            ed.apply()
        }

    var lastAutoCheckAtMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO, value).apply()

    var dismissedVersionCode: Int
        get() = prefs.getInt(KEY_DISMISSED, 0)
        set(value) = prefs.edit().putInt(KEY_DISMISSED, value).apply()

    var cachedEtag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) {
            val ed = prefs.edit()
            if (value.isNullOrBlank()) ed.remove(KEY_ETAG) else ed.putString(KEY_ETAG, value)
            ed.apply()
        }

    fun cleanupObsolete() {
        val keep = pendingInstallFile?.canonicalFile
        val dir = directory
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (keep != null && file.canonicalFile == keep) return@forEach
            file.delete()
        }
    }

    fun deleteIfNotPending(file: File?) {
        if (file == null) return
        val pending = pendingInstallFile?.canonicalFile
        if (pending != null && file.canonicalFile == pending) return
        file.delete()
    }

    companion object {
        const val DIR_NAME = "updates"
        private const val PREFS = "reader_updates"
        private const val KEY_PENDING = "pending_install_file"
        private const val KEY_LAST_AUTO = "last_auto_check_at"
        private const val KEY_DISMISSED = "dismissed_version_code"
        private const val KEY_ETAG = "latest_etag"
    }
}
