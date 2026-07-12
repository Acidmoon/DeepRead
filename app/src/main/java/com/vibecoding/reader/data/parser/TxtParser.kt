package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import java.io.File
import java.nio.charset.Charset

/**
 * TXT 目录解析规则（严格）：
 * - **章**：仅「第X章…」「Chapter X…」等形式，其它行一律不算章
 * - **卷**：识别「第X卷…」「Volume X…」等，作为卷级目录
 * - 不识别：节/回/部、纯数字序号行、无标识短标题等
 */
object TxtParser {

    private val cnNum = "[零〇一二三四五六七八九十百千万两0-9０-９]+"

    /**
     * 章：第X章 [可选标题]
     * 允许：第一章、第1章、第１２章、第一章 初入江湖、第一章：开始
     */
    private val chapterCn = Regex(
        "^第" + cnNum + "章([ \\t\\u3000:：\\-—–].{0,80})?$"
    )

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

    fun readText(file: File): String {
        val bytes = file.readBytes()
        val charset = detectCharset(bytes)
        return String(bytes, charset)
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

        val raw = ArrayList<TocEntry>(256)
        var offset = 0
        // 避免 split 整本产生巨量中间列表：按行扫描
        var i = 0
        val n = text.length
        while (i <= n && raw.size < maxEntries) {
            val nl = text.indexOf('\n', i).let { if (it < 0) n else it }
            val line = text.substring(i, nl)
            val trimmed = line.trim()
                .trim('\uFEFF', '\u200B')

            if (trimmed.isNotEmpty()) {
                classifyHeading(trimmed)?.let { (kind, level) ->
                    raw += TocEntry(
                        title = trimmed.take(80),
                        position = ReaderPosition.CharOffset(offset).serialize(),
                        level = level,
                        kind = kind
                    )
                }
            }

            offset = if (nl < n) nl + 1 else n
            if (nl >= n) break
            i = nl + 1
        }

        if (raw.isEmpty()) return emptyList()

        // 若全书无卷，章统一 level=0；有卷则卷=0、章=1
        val hasVolume = raw.any { it.kind == TocKind.VOLUME }
        return if (hasVolume) {
            raw.map { entry ->
                when (entry.kind) {
                    TocKind.VOLUME -> entry.copy(level = 0)
                    TocKind.CHAPTER -> entry.copy(level = 1)
                }
            }
        } else {
            raw.map { it.copy(level = 0) }
        }
    }

    /**
     * 判定是否为合法卷/章标题。严格匹配，避免误伤正文。
     */
    fun classifyHeading(line: String): Pair<TocKind, Int>? {
        // 过长几乎不可能是目录标题
        if (line.length > 100) return null

        if (volumeCn.matches(line) || volumeCnAlt.matches(line) || volumeEn.matches(line)) {
            return TocKind.VOLUME to 0
        }
        if (chapterCn.matches(line) || chapterEn.matches(line)) {
            return TocKind.CHAPTER to 1
        }
        return null
    }

    fun titleFromFileName(name: String): String =
        name.substringBeforeLast('.').ifBlank { name }
}
