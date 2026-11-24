package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import java.io.File
import java.nio.charset.Charset

/**
 * TXT 目录解析规则：
 * - **卷**：识别「第X卷…」「卷X…」「Volume X…」，作为上级目录（level 0），不算章节
 * - **章**：候选标记为「第X章…」「第X回…」「第X节…」「Chapter X…」
 * - **主章节多数决**：章 / 回 / 节哪种标记在全书出现最多，只有它算章节；
 *   其余标记视为正文偶发提及，不进目录（出现次数都 <2 时不过滤，全部保留）
 * - 不识别：纯数字序号行、无标识短标题等
 */
object TxtParser {

    private const val CHARSET_PROBE_BYTES = 256L * 1024

    private val cnNum = "[零〇一二三四五六七八九十百千万两0-9０-９]+"

    /** 中文章节类标题：第X<单位> [可选标题] */
    private fun cnHeading(unit: String) = Regex(
        "^第" + cnNum + unit + "([ \\t\\u3000:：\\-—–].{0,80})?$"
    )

    private val chapterCn = cnHeading("章")
    private val huiCn = cnHeading("回")
    private val jieCn = cnHeading("节")

    /**
     * 卷：第X卷 [可选标题] 或 卷X [可选标题]
     */
    private val volumeCn = Regex(
        "^第" + cnNum + "卷([ \\t\\u3000:：\\-—–].{0,80})?$"
    )
    private val volumeCnAlt = Regex(
        "^卷" + cnNum + "([ \\t\\u3000:：\\-—–].{0,80})?$"
    )

    /** Chapter 1 / Chapter I / CHAPTER 12: Title / Chapter 1 The Start */
    private val chapterEn = Regex(
        "^Chapter\\s+(\\d+|[IVXLCDM]+)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** Volume 1 / Vol. 2 / VOLUME III */
    private val volumeEn = Regex(
        "^(Volume|Vol\\.?)\\s+(\\d+|[IVXLCDM]+)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** 章级候选的标记类型，用于全书多数决筛选 */
    private enum class ChapterMarker { ZHANG, HUI, JIE, EN }

    private data class Heading(
        val kind: TocKind,
        val title: String,
        val offset: Int,
        /** null 表示卷级标题 */
        val marker: ChapterMarker?
    )

    fun readText(file: File): String {
        // 只用文件头部探测编码，避免整本 byte[] 与 String 同时驻留
        val prefixLen = minOf(file.length(), CHARSET_PROBE_BYTES).toInt().coerceAtLeast(0)
        val prefix = file.inputStream().use { input ->
            val buf = ByteArray(prefixLen)
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else if (n == buf.size) buf else buf.copyOf(n)
        }
        val charset = detectCharset(prefix)
        return file.inputStream().buffered().reader(charset).use { it.readText() }
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Charsets.UTF_16LE
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Charsets.UTF_16BE
            }
        }
        val utf8 = Charsets.UTF_8
        val asUtf8 = String(bytes, utf8)
        val replacementCount = asUtf8.count { it == '\uFFFD' }
        if (replacementCount > bytes.size / 50 && bytes.isNotEmpty()) {
            return runCatching { Charset.forName("GBK") }.getOrDefault(utf8)
        }
        return utf8
    }

    /**
     * @param maxEntries 千章长篇预留足够上限
     */
    fun buildToc(text: String, maxEntries: Int = 20_000): List<TocEntry> {
        if (text.isEmpty()) return emptyList()

        val headings = ArrayList<Heading>(256)
        var offset = 0
        // 避免 split 整本产生巨量中间列表：按行扫描
        var i = 0
        val n = text.length
        while (i <= n && headings.size < maxEntries) {
            val nl = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, nl)
            val trimmed = line.trim()
                .trim('\uFEFF', '\u200B')

            if (trimmed.isNotEmpty()) {
                classifyHeadingFull(trimmed)?.let { (kind, marker) ->
                    headings += Heading(
                        kind = kind,
                        title = trimmed.take(80),
                        offset = offset,
                        marker = marker
                    )
                }
            }

            offset = if (nl < n) nl + 1 else n
            if (nl >= n) break
            i = nl + 1
        }

        if (headings.isEmpty()) return emptyList()

        // 主章节多数决：章/回/节出现最多的标记才算章节（≥2 次才启用筛选，
        // 否则可能是误识别，全部保留交给后面的层级逻辑）
        val dominant = headings.mapNotNull { it.marker }
            .filter { it != ChapterMarker.EN }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.takeIf { it.value >= 2 }
            ?.key

        val raw = headings.filter { h ->
            h.kind == TocKind.VOLUME ||
                h.marker == ChapterMarker.EN ||
                dominant == null ||
                h.marker == dominant
        }
        if (raw.isEmpty()) return emptyList()

        // 若全书无卷，章统一 level=0；有卷则卷=0、章=1（卷仅作上级目录，不算章）
        val hasVolume = raw.any { it.kind == TocKind.VOLUME }
        return raw.map { h ->
            val level = when {
                !hasVolume -> 0
                h.kind == TocKind.VOLUME -> 0
                else -> 1
            }
            TocEntry(
                title = h.title,
                position = ReaderPosition.CharOffset(h.offset).serialize(),
                level = level,
                kind = h.kind
            )
        }
    }

    /**
     * 单行标题分类（含候选章标记）：供 [buildToc] 多数决使用。
     * @return 类型与章标记（卷时标记为 null）
     */
    private fun classifyHeadingFull(line: String): Pair<TocKind, ChapterMarker?>? {
        // 过长几乎不可能是目录标题
        if (line.length > 100) return null

        if (volumeCn.matches(line) || volumeCnAlt.matches(line) || volumeEn.matches(line)) {
            return TocKind.VOLUME to null
        }
        if (chapterCn.matches(line)) return TocKind.CHAPTER to ChapterMarker.ZHANG
        if (huiCn.matches(line)) return TocKind.CHAPTER to ChapterMarker.HUI
        if (jieCn.matches(line)) return TocKind.CHAPTER to ChapterMarker.JIE
        if (chapterEn.matches(line)) return TocKind.CHAPTER to ChapterMarker.EN
        return null
    }

    /**
     * 判定单行是否为合法卷/章候选标题。严格匹配，避免误伤正文。
     * 注意：章/回/节在此都算候选章，最终取舍由 [buildToc] 的多数决决定。
     */
    fun classifyHeading(line: String): Pair<TocKind, Int>? {
        val (kind, _) = classifyHeadingFull(line) ?: return null
        return kind to if (kind == TocKind.VOLUME) 0 else 1
    }

    fun titleFromFileName(name: String): String =
        name.substringBeforeLast('.').ifBlank { name }
}
