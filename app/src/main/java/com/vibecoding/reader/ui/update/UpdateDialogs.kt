package com.vibecoding.reader.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vibecoding.reader.data.update.UpdateController
import com.vibecoding.reader.data.update.UpdateUiState
import com.vibecoding.reader.domain.update.UpdateOffer
import com.vibecoding.reader.domain.update.UpdateStreamSaver

@Composable
fun UpdateHost(
    controller: UpdateController,
    autoCheckOnEnter: Boolean
) {
    val state by controller.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(autoCheckOnEnter, controller) {
        if (autoCheckOnEnter) controller.onHomeEntered()
    }
    LaunchedEffect(controller, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            controller.onHostResumed()
        }
    }
    when (val current = state) {
        UpdateUiState.Idle -> Unit
        UpdateUiState.Checking -> CheckingDialog(onDismiss = controller::dismissPrompt)
        is UpdateUiState.Prompt -> PromptDialog(
            offer = current.offer,
            onConfirm = { controller.confirmDownload(current.offer) },
            onDismiss = controller::dismissPrompt
        )
        is UpdateUiState.Downloading -> DownloadDialog(
            offer = current.offer,
            downloaded = current.downloaded,
            total = current.total,
            onCancel = controller::cancelDownload
        )
        is UpdateUiState.ReadyToInstall -> ReadyDialog(
            offer = current.offer,
            onInstall = { controller.requestInstall(current.offer, current.file) },
            onDismiss = controller::dismissPrompt
        )
        is UpdateUiState.AwaitingPermission -> {
            var visible by remember(current) { mutableStateOf(true) }
            if (visible) {
                PermissionDialog(onDismiss = { visible = false })
            }
        }
        is UpdateUiState.Installing -> InstallingDialog()
        is UpdateUiState.Message -> MessageDialog(
            text = current.text,
            onDismiss = controller::dismissPrompt
        )
    }
}

@Composable
private fun CheckingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = { Text("正在查询 GitHub 上的正式版本…") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PromptDialog(offer: UpdateOffer, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val notes = offer.manifest.releaseNotes.joinToString("\n") { "• $it" }
    val preface = if (offer.belowMinSupported || offer.manifest.mandatory) {
        "当前版本过低，建议尽快更新到 ${offer.manifest.versionName}。\n\n"
    } else {
        "发现新版本 ${offer.manifest.versionName}（versionCode ${offer.manifest.versionCode}）。\n\n"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用更新") },
        text = {
            Text(
                preface +
                    "大小 ${UpdateStreamSaver.formatSize(offer.manifest.bytes)}。\n\n" +
                    if (notes.isBlank()) "下载后会校验安装包，并由系统确认安装。"
                    else "更新说明：\n$notes\n\n下载后会校验安装包，并由系统确认安装。"
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("下载") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
    )
}

@Composable
private fun DownloadDialog(
    offer: UpdateOffer,
    downloaded: Long,
    total: Long,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 下载中避免误关，使用取消按钮 */ },
        title = { Text("正在下载 ${offer.manifest.versionName}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = UpdateStreamSaver.coerceProgress(downloaded, total),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${UpdateStreamSaver.formatSize(downloaded)} / ${UpdateStreamSaver.formatSize(total)}"
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

@Composable
private fun ReadyDialog(offer: UpdateOffer, onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("校验已通过") },
        text = { Text("版本 ${offer.manifest.versionName} 已准备好安装。系统会再次请你确认，不会静默安装。") },
        confirmButton = { TextButton(onClick = onInstall) { Text("安装") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
    )
}

@Composable
private fun PermissionDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要安装权限") },
        text = { Text("请允许本应用安装未知来源的应用。返回后会继续刚才的安装，阅读不会被中断。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
private fun InstallingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在提交安装") },
        text = { Text("请在系统安装界面中确认。") },
        confirmButton = {}
    )
}

@Composable
private fun MessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
    )
}
