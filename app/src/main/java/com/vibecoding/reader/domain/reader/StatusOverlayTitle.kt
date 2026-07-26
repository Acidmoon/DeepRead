package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind

/**
 * 底部状态浮层左侧标题解析（纯函数，便于单测）。
 *
 * - 电子书：按字符偏移取当前「章」（跳过卷）
 * - PDF：按页码取当前大纲项
 * - 左侧文案：章节名 + 进度组合
 */
object StatusOverlayTitle {

    /**
     * 电子书：当前偏移所在章节标题；无目录或无法解析时返回 null。
     */
    fun forEbook(toc: List<TocEntry>, charOffset: Int?): String? {
        if (toc.isEmpty() || charOffset == null) return null
        val chapters = toc.mapNotNull { entry ->
            if (entry.kind == TocKind.VOLUME) return@mapNotNull null
            val o = (ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset)?.offset
                ?: return@mapNotNull null
            val title = entry.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            title to o
        }.sortedBy { it.second }

        val starts = chapters.ifEmpty {
            // 无章条目时退回全部可解析条目（兼容异常缓存）
            toc.mapNotNull { entry ->
                val o = (ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset)?.offset
                    ?: return@mapNotNull null
                val title = entry.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                title to o
            }.sortedBy { it.second }
        }
        if (starts.isEmpty()) return null
        var idx = starts.indexOfLast { it.second <= charOffset }
        if (idx < 0) idx = 0
        return starts.getOrNull(idx)?.first
    }

    /**
     * PDF：当前页所在大纲标题；无大纲或无法解析时返回 null。
     */
    fun forPdf(toc: List<TocEntry>, pageIndex: Int?): String? {
        if (toc.isEmpty() || pageIndex == null || pageIndex < 0) return null
        val starts = toc.mapNotNull { entry ->
            val page = (ReaderPosition.parse(entry.position) as? ReaderPosition.PageIndex)?.page
                ?: return@mapNotNull null
            val title = entry.title.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            title to page
        }.sortedBy { it.second }
        if (starts.isEmpty()) return null
        var idx = starts.indexOfLast { it.second <= pageIndex }
        if (idx < 0) idx = 0
        return starts.getOrNull(idx)?.first
    }

    /**
     * 状态条左侧文案：
     * - 有章节 + 进度 →「章节 · xx%」
     * - 仅进度 →「进度 xx%」
     * - 都无 → 品牌名
     */
    fun buildLeftLabel(progressPercent: Float?, chapterTitle: String?): String {
        val title = chapterTitle?.trim()?.takeIf { it.isNotEmpty() }
        val progress = progressPercent?.let {
            "${(it.coerceIn(0f, 1f) * 100).toInt()}%"
        }
        return when {
            title != null && progress != null -> "$title · $progress"
            title != null -> title
            progress != null -> "进度 $progress"
            else -> "DeepRead"
        }
    }
}
