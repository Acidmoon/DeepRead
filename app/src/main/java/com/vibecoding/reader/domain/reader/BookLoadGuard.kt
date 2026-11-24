package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.BookFormat
import java.io.File
import java.util.Locale
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * 大文件 / 损坏文件加载策略与错误文案（纯逻辑，可单测）。
 *
 * 电子书（TXT/MD/EPUB/DOCX）需整本读入内存重排，受 [HARD_LIMIT_BYTES] 限制；
 * PDF 按页流式渲染（系统 PdfRenderer / PdfBox 都是随用随取，不整本入内存），
 * 适用宽松得多的 [PDF_HARD_LIMIT_BYTES]。
 */
object BookLoadGuard {
    /** 超过此大小显示“大文件加载中”提示 */
    const val LARGE_WARN_BYTES: Long = 8L * 1024 * 1024

    /** 电子书超过此大小拒绝整本读入内存（防 OOM） */
    const val HARD_LIMIT_BYTES: Long = 64L * 1024 * 1024

    /** PDF 按页渲染，上限仅防御极端异常文件 */
    const val PDF_HARD_LIMIT_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** 加载超时（毫秒） */
    const val LOAD_TIMEOUT_MS: Long = 90_000L

    fun fileSize(file: File): Long = if (file.exists()) file.length() else -1L

    fun isLarge(bytes: Long): Boolean = bytes >= LARGE_WARN_BYTES

    fun exceedsHardLimit(bytes: Long): Boolean = bytes > HARD_LIMIT_BYTES

    fun loadingMessage(bytes: Long): String {
        return if (isLarge(bytes)) {
            "正在加载大文件（${formatSize(bytes)}），请稍候…"
        } else {
            "正在打开…"
        }
    }

    fun hardLimitMessage(bytes: Long, limit: Long = HARD_LIMIT_BYTES): String =
        "文件过大（${formatSize(bytes)}），暂不支持超过 ${formatSize(limit)} 的文件"

    fun classifyError(error: Throwable): String {
        val msg = error.message?.trim().orEmpty()
        return when {
            error is TimeoutException || msg.contains("timeout", ignoreCase = true) ->
                "加载超时，请重试或换较小文件"
            error is OutOfMemoryError || msg.contains("OutOfMemory", ignoreCase = true) ->
                "内存不足，文件可能过大"
            error is SecurityException ->
                "没有读取权限，请重新导入"
            msg.contains("本地文件丢失") || (error is IOException && msg.contains("找不到")) ->
                "本地文件丢失，请重新导入"
            msg.contains("损坏") || msg.contains("invalid", ignoreCase = true) ||
                msg.contains("解析") || msg.contains("EPUB") || msg.contains("ZIP") ->
                if (msg.isNotBlank()) msg else "文件可能已损坏，无法打开"
            msg.isNotBlank() -> msg
            else -> "打开失败，请重试"
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知大小"
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1fMB", mb)
    }

    /** 打开前预检：返回错误文案；null 表示可继续。PDF 按页流式渲染，适用独立上限 */
    fun precheck(file: File, format: BookFormat? = null): String? {
        if (!file.exists()) return "本地文件丢失，请重新导入"
        if (!file.isFile) return "路径不是有效文件"
        val size = file.length()
        if (size <= 0L) return "文件为空或无法读取"
        val limit = if (format == BookFormat.PDF) PDF_HARD_LIMIT_BYTES else HARD_LIMIT_BYTES
        if (size > limit) return hardLimitMessage(size, limit)
        return null
    }
}
