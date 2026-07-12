package com.vibecoding.reader.domain.sync

/**
 * 云同步预留接口。MVP 仅本地，使用 [NoOpSyncPort]。
 * 未来可同步：书籍元数据、进度、书签、阅读设置（正文文件可选上传）。
 */
interface SyncPort {
    suspend fun pushChanges(since: Long): SyncResult
    suspend fun pullChanges(since: Long): SyncResult
}

sealed class SyncResult {
    data object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
}

class NoOpSyncPort : SyncPort {
    override suspend fun pushChanges(since: Long): SyncResult = SyncResult.Success
    override suspend fun pullChanges(since: Long): SyncResult = SyncResult.Success
}
