package com.vibecoding.reader.data.update

import android.content.Context
import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.update.UpdateCheckOutcome
import com.vibecoding.reader.domain.update.UpdateError
import com.vibecoding.reader.domain.update.UpdateManifestParser
import com.vibecoding.reader.domain.update.UpdateNoticeKind
import com.vibecoding.reader.domain.update.UpdateOffer
import com.vibecoding.reader.domain.update.UpdateStreamSaver
import com.vibecoding.reader.domain.update.UpdateVersionPolicy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Prompt(val offer: UpdateOffer, val kind: UpdateNoticeKind) : UpdateUiState()
    data class Downloading(
        val offer: UpdateOffer,
        val downloaded: Long,
        val total: Long
    ) : UpdateUiState()
    data class ReadyToInstall(val offer: UpdateOffer, val file: File) : UpdateUiState()
    data class AwaitingPermission(val offer: UpdateOffer, val file: File) : UpdateUiState()
    data class Installing(val offer: UpdateOffer, val file: File) : UpdateUiState()
    data class Message(val text: String, val isError: Boolean) : UpdateUiState()
}

class UpdateController(
    context: Context,
    private val enabled: Boolean,
    private val currentVersionCode: Int,
    private val repository: GithubUpdateRepository,
    private val store: UpdateFileStore,
    private val verifier: UpdateApkVerifier,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext
    private val _ui = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private var work: Job? = null
    private val cancelled = AtomicBoolean(false)
    private var lastOffer: UpdateOffer? = null

    fun onHomeEntered() {
        store.cleanupObsolete()
        restorePending()
        if (!enabled) return
        if (!UpdateVersionPolicy.shouldAutoCheck(store.lastAutoCheckAtMs.takeIf { it > 0L }, nowMs())) {
            return
        }
        startCheck(UpdateNoticeKind.Silent)
    }

    fun checkManually() {
        if (!enabled) {
            _ui.value = UpdateUiState.Message("检查更新仅在正式版可用。", isError = false)
            return
        }
        startCheck(UpdateNoticeKind.Manual)
    }

    fun dismissPrompt() {
        val state = _ui.value
        if (state is UpdateUiState.Prompt) {
            store.dismissedVersionCode = state.offer.manifest.versionCode
        }
        if (state is UpdateUiState.Message) {
            _ui.value = UpdateUiState.Idle
            return
        }
        _ui.value = UpdateUiState.Idle
    }

    fun confirmDownload(offer: UpdateOffer) {
        if (!enabled) return
        work?.cancel()
        cancelled.set(false)
        work = scope.launch(Dispatchers.IO) {
            downloadAndVerify(offer)
        }
    }

    fun cancelDownload() {
        cancelled.set(true)
        work?.cancel()
        val state = _ui.value
        if (state is UpdateUiState.Downloading) {
            store.deleteIfNotPending(store.newApkFile(state.offer.manifest.versionCode))
        }
        _ui.value = UpdateUiState.Idle
    }

    fun requestInstall(offer: UpdateOffer, file: File) {
        if (!file.isFile) {
            _ui.value = UpdateUiState.Message("找不到已下载的更新包", true)
            return
        }
        store.pendingInstallFile = file
        if (!UpdateInstallSupport.canRequestInstall(appContext)) {
            _ui.value = UpdateUiState.AwaitingPermission(offer, file)
            appContext.startActivity(UpdateInstallSupport.unknownSourcesIntent(appContext.packageName))
            return
        }
        commitInstall(offer, file)
    }

    fun onHostResumed() {
        val state = _ui.value
        if (state is UpdateUiState.AwaitingPermission && UpdateInstallSupport.canRequestInstall(appContext)) {
            commitInstall(state.offer, state.file)
        }
    }

    fun onInstallFinished(file: File?, error: String?) {
        scope.launch(Dispatchers.Main) {
            if (error == null) {
                store.pendingInstallFile = null
                file?.delete()
                store.cleanupObsolete()
                lastOffer = null
                _ui.value = UpdateUiState.Message("更新已提交安装", false)
            } else {
                val offer = lastOffer
                if (offer != null && file != null && file.isFile) {
                    _ui.value = UpdateUiState.ReadyToInstall(offer, file)
                } else {
                    _ui.value = UpdateUiState.Message(error, true)
                }
            }
        }
    }

    private fun restorePending() {
        val pending = store.pendingInstallFile ?: return
        val offer = lastOffer
        if (offer != null) {
            _ui.value = UpdateUiState.ReadyToInstall(offer, pending)
        }
    }

    private fun startCheck(kind: UpdateNoticeKind) {
        work?.cancel()
        cancelled.set(false)
        work = scope.launch(Dispatchers.IO) {
            if (kind == UpdateNoticeKind.Manual) {
                _ui.value = UpdateUiState.Checking
            }
            val outcome = try {
                repository.check(currentVersionCode, null)
            } catch (e: UpdateError) {
                UpdateCheckOutcome.Failed(e)
            } catch (e: Exception) {
                UpdateCheckOutcome.Failed(UpdateError.Network(e.message ?: "网络不可用"))
            }
            if (kind == UpdateNoticeKind.Silent) {
                store.lastAutoCheckAtMs = nowMs()
            }
            withContext(Dispatchers.Main) {
                applyOutcome(outcome, kind)
            }
        }
    }

    private fun applyOutcome(outcome: UpdateCheckOutcome, kind: UpdateNoticeKind) {
        when (outcome) {
            UpdateCheckOutcome.Disabled ->
                _ui.value = if (kind == UpdateNoticeKind.Manual) {
                    UpdateUiState.Message("检查更新仅在正式版可用。", false)
                } else UpdateUiState.Idle
            UpdateCheckOutcome.UpToDate ->
                _ui.value = if (kind == UpdateNoticeKind.Manual) {
                    UpdateUiState.Message("已是最新版本", false)
                } else UpdateUiState.Idle
            UpdateCheckOutcome.NoPublishedRelease ->
                _ui.value = if (kind == UpdateNoticeKind.Manual) {
                    UpdateUiState.Message("还没有已发布的正式更新。", false)
                } else UpdateUiState.Idle
            is UpdateCheckOutcome.RateLimited ->
                _ui.value = if (kind == UpdateNoticeKind.Manual) {
                    UpdateUiState.Message("检查更新过于频繁，请稍后再试。", true)
                } else UpdateUiState.Idle
            is UpdateCheckOutcome.Failed ->
                _ui.value = if (kind == UpdateNoticeKind.Manual) {
                    UpdateUiState.Message(outcome.error.userMessage, true)
                } else UpdateUiState.Idle
            is UpdateCheckOutcome.Available -> {
                lastOffer = outcome.offer
                if (kind == UpdateNoticeKind.Silent &&
                    store.dismissedVersionCode == outcome.offer.manifest.versionCode
                ) {
                    _ui.value = UpdateUiState.Idle
                } else {
                    _ui.value = UpdateUiState.Prompt(outcome.offer, kind)
                }
            }
        }
    }

    private fun downloadAndVerify(offer: UpdateOffer) {
        val dest = store.newApkFile(offer.manifest.versionCode)
        try {
            val spaceError = UpdateStreamSaver.spaceError(offer.manifest.bytes, store.usableSpace())
            if (spaceError != null) throw UpdateError.Storage(spaceError)
            store.cleanupObsolete()
            if (dest.exists()) dest.delete()
            _ui.value = UpdateUiState.Downloading(offer, 0L, offer.manifest.bytes)
            repository.downloadToFile(
                url = offer.apk.browserDownloadUrl,
                dest = dest,
                expectedBytes = offer.manifest.bytes,
                expectedSha256 = offer.manifest.sha256,
                maxBytes = UpdateManifestParser.MAX_APK_BYTES,
                cancelled = { cancelled.get() },
                onProgress = { d, t -> _ui.value = UpdateUiState.Downloading(offer, d, t) }
            )
            if (cancelled.get()) throw UpdateError.Cancelled()
            verifier.verify(dest, offer.manifest)
            store.pendingInstallFile = dest
            lastOffer = offer
            _ui.value = UpdateUiState.ReadyToInstall(offer, dest)
        } catch (e: UpdateError) {
            store.deleteIfNotPending(dest)
            if (e is UpdateError.Cancelled) {
                _ui.value = UpdateUiState.Idle
            } else {
                _ui.value = UpdateUiState.Message(e.userMessage, true)
            }
        } catch (e: Exception) {
            store.deleteIfNotPending(dest)
            _ui.value = UpdateUiState.Message(e.message ?: "下载失败", true)
        }
    }

    private fun commitInstall(offer: UpdateOffer, file: File) {
        _ui.value = UpdateUiState.Installing(offer, file)
        try {
            UpdateInstallSupport.startSession(appContext, file)
        } catch (e: UpdateError) {
            _ui.value = UpdateUiState.Message(e.userMessage, true)
        } catch (e: Exception) {
            _ui.value = UpdateUiState.Message(e.message ?: "无法启动安装", true)
        }
    }

    companion object {
        fun repoFromBuild(owner: String, name: String): GithubRepo = GithubRepo(owner, name)
    }
}
