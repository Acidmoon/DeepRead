package com.vibecoding.reader.data.import

import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.reader.BookLoadGuard
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * 导入前的纯逻辑预检：格式、体积、磁盘空间，以及带上限的拷贝。
 */
object ImportPrecheck {
    const val SPACE_MARGIN_BYTES: Long = 2L * 1024 * 1024

    fun isLegacyMsWord(fileName: String, mime: String?): Boolean {
        val lower = fileName.substringAfterLast('/').lowercase()
        if (lower.endsWith(".docx") || lower.endsWith(".docm")) return false
        if (lower.endsWith(".doc") || lower.endsWith(".dot")) return true
        return mime.equals("application/msword", ignoreCase = true)
    }

    fun legacyMsWordMessage(fileName: String): String =
        "不支持旧版 Word（.doc）：$fileName。请先另存为 DOCX 后再导入"

    fun resolveFormat(fileName: String, mime: String?): BookFormat? =
        BookFormat.fromFileName(fileName) ?: BookFormat.fromMime(mime)

    fun unsupportedFormatMessage(fileName: String): String =
        "不支持的格式：$fileName（支持 TXT / MD / EPUB / PDF / DOCX）"

    /** [bytes] 未知或 <=0 时先不拒绝，交给带上限的拷贝。 */
    fun sizeError(bytes: Long, format: BookFormat): String? {
        if (bytes <= 0L) return null
        val limit = limitFor(format)
        if (bytes > limit) return BookLoadGuard.hardLimitMessage(bytes, limit)
        return null
    }

    fun limitFor(format: BookFormat): Long =
        if (format == BookFormat.PDF) BookLoadGuard.PDF_HARD_LIMIT_BYTES
        else BookLoadGuard.HARD_LIMIT_BYTES

    fun spaceError(
        neededBytes: Long,
        usableSpace: Long,
        margin: Long = SPACE_MARGIN_BYTES
    ): String? {
        if (neededBytes <= 0L || usableSpace < 0L) return null
        val required = neededBytes + margin
        if (usableSpace < required) {
            return "存储空间不足，需要约 ${BookLoadGuard.formatSize(required)}，" +
                "可用 ${BookLoadGuard.formatSize(usableSpace)}"
        }
        return null
    }

    /**
     * 按 [sourceUri] 查找已在书架中的同一份来源。空白 URI 不匹配。
     */
    fun existingBySourceUri(sourceUri: String, candidates: List<Pair<String, String>>): String? {
        if (sourceUri.isBlank()) return null
        return candidates.firstOrNull { it.first == sourceUri }?.second
    }

    /**
     * @return 实际拷贝字节数
     * @throws IOException 超过 [maxBytes]
     */
    fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val next = total + n
            if (next > maxBytes) {
                throw IOException(
                    "文件过大，超过 ${BookLoadGuard.formatSize(maxBytes)} 限制"
                )
            }
            output.write(buf, 0, n)
            total = next
        }
        return total
    }
}
