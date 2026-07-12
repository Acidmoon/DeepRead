package com.vibecoding.reader.domain.reader

/**
 * 自动翻页参数约束与换算。
 *
 * - **分页模式**：每隔 [intervalSec] 自动下一页
 * - **竖滑模式**：每秒滚动 [linesPerSec] 行（按字号×行距估算行高）
 */
object AutoPageTurn {
    const val DEFAULT_INTERVAL_SEC = 8f
    const val MIN_INTERVAL_SEC = 2f
    const val MAX_INTERVAL_SEC = 60f

    const val DEFAULT_LINES_PER_SEC = 1.2f
    const val MIN_LINES_PER_SEC = 0.3f
    const val MAX_LINES_PER_SEC = 6f

    fun clampIntervalSec(value: Float): Float =
        value.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)

    fun clampLinesPerSec(value: Float): Float =
        value.coerceIn(MIN_LINES_PER_SEC, MAX_LINES_PER_SEC)

    /** 分页：间隔毫秒 */
    fun intervalMs(intervalSec: Float): Long =
        (clampIntervalSec(intervalSec) * 1000f).toLong().coerceAtLeast(500L)

    /**
     * 竖滑：每帧应滚动的像素。
     * @param fontSizeSp 字号
     * @param lineSpacing 行距倍数
     * @param density 屏幕 density
     * @param linesPerSec 每秒行数
     * @param frameMs 帧间隔
     */
    fun scrollPxPerFrame(
        fontSizeSp: Float,
        lineSpacing: Float,
        density: Float,
        linesPerSec: Float,
        frameMs: Long = 16L
    ): Float {
        val lineHeightPx = fontSizeSp * lineSpacing * density
        val pxPerSec = lineHeightPx * clampLinesPerSec(linesPerSec)
        return pxPerSec * (frameMs / 1000f)
    }
}
