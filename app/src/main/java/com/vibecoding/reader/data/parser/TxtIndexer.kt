package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.reader.TextSource
import com.vibecoding.reader.domain.reader.WindowedText

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 流式扫描 TXT：一边解码一边统一换行、收集目录候选和 char→byte 检查点。
 * 扫描过程不驻留完整 byte[]，也不驻留完整 String。
 */
object TxtIndexer {

    private const val CHARSET_PROBE_BYTES = 256L * 1024

    fun detectFileCharset(file: File): Charset {
        val prefixLen = minOf(file.length(), CHARSET_PROBE_BYTES).toInt().coerceAtLeast(0)
        val prefix = file.inputStream().use { input ->
            val buf = ByteArray(prefixLen)
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else if (n == buf.size) buf else buf.copyOf(n)
        }
        return TxtParser.detectCharset(prefix)
    }

    fun build(file: File, stamp: TxtFileStamp = TxtFileStamp.of(file)): TxtIndex {
        val charset = detectFileCharset(file)
        val checkpoints = ArrayList<TxtCheckpoint>(64)
        val rawHeadings = ArrayList<TxtParser.HeadingCandidate>(256)
        checkpoints += TxtCheckpoint(0, 0L)

        val line = StringBuilder()
        var lineStartChar = 0
        var lineStartByte = 0L
        var nextCheckpoint = TxtIndexStore.CHECKPOINT_CHARS

        fun flushLine() {
            val trimmed = line.toString().trim()
                .trim('\uFEFF', '\u200B')
            if (trimmed.isNotEmpty()) {
                TxtParser.classifyHeadingFull(trimmed)?.let { (kind, marker) ->
                    rawHeadings += TxtParser.HeadingCandidate(
                        kind = kind,
                        title = trimmed.take(80),
                        charOffset = lineStartChar,
                        byteOffset = lineStartByte,
                        marker = marker
                    )
                    checkpoints += TxtCheckpoint(lineStartChar, lineStartByte)
                }
            }
            line.setLength(0)
        }

        var total = 0
        openNormalizedReader(file, charset, 0L).use { reader ->
            while (true) {
                val code = reader.readNormalized()
                if (code < 0) break
                val ch = code.toChar()
                val charOffset = reader.charOffset
                val byteOffset = reader.charByteOffset
                if (charOffset == nextCheckpoint) {
                    checkpoints += TxtCheckpoint(charOffset, byteOffset)
                    nextCheckpoint += TxtIndexStore.CHECKPOINT_CHARS
                }
                if (ch == '\n') {
                    flushLine()
                    lineStartChar = charOffset + 1
                    lineStartByte = reader.nextByteOffset
                } else {
                    if (line.isEmpty()) {
                        lineStartChar = charOffset
                        lineStartByte = byteOffset
                    }
                    line.append(ch)
                }
                total = charOffset + 1
            }
        }
        if (line.isNotEmpty()) {
            flushLine()
        }

        val uniqueCheckpoints = uniqueCheckpoints(checkpoints, total)
        val headings = TxtParser.finalizeHeadings(rawHeadings)

        return TxtIndex(
            version = TxtIndexStore.VERSION,
            charsetName = charset.name(),
            fileLength = stamp.length,
            fileLastModified = stamp.lastModified,
            headHash = stamp.headHash,
            tailHash = stamp.tailHash,
            totalCharLength = total,
            checkpoints = uniqueCheckpoints,
            headings = headings
        )
    }

    /**
     * 从 [byteOffset] 起解码，跳过 [skipChars] 个规范化字符，再读取最多 [count] 个。
     */
    fun readNormalizedRange(
        file: File,
        charset: Charset,
        byteOffset: Long,
        skipChars: Int,
        count: Int
    ): String {
        if (count <= 0) return ""
        openNormalizedReader(file, charset, byteOffset).use { reader ->
            if (skipChars > 0) {
                var skipped = 0
                while (skipped < skipChars) {
                    val code = reader.readNormalized()
                    if (code < 0) return ""
                    skipped++
                }
            }
            val out = StringBuilder(count)
            while (out.length < count) {
                val code = reader.readNormalized()
                if (code < 0) break
                out.append(code.toChar())
            }
            return out.toString()
        }
    }

    /** 只解码前 [maxChars] 个规范化字符，供封面摘录使用。 */
    fun readPrefix(file: File, maxChars: Int, charset: Charset = detectFileCharset(file)): String {
        if (maxChars <= 0 || !file.exists() || file.length() <= 0L) return ""
        return readNormalizedRange(file, charset, 0L, 0, maxChars)
    }

    private fun openNormalizedReader(
        file: File,
        charset: Charset,
        byteOffset: Long
    ): NormalizedTextReader {
        val input = FileInputStream(file)
        if (byteOffset > 0L) {
            input.channel.position(byteOffset)
        }
        return NormalizedTextReader(input, charset, byteOffset)
    }

    private fun uniqueCheckpoints(
        raw: List<TxtCheckpoint>,
        totalChars: Int
    ): List<TxtCheckpoint> {
        val seen = LinkedHashMap<Int, TxtCheckpoint>()
        for (cp in raw) {
            if (cp.charOffset < 0 || (totalChars > 0 && cp.charOffset > totalChars)) continue
            seen.putIfAbsent(cp.charOffset, cp)
        }
        seen.putIfAbsent(0, TxtCheckpoint(0, 0L))
        return seen.values.sortedBy { it.charOffset }
    }
}

/**
 * 按检查点定位并解码指定字符窗口。不持有全书文本。
 */
class IndexedTxtTextSource(
    private val file: File,
    val index: TxtIndex
) : TextSource {
    override val totalLength: Int get() = index.totalCharLength
    override val toc: List<com.vibecoding.reader.domain.model.TocEntry>
        get() = index.tocEntries()
    override val fullText: String? get() = null

    override fun readRange(startChar: Int, endChar: Int): WindowedText {
        val s = startChar.coerceIn(0, index.totalCharLength)
        val e = endChar.coerceIn(s, index.totalCharLength)
        if (s >= e) {
            return WindowedText("", s, e, index.totalCharLength)
        }
        val cp = index.checkpointAtOrBefore(s)
        val skip = (s - cp.charOffset).coerceAtLeast(0)
        val text = TxtIndexer.readNormalizedRange(
            file = file,
            charset = index.charset,
            byteOffset = cp.byteOffset,
            skipChars = skip,
            count = e - s
        )
        return WindowedText(text, s, s + text.length, index.totalCharLength)
    }
}

/**
 * 从字节流逐字解码，并把 `\r\n` / `\r` 规范为 `\n`，同时报告每个规范化字符
 * 在文件中的起始字节偏移。
 */
internal class NormalizedTextReader(
    input: InputStream,
    charset: Charset,
    startByteOffset: Long
) : Closeable {
    private val rawReader = TxtCharsetReader(input, charset, startByteOffset)
    private var pendingCR = false
    private var pendingCRByte = 0L
    private var heldCode = -1
    private var heldByte = 0L
    private var heldNext = 0L
    private var finished = false

    /** 最近一次 [readNormalized] 得到的字符，在规范化文本中的偏移。 */
    var charOffset: Int = -1
        private set

    /** 该规范化字符对应的文件字节起点（`\\r\\n` 取 `\\r` 的位置）。 */
    var charByteOffset: Long = startByteOffset
        private set

    /** 读完当前字符后，下一个原始字节的位置。 */
    var nextByteOffset: Long = startByteOffset
        private set

    /**
     * @return 规范化字符的代码，或 -1 表示结束
     */
    fun readNormalized(): Int {
        while (true) {
            val code: Int
            val byteOffset: Long
            val nextByte: Long
            if (heldCode >= 0) {
                code = heldCode
                byteOffset = heldByte
                nextByte = heldNext
                heldCode = -1
            } else if (finished) {
                return -1
            } else {
                val raw = rawReader.readChar()
                if (raw < 0) {
                    finished = true
                    if (pendingCR) {
                        pendingCR = false
                        return emit('\n', pendingCRByte, rawReader.nextByteOffset)
                    }
                    return -1
                }
                code = raw
                byteOffset = rawReader.charByteOffset
                nextByte = rawReader.nextByteOffset
            }

            if (pendingCR) {
                pendingCR = false
                if (code == '\n'.code) {
                    return emit('\n', pendingCRByte, nextByte)
                }
                heldCode = code
                heldByte = byteOffset
                heldNext = nextByte
                return emit('\n', pendingCRByte, byteOffset)
            }
            if (code == '\r'.code) {
                pendingCR = true
                pendingCRByte = byteOffset
                continue
            }
            return emit(code.toChar(), byteOffset, nextByte)
        }
    }

    private fun emit(ch: Char, byteOffset: Long, nextByte: Long): Int {
        charOffset++
        charByteOffset = byteOffset
        nextByteOffset = nextByte
        return ch.code
    }

    override fun close() {
        rawReader.close()
    }
}

/**
 * 带字节计数的 Charset 解码器。一次尽量产出一个 UTF-16 code unit；
 * 遇到补充平面字符时把低代理项缓存起来，两者共享同一字节起点。
 */
internal class TxtCharsetReader(
    private val input: InputStream,
    charset: Charset,
    startByteOffset: Long
) : Closeable {
    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private val raw = ByteArray(64 * 1024)
    private var rawSize = 0
    private var rawPos = 0
    private var filePosAtRaw0 = startByteOffset
    private var eof = false
    private var flushed = false

    private var pendingLow: Int = -1
    private var pendingLowByte: Long = 0L
    private var pendingLowNext: Long = 0L

    var charByteOffset: Long = startByteOffset
        private set
    var nextByteOffset: Long = startByteOffset
        private set

    fun readChar(): Int {
        if (pendingLow >= 0) {
            val code = pendingLow
            pendingLow = -1
            charByteOffset = pendingLowByte
            nextByteOffset = pendingLowNext
            return code
        }
        if (flushed) return -1
        val startByte = currentBytePosition()
        val out = CharBuffer.allocate(2)
        while (out.position() == 0 && !flushed) {
            if (rawPos >= rawSize && !eof) {
                fill()
            }
            val remaining = rawSize - rawPos
            if (remaining <= 0 && eof) {
                val endBuf = ByteBuffer.allocate(0)
                var produced = decoder.decode(endBuf, out, true).also { r ->
                    if (r.isError) r.throwException()
                }
                if (out.position() == 0) {
                    produced = decoder.flush(out)
                    if (produced.isError) produced.throwException()
                }
                flushed = true
                break
            }
            val inBuf = ByteBuffer.wrap(raw, rawPos, remaining)
            val before = inBuf.position()
            val result = decoder.decode(inBuf, out, false)
            val consumed = inBuf.position() - before
            rawPos += consumed
            if (out.position() > 0) break
            if (result.isError) result.throwException()
            if (result.isOverflow) break
            // underflow：需要更多字节
            fill()
        }
        if (out.position() == 0) return -1
        out.flip()
        val first = out.get()
        charByteOffset = startByte
        nextByteOffset = currentBytePosition()
        if (out.hasRemaining()) {
            pendingLow = out.get().code
            pendingLowByte = startByte
            pendingLowNext = nextByteOffset
        }
        return first.code
    }

    private fun currentBytePosition(): Long = filePosAtRaw0 + rawPos

    private fun fill() {
        if (eof) return
        if (rawPos > 0 && rawPos < rawSize) {
            val leftover = rawSize - rawPos
            System.arraycopy(raw, rawPos, raw, 0, leftover)
            filePosAtRaw0 += rawPos
            rawPos = 0
            rawSize = leftover
        } else if (rawPos >= rawSize) {
            filePosAtRaw0 += rawPos
            rawPos = 0
            rawSize = 0
        }
        val n = input.read(raw, rawSize, raw.size - rawSize)
        if (n <= 0) {
            eof = true
        } else {
            rawSize += n
        }
    }

    override fun close() {
        input.close()
    }
}
