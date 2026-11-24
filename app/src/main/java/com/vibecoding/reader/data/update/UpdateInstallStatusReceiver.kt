package com.vibecoding.reader.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.vibecoding.reader.ReaderApp
import java.io.File

class UpdateInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstallSupport.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val path = intent.getStringExtra(UpdateInstallSupport.EXTRA_FILE)
        val file = path?.let { File(it) }
        val app = context.applicationContext as? ReaderApp ?: return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> app.container.updateController.onInstallFinished(file, null)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                app.container.updateController.onInstallFinished(file, "已取消安装")
            else -> app.container.updateController.onInstallFinished(
                file,
                message?.ifBlank { null } ?: "安装失败"
            )
        }
    }
}
