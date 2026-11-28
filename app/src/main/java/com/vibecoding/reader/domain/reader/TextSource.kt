package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.TocEntry

/**
 * 电子书文本源：用全局字符偏移读写，不必把全书解码成一整段 [String]。
 *
 * 字符偏移与现有书签 / 进度协议一致：换行已统一为 `\n`
 *（`\\r\\n` 与单独的 `\\r` 都计为 1 个字符）。
 *
 * [fullText] 仅在文本已在内存中时非空；大 TXT 走窗口读取，不得为了方便
 * 再把全书读回内存。
 */
interface TextSource {
    val totalLength: Int
    val toc: List<TocEntry>

    /** 已驻留内存的全文；窗口化来源必须返回 null。 */
    val fullText: String? get() = null

    /**
     * 读取规范化后的半开区间 [startChar, endChar)。
     * 返回窗口的 [WindowedText.text] 对应全局偏移 [WindowedText.startOffset]。
     */
    fun readRange(startChar: Int, endChar: Int): WindowedText
}

/**
 * 一段已解码的窗口正文。[text] 的下标 0 对应全书 [startOffset]。
 */
data class WindowedText(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val totalLength: Int
) {
    fun contains(globalStart: Int, globalEnd: Int): Boolean {
        if (globalStart < startOffset) return false
        if (globalEnd > endOffset) return false
        if (text.isEmpty()) return globalStart == globalEnd && globalStart == startOffset
        return true
    }

    fun toLocal(global: Int): Int = (global - startOffset).coerceIn(0, text.length)

    fun slice(globalStart: Int, globalEnd: Int): String {
        val s = toLocal(globalStart)
        val e = toLocal(globalEnd).coerceAtLeast(s)
        return text.substring(s, e)
    }
}

/**
 * 小文件或本轮未窗口化的格式（MD / EPUB / DOCX）使用的内存实现。
 */
class InMemoryTextSource(
    private val text: String,
    override val toc: List<TocEntry>
) : TextSource {
    override val totalLength: Int get() = text.length
    override val fullText: String get() = text

    override fun readRange(startChar: Int, endChar: Int): WindowedText {
        val s = startChar.coerceIn(0, text.length)
        val e = endChar.coerceIn(s, text.length)
        return WindowedText(text.substring(s, e), s, e, text.length)
    }
}
