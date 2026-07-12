package com.vibecoding.reader.ui.reader.text

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import kotlin.math.max
import kotlin.math.min

/**
 * 章节区间（绝对字符偏移，end 为 exclusive）。
 */
data class ChapterRange(
    val index: Int,
    val title: String,
    val start: Int,
    val end: Int
)

/**
 * 页在全书中的绝对偏移区间（不持有正文拷贝，避免大书 OOM）。
 */
data class TextPageBreak(
    val startOffset: Int,
    val endOffset: Int
)

/**
 * 高性能文本分页：
 * - 禁止对整本书构建一次 StaticLayout（千章小说会卡死/OOM）
 * - 按章分页；每页用「估计长度 + 局部 StaticLayout」切分
 * - 只存偏移，不存每页 content 字符串
 */
object TextPaginator {

    fun createPaint(fontSizePx: Float, textColor: Int = 0xFF000000.toInt()): TextPaint {
        return TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            typeface = Typeface.SERIF
            color = textColor
            isAntiAlias = true
        }
    }

    fun buildLayout(
        text: CharSequence,
        start: Int,
        end: Int,
        widthPx: Int,
        paint: TextPaint,
        lineSpacingMultiplier: Float
    ): StaticLayout {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val w = widthPx.coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, s, e, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .setEllipsize(null)
            .build()
    }

    /**
     * 由 TOC + 全文长度构建章节区间。无目录时整本为一章，
     * 或超长时按固定块切分，保证单次分页体量可控。
     */
    fun buildChapters(
        textLength: Int,
        toc: List<TocEntry>,
        maxChapterChars: Int = 80_000
    ): List<ChapterRange> {
        if (textLength <= 0) {
            return listOf(ChapterRange(0, "正文", 0, 0))
        }

        val starts = mutableListOf<Pair<Int, String>>()
        for (entry in toc) {
            val pos = ReaderPosition.parse(entry.position) as? ReaderPosition.CharOffset
                ?: continue
            val off = pos.offset.coerceIn(0, textLength)
            if (starts.isEmpty() || starts.last().first != off) {
                starts += off to entry.title
            }
        }
        starts.sortBy { it.first }
        if (starts.isEmpty() || starts.first().first > 0) {
            starts.add(0, 0 to "正文")
        }
        // 去重排序后的起点
        val unique = starts.distinctBy { it.first }.sortedBy { it.first }

        val raw = mutableListOf<ChapterRange>()
        for (i in unique.indices) {
            val start = unique[i].first
            val end = if (i + 1 < unique.size) unique[i + 1].first else textLength
            if (end > start) {
                raw += ChapterRange(raw.size, unique[i].second, start, end)
            }
        }
        if (raw.isEmpty()) {
            raw += ChapterRange(0, "正文", 0, textLength)
        }

        // 超长章切块，避免单章仍过大
        val result = mutableListOf<ChapterRange>()
        for (ch in raw) {
            if (ch.end - ch.start <= maxChapterChars) {
                result += ch.copy(index = result.size)
            } else {
                var s = ch.start
                var part = 1
                while (s < ch.end) {
                    val e = min(ch.end, s + maxChapterChars)
                    result += ChapterRange(
                        index = result.size,
                        title = if (part == 1) ch.title else "${ch.title} ($part)",
                        start = s,
                        end = e
                    )
                    s = e
                    part++
                }
            }
        }
        return result
    }

    fun chapterIndexForOffset(chapters: List<ChapterRange>, offset: Int): Int {
        if (chapters.isEmpty()) return 0
        val o = offset.coerceAtLeast(0)
        val idx = chapters.indexOfLast { it.start <= o }
        return max(0, idx)
    }

    /**
     * 计算从 [startOffset] 起、填满 [heightPx] 的一页结束偏移（exclusive）。
     * 仅对局部文本做 StaticLayout，复杂度与单页相关。
     */
    fun endOffsetForPage(
        text: CharSequence,
        startOffset: Int,
        endLimit: Int,
        widthPx: Int,
        heightPx: Int,
        paint: TextPaint,
        lineSpacingMultiplier: Float
    ): Int {
        val start = startOffset.coerceIn(0, text.length)
        val limit = endLimit.coerceIn(start, text.length)
        if (start >= limit || widthPx <= 0 || heightPx <= 0) return start

        // 中文约 1em 宽；多估一些再裁切
        val avgChar = max(paint.textSize * 0.9f, 1f)
        val lineHeight = max(paint.fontSpacing * lineSpacingMultiplier, paint.textSize)
        val lines = max(1, (heightPx / lineHeight).toInt())
        val charsPerLine = max(1, (widthPx / avgChar).toInt())
        var probeLen = (lines * charsPerLine * 1.35f).toInt().coerceAtLeast(64)
        var end = min(limit, start + probeLen)

        var layout = buildLayout(text, start, end, widthPx, paint, lineSpacingMultiplier)
        // 若高度不足且还有字，扩展探测范围
        var guard = 0
        while (layout.height <= heightPx && end < limit && guard < 8) {
            probeLen = (probeLen * 1.6f).toInt()
            end = min(limit, start + probeLen)
            layout = buildLayout(text, start, end, widthPx, paint, lineSpacingMultiplier)
            guard++
        }

        // 找到放得下的最后一行
        var lastLine = -1
        for (i in 0 until layout.lineCount) {
            if (layout.getLineBottom(i) <= heightPx) {
                lastLine = i
            } else {
                break
            }
        }
        if (lastLine < 0) {
            // 单行也放不下：至少吃掉一行，避免死循环
            lastLine = 0
        }
        var pageEnd = layout.getLineEnd(lastLine)
        // getLineEnd 是相对整段 CharSequence 的绝对 offset（obtain 使用全局 start/end 时）
        pageEnd = pageEnd.coerceIn(start + 1, limit)
        // 避免卡在同一位置
        if (pageEnd <= start) {
            pageEnd = min(limit, start + 1)
        }
        return pageEnd
    }

    /**
     * 对章节 [chapterStart, chapterEnd) 做完整分页，只返回偏移列表。
     */
    fun paginateChapter(
        text: CharSequence,
        chapterStart: Int,
        chapterEnd: Int,
        widthPx: Int,
        heightPx: Int,
        paint: TextPaint,
        lineSpacingMultiplier: Float
    ): List<TextPageBreak> {
        val start0 = chapterStart.coerceIn(0, text.length)
        val end0 = chapterEnd.coerceIn(start0, text.length)
        if (start0 >= end0) {
            return listOf(TextPageBreak(start0, start0))
        }
        if (widthPx <= 0 || heightPx <= 0) {
            return listOf(TextPageBreak(start0, end0))
        }

        val pages = ArrayList<TextPageBreak>(64)
        var cursor = start0
        var guard = 0
        val maxPages = 50_000
        while (cursor < end0 && guard < maxPages) {
            val pageEnd = endOffsetForPage(
                text = text,
                startOffset = cursor,
                endLimit = end0,
                widthPx = widthPx,
                heightPx = heightPx,
                paint = paint,
                lineSpacingMultiplier = lineSpacingMultiplier
            )
            val safeEnd = pageEnd.coerceIn(cursor + 1, end0)
            pages += TextPageBreak(cursor, safeEnd)
            cursor = safeEnd
            guard++
        }
        if (pages.isEmpty()) {
            pages += TextPageBreak(start0, end0)
        }
        return pages
    }

    fun pageIndexForOffset(pages: List<TextPageBreak>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        val o = offset.coerceAtLeast(pages.first().startOffset)
        val idx = pages.indexOfLast { it.startOffset <= o }
        return max(0, idx)
    }

    fun progressForOffset(offset: Int, textLength: Int): Float {
        if (textLength <= 0) return 0f
        return min(1f, offset.toFloat() / textLength.toFloat())
    }

    /** 将长文拆成段落块，供上下滚动 LazyColumn 使用（避免单 Composable 持有整章超大 Text）。 */
    fun splitBlocks(
        text: String,
        start: Int,
        end: Int,
        targetBlockChars: Int = 1200
    ): List<TextBlock> {
        val s0 = start.coerceIn(0, text.length)
        val e0 = end.coerceIn(s0, text.length)
        if (s0 >= e0) return emptyList()

        val blocks = ArrayList<TextBlock>()
        var i = s0
        while (i < e0) {
            var j = min(e0, i + targetBlockChars)
            if (j < e0) {
                // 尽量在段落边界切开
                val nl = text.lastIndexOf('\n', j)
                if (nl > i + targetBlockChars / 3) {
                    j = nl + 1
                }
            }
            blocks += TextBlock(
                startOffset = i,
                endOffset = j,
                content = text.substring(i, j)
            )
            i = j
        }
        return blocks
    }
}

data class TextBlock(
    val startOffset: Int,
    val endOffset: Int,
    val content: String
)
