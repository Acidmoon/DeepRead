package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.ReaderPosition

/**
 * 阅读进度换算（纯函数）：拖动比例 ↔ 全书位置。
 */
object ReadingProgress {

    /** 0~1 进度 → 字符偏移（电子书） */
    fun charOffsetFromProgress(progress: Float, textLength: Int): Int {
        if (textLength <= 0) return 0
        val p = progress.coerceIn(0f, 1f)
        return (p * textLength).toInt().coerceIn(0, (textLength - 1).coerceAtLeast(0))
    }

    /** 字符偏移 → 0~1 进度 */
    fun progressFromCharOffset(offset: Int, textLength: Int): Float {
        if (textLength <= 0) return 0f
        return (offset.toFloat() / textLength.toFloat()).coerceIn(0f, 1f)
    }

    fun positionFromProgress(progress: Float, textLength: Int): String =
        ReaderPosition.CharOffset(charOffsetFromProgress(progress, textLength)).serialize()

    /** PDF：0~1 → 页索引 */
    fun pageFromProgress(progress: Float, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val p = progress.coerceIn(0f, 1f)
        // 用进度对应「读到第几页」：0→0, 1→last
        return ((p * pageCount).toInt().coerceAtMost(pageCount - 1)).coerceAtLeast(0)
    }

    fun progressFromPage(page: Int, pageCount: Int): Float {
        if (pageCount <= 0) return 0f
        return ((page + 1).toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
    }

    fun pagePositionFromProgress(progress: Float, pageCount: Int): String =
        ReaderPosition.PageIndex(pageFromProgress(progress, pageCount)).serialize()
}
