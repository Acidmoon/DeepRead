package com.vibecoding.reader.domain.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 阅读进度：先记下最新位置，默认 [debounceMs] 后再落库；
 * 切到后台或离开阅读页时应调用 [flush]，避免只靠防抖导致最后一次进度丢失。
 */
class ProgressSaveController(
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val persist: suspend (position: String, progress: Float) -> Unit
) {
    @Volatile
    var latestPosition: String? = null
        private set

    @Volatile
    var latestProgress: Float = 0f
        private set

    @Volatile
    private var lastSavedPosition: String? = null

    private val mutex = Mutex()
    private var job: Job? = null

    val hasUnsaved: Boolean
        get() {
            val pos = latestPosition ?: return false
            return pos != lastSavedPosition
        }

    fun updateLatest(position: String, progress: Float) {
        if (position.isBlank()) return
        latestPosition = position
        latestProgress = progress.coerceIn(0f, 1f)
    }

    fun onProgress(position: String, progress: Float, scope: CoroutineScope) {
        updateLatest(position, progress)
        if (position.isBlank()) return
        if (position == lastSavedPosition) return
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    suspend fun flush() {
        job?.cancel()
        job = null
        mutex.withLock {
            val pos = latestPosition ?: return
            if (pos == lastSavedPosition) return
            val progress = latestProgress
            persist(pos, progress)
            lastSavedPosition = pos
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 350L
    }
}
