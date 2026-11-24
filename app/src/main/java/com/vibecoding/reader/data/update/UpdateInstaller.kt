package com.vibecoding.reader.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.vibecoding.reader.domain.update.UpdateError
import java.io.File

object UpdateInstallSupport {
    const val FILE_PROVIDER_SUFFIX = ".update.fileprovider"
    const val ACTION_INSTALL_STATUS = "com.vibecoding.reader.update.INSTALL_STATUS"
    const val EXTRA_FILE = "update_apk_path"

    fun providerAuthority(packageName: String): String = packageName + FILE_PROVIDER_SUFFIX

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(packageName: String): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun apkContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            providerAuthority(context.packageName),
            file
        )
    }

    fun startSession(context: Context, file: File) {
        if (!file.isFile) throw UpdateError.Install("找不到待安装的更新包")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        var sessionId = -1
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("deepread.apk", 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val status = Intent(context, UpdateInstallStatusReceiver::class.java).apply {
                    action = ACTION_INSTALL_STATUS
                    putExtra(EXTRA_FILE, file.absolutePath)
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, status, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            if (e is UpdateError) throw e
            throw UpdateError.Install(e.message ?: "无法启动系统安装流程")
        }
    }
}
