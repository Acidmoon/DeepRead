package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.TocEntry
import com.vibecoding.reader.domain.model.TocKind
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * TXT 流式索引：编码、总字符数、目录，以及 char→byte 检查点。
 *
 * 检查点至少包含全书起点、每个标题行起点，并按 [CHECKPOINT_CHARS] 稀疏采样，
 * 以便在超长章或无目录时也能按字符偏移定位，而不把字节数直接当成字符数。
 */
data class TxtIndex(
    val version: Int,
    val charsetName: String,
    val fileLength: Long,
    val fileLastModified: Long,
    val headHash: String,
    val tailHash: String,
    val totalCharLength: Int,
    val checkpoints: List<TxtCheckpoint>,
    val headings: List<TxtHeading>
) {
    val charset: Charset
        get() = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)

    fun tocEntries(): List<TocEntry> = headings.map { it.toTocEntry() }

    fun matches(stamp: TxtFileStamp): Boolean =
        version == TxtIndexStore.VERSION &&
            fileLength == stamp.length &&
            fileLastModified == stamp.lastModified &&
            headHash == stamp.headHash &&
            tailHash == stamp.tailHash &&
            totalCharLength >= 0

    fun checkpointAtOrBefore(charOffset: Int): TxtCheckpoint {
        val target = charOffset.coerceAtLeast(0)
        val list = checkpoints
        if (list.isEmpty()) return TxtCheckpoint(0, 0L)
        var lo = 0
        var hi = list.lastIndex
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].charOffset <= target) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return list[best]
    }
}

data class TxtCheckpoint(
    val charOffset: Int,
    val byteOffset: Long
)

data class TxtHeading(
    val title: String,
    val charOffset: Int,
    val byteOffset: Long,
    val level: Int,
    val kind: TocKind
) {
    fun toTocEntry(): TocEntry = TocEntry(
        title = title,
        position = com.vibecoding.reader.domain.model.ReaderPosition.CharOffset(charOffset).serialize(),
        level = level,
        kind = kind
    )
}

data class TxtFileStamp(
    val length: Long,
    val lastModified: Long,
    val headHash: String,
    val tailHash: String
) {
    companion object {
        private const val HASH_BYTES = 8 * 1024

        fun of(file: File): TxtFileStamp {
            val length = file.length()
            val head = hashRange(file, 0L, minOf(length, HASH_BYTES.toLong()))
            val tail = if (length <= HASH_BYTES) {
                head
            } else {
                hashRange(file, length - HASH_BYTES, HASH_BYTES.toLong())
            }
            return TxtFileStamp(
                length = length,
                lastModified = file.lastModified(),
                headHash = head,
                tailHash = tail
            )
        }

        private fun hashRange(file: File, start: Long, count: Long): String {
            if (count <= 0L) return "empty"
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                if (start > 0L) {
                    var skipped = 0L
                    while (skipped < start) {
                        val n = input.skip(start - skipped)
                        if (n <= 0L) break
                        skipped += n
                    }
                }
                val buf = ByteArray(minOf(count, 4096L).toInt())
                var remaining = count
                while (remaining > 0L) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    remaining -= n
                }
            }
            return digest.digest().joinToString("") { b ->
                val v = b.toInt() and 0xFF
                val hex = "0123456789abcdef"
                "${hex[v shr 4]}${hex[v and 0x0F]}"
            }
        }
    }
}

object TxtIndexStore {
    const val FILE_NAME = "txt_index"
    const val VERSION = 1

    /** 每隔这么多规范化字符记录一个 char→byte 检查点（另加章起点）。 */
    const val CHECKPOINT_CHARS: Int = 16_384

    fun indexFile(bookDir: File): File = File(bookDir, FILE_NAME)

    fun load(bookDir: File): TxtIndex? {
        val file = indexFile(bookDir)
        if (!file.exists()) return null
        return runCatching { fromJson(file.readText()) }.getOrNull()
    }

    fun save(bookDir: File, index: TxtIndex) {
        bookDir.mkdirs()
        indexFile(bookDir).writeText(toJson(index))
    }

    /**
     * 若 sidecar 仍匹配当前文件则复用，否则流式重建并写回 [bookDir]。
     * [bookDir] 为空时只重建，不落盘。
     */
    fun loadOrBuild(file: File, bookDir: File?): TxtIndex {
        val stamp = TxtFileStamp.of(file)
        if (bookDir != null) {
            val existing = load(bookDir)
            if (existing != null && existing.matches(stamp)) {
                return existing
            }
        }
        val index = TxtIndexer.build(file, stamp)
        if (bookDir != null) {
            save(bookDir, index)
        }
        return index
    }

    /**
     * 行式 sidecar，避免依赖 org.json（JVM 单测里 Android stub 不可用）。
     * 标题内的换行和反斜杠已转义。
     */
    fun toJson(index: TxtIndex): String {
        val out = StringBuilder()
        fun line(text: String) {
            out.append(text).append('\n')
        }
        line("v=" + index.version)
        line("charset=" + index.charsetName)
        line("fileLength=" + index.fileLength)
        line("fileLastModified=" + index.fileLastModified)
        line("headHash=" + index.headHash)
        line("tailHash=" + index.tailHash)
        line("totalCharLength=" + index.totalCharLength)
        index.checkpoints.forEach { cp ->
            line("cp=" + cp.charOffset + "," + cp.byteOffset)
        }
        index.headings.forEach { h ->
            line(
                "hd=" + h.kind.name + "\t" + h.level + "\t" +
                    h.charOffset + "\t" + h.byteOffset + "\t" + escape(h.title)
            )
        }
        return out.toString()
    }

    fun fromJson(raw: String): TxtIndex {
        var version = 0
        var charsetName = "UTF-8"
        var fileLength = 0L
        var fileLastModified = 0L
        var headHash = ""
        var tailHash = ""
        var totalCharLength = 0
        val checkpoints = ArrayList<TxtCheckpoint>()
        val headings = ArrayList<TxtHeading>()
        for (rawLine in raw.split('\n')) {
            if (rawLine.isEmpty()) continue
            val eq = rawLine.indexOf('=')
            if (eq <= 0) continue
            val key = rawLine.substring(0, eq)
            val value = rawLine.substring(eq + 1)
            when (key) {
                "v", "version" -> version = value.toIntOrNull() ?: 0
                "charset" -> charsetName = value
                "fileLength" -> fileLength = value.toLongOrNull() ?: 0L
                "fileLastModified" -> fileLastModified = value.toLongOrNull() ?: 0L
                "headHash" -> headHash = value
                "tailHash" -> tailHash = value
                "totalCharLength" -> totalCharLength = value.toIntOrNull() ?: 0
                "cp" -> {
                    val parts = value.split(',')
                    if (parts.size >= 2) {
                        val charOff = parts[0].toIntOrNull() ?: continue
                        val byteOff = parts[1].toLongOrNull() ?: continue
                        checkpoints += TxtCheckpoint(charOff, byteOff)
                    }
                }
                "hd" -> {
                    val parts = value.split('\t', limit = 5)
                    if (parts.size >= 5) {
                        val kind = runCatching { TocKind.valueOf(parts[0]) }
                            .getOrDefault(TocKind.CHAPTER)
                        headings += TxtHeading(
                            title = unescape(parts[4]),
                            charOffset = parts[2].toIntOrNull() ?: 0,
                            byteOffset = parts[3].toLongOrNull() ?: 0L,
                            level = parts[1].toIntOrNull() ?: 0,
                            kind = kind
                        )
                    }
                }
            }
        }
        return TxtIndex(
            version = version,
            charsetName = charsetName,
            fileLength = fileLength,
            fileLastModified = fileLastModified,
            headHash = headHash,
            tailHash = tailHash,
            totalCharLength = totalCharLength,
            checkpoints = checkpoints,
            headings = headings
        )
    }

    private fun escape(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    else -> {
                        out.append(ch)
                        i++
                        continue
                    }
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
