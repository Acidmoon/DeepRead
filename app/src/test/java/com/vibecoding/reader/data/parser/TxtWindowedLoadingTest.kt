package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.model.ReaderPosition
import com.vibecoding.reader.domain.model.TocKind
import com.vibecoding.reader.domain.reader.InMemoryTextSource
import com.vibecoding.reader.domain.search.TextSearch
import com.vibecoding.reader.ui.reader.text.TextPaginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TxtWindowedLoadingTest {

    private fun tempTxt(): File = File.createTempFile("deepread-txt", ".txt").apply {
        deleteOnExit()
    }

    private fun writeBytes(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
    }

    private fun sampleNovel(newline: String = "\n"): String = listOf(
        "序言",
        "这是序言内容。",
        "",
        "第一章 少年",
        "正文一段，含中文「长安」。",
        "",
        "第二章 远行",
        "更多正文，提到长安与江南。",
        "",
        "第三章 归途",
        "结尾。"
    ).joinToString(newline)

    @Test
    fun utf8IndexMatchesReadTextAndToc() {
        val file = tempTxt()
        writeBytes(file, sampleNovel().toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        val index = TxtIndexer.build(file)
        assertEquals(expected.length, index.totalCharLength)
        assertEquals(TxtParser.buildToc(expected).map { it.title to it.position },
            index.tocEntries().map { it.title to it.position })
        val source = IndexedTxtTextSource(file, index)
        assertEquals(expected, source.readRange(0, expected.length).text)
        assertEquals("第一章 少年", source.toc[0].title)
        val ch1 = (ReaderPosition.parse(source.toc[0].position) as ReaderPosition.CharOffset).offset
        val window = source.readRange(ch1, ch1 + 8)
        assertEquals(expected.substring(ch1, ch1 + 8), window.text)
    }

    @Test
    fun utf8BomOffsetsStayCompatibleWithReadText() {
        val file = tempTxt()
        val body = "第一章\n正文"
        writeBytes(file, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            body.toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        assertTrue(expected.startsWith("\uFEFF") || expected.startsWith("第一章"))
        val index = TxtIndexer.build(file)
        assertEquals(expected.length, index.totalCharLength)
        val source = IndexedTxtTextSource(file, index)
        assertEquals(expected, source.readRange(0, expected.length).text)
        val toc = index.tocEntries()
        assertEquals(TxtParser.buildToc(expected).map { it.position }, toc.map { it.position })
    }

    @Test
    fun gbkIndexAndWindowMatchReadText() {
        val gbk = Charset.forName("GBK")
        val file = tempTxt()
        writeBytes(file, sampleNovel().toByteArray(gbk))
        val expected = TxtParser.readText(file)
        val index = TxtIndexer.build(file)
        assertTrue(index.charsetName.contains("GB", ignoreCase = true) ||
            index.charsetName.contains("windows-936", ignoreCase = true))
        assertEquals(expected.length, index.totalCharLength)
        val source = IndexedTxtTextSource(file, index)
        assertEquals(expected, source.readRange(0, expected.length).text)
        val mid = expected.length / 2
        assertEquals(expected.substring(mid, mid + 10), source.readRange(mid, mid + 10).text)
    }

    @Test
    fun crlfNormalizationKeepsCharOffsets() {
        val file = tempTxt()
        writeBytes(file, sampleNovel(newline = "\r\n").toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        assertFalse(expected.contains('\r'))
        val index = TxtIndexer.build(file)
        assertEquals(expected.length, index.totalCharLength)
        val source = IndexedTxtTextSource(file, index)
        assertEquals(expected, source.readRange(0, expected.length).text)
        val toc = index.tocEntries()
        val expectedToc = TxtParser.buildToc(expected)
        assertEquals(expectedToc.size, toc.size)
        expectedToc.zip(toc).forEach { (a, b) ->
            assertEquals(a.position, b.position)
            assertEquals(a.title, b.title)
            assertEquals(a.kind, b.kind)
        }
        // 与仅 LF 文本的书签偏移一致
        val lf = tempTxt()
        writeBytes(lf, sampleNovel("\n").toByteArray(Charsets.UTF_8))
        val lfText = TxtParser.readText(lf)
        assertEquals(lfText, expected)
        assertEquals(TxtParser.buildToc(lfText).map { it.position }, toc.map { it.position })
    }

    @Test
    fun loneCarriageReturnIsNormalizedToNewline() {
        val file = tempTxt()
        writeBytes(file, "第一章\r正文A\r\n第二章\n结尾".toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        val index = TxtIndexer.build(file)
        assertEquals(expected, IndexedTxtTextSource(file, index).readRange(0, expected.length).text)
        assertEquals(2, index.headings.size)
    }

    @Test
    fun noTocStillSplitsByMaxChapterChars() {
        val file = tempTxt()
        val text = "A".repeat(100_000)
        writeBytes(file, text.toByteArray(Charsets.UTF_8))
        val source = TxtDocuments.open(file, bookDir = null, inMemoryMaxBytes = 0)
        assertTrue(source is IndexedTxtTextSource)
        assertEquals(100_000, source.totalLength)
        assertTrue(source.toc.isEmpty())
        val chapters = TextPaginator.buildChapters(source.totalLength, source.toc, maxChapterChars = 80_000)
        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].start)
        assertEquals(80_000, chapters[0].end)
        assertEquals(80_000, chapters[1].start)
        assertEquals(100_000, chapters[1].end)
        assertEquals(text.substring(80_000, 90_000), source.readRange(80_000, 90_000).text)
        // 稀疏检查点应能落到第二段附近，而不必从文件头逐字跳过全部 8 万字才能定位
        val index = (source as IndexedTxtTextSource).index
        val cp = index.checkpointAtOrBefore(80_000)
        assertTrue(cp.charOffset <= 80_000)
        assertTrue(cp.charOffset >= 80_000 - TxtIndexStore.CHECKPOINT_CHARS)
    }

    @Test
    fun indexInvalidatesWhenFileReplaced() {
        val file = tempTxt()
        val dir = File(file.parentFile, "book-${file.name}").apply { mkdirs() }
        dir.deleteOnExit()
        writeBytes(file, "第一章\n旧内容旧内容旧内容".toByteArray(Charsets.UTF_8))
        val first = TxtIndexStore.loadOrBuild(file, dir)
        assertEquals(TxtParser.readText(file).length, first.totalCharLength)

        writeBytes(file, "第一章\n新书内容完全不同而且更长一些！".toByteArray(Charsets.UTF_8))
        val second = TxtIndexStore.loadOrBuild(file, dir)
        val expected = TxtParser.readText(file)
        assertEquals(expected.length, second.totalCharLength)
        assertNotEquals(first.headHash, second.headHash)
        assertEquals(expected, IndexedTxtTextSource(file, second).readRange(0, expected.length).text)
    }

    @Test
    fun windowReadAndJumpAroundChapter() {
        val file = tempTxt()
        writeBytes(file, sampleNovel().toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        val source = TxtDocuments.open(file, inMemoryMaxBytes = 0)
        val toc = source.toc
        assertEquals(3, toc.size)
        val ch2 = (ReaderPosition.parse(toc[1].position) as ReaderPosition.CharOffset).offset
        val ch3 = (ReaderPosition.parse(toc[2].position) as ReaderPosition.CharOffset).offset
        val window = source.readRange(ch2, ch3)
        assertEquals(expected.substring(ch2, ch3), window.text)
        assertEquals(ch2, window.startOffset)
        // 从目录跳到第三章附近
        val jumped = source.readRange(ch3, expected.length)
        assertTrue(jumped.text.startsWith("第三章"))
        assertEquals(expected.substring(ch3), jumped.text)
    }

    @Test
    fun smallFileUsesInMemoryPath() {
        val file = tempTxt()
        writeBytes(file, sampleNovel().toByteArray(Charsets.UTF_8))
        val source = TxtDocuments.open(file)
        assertTrue(source is InMemoryTextSource)
        assertEquals(TxtParser.readText(file), source.fullText)
        assertTrue(file.length() < TxtDocuments.IN_MEMORY_MAX_BYTES)
    }

    @Test
    fun prefixReadDoesNotRequireFullFileAndMatchesExcerptStart() {
        val file = tempTxt()
        val longBody = "第一章 开始\n" + "这是第一段正文，用来做封面节选。\n".repeat(50)
        writeBytes(file, longBody.toByteArray(Charsets.UTF_8))
        val prefix = TxtDocuments.readPrefix(file, 80)
        val full = TxtParser.readText(file)
        assertEquals(full.take(80), prefix)
        assertTrue(prefix.length <= 80)
        assertTrue(prefix.startsWith("第一章"))
    }

    @Test
    fun streamingSearchMatchesInMemoryAndCrossesChunks() {
        val file = tempTxt()
        val text = "前言AAAA关键字BBBB结尾"
        writeBytes(file, text.toByteArray(Charsets.UTF_8))
        val expected = TxtParser.readText(file)
        val source = TxtDocuments.open(file, inMemoryMaxBytes = 0)
        val memoryHits = TextSearch.search(expected, "关键字")
        val streamed = TextSearch.search(source, "关键字", chunkChars = 6)
        assertEquals(memoryHits.map { it.offset }, streamed.map { it.offset })
        assertEquals(expected.indexOf("关键字"), streamed[0].offset)
        assertTrue(streamed[0].snippet.contains("关键字"))
        // 关键字刚好落在分块边界附近
        val around = "0123456789关键字ABCDEFGHIJ"
        val f2 = tempTxt()
        writeBytes(f2, around.toByteArray(Charsets.UTF_8))
        val src2 = TxtDocuments.open(f2, inMemoryMaxBytes = 0)
        val hits = TextSearch.search(src2, "关键字", chunkChars = 12)
        assertEquals(1, hits.size)
        assertEquals(around.indexOf("关键字"), hits[0].offset)
    }

    @Test
    fun sidecarRoundTripPreservesCheckpoints() {
        val file = tempTxt()
        val dir = File(file.parentFile, "book-sidecar-${file.name}").apply { mkdirs() }
        dir.deleteOnExit()
        writeBytes(file, sampleNovel().toByteArray(Charsets.UTF_8))
        val built = TxtIndexStore.loadOrBuild(file, dir)
        val loaded = TxtIndexStore.load(dir)!!
        assertTrue(loaded.matches(TxtFileStamp.of(file)))
        assertEquals(built.totalCharLength, loaded.totalCharLength)
        assertEquals(built.checkpoints, loaded.checkpoints)
        assertEquals(built.headings.map { it.charOffset to it.title }, loaded.headings.map { it.charOffset to it.title })
        assertTrue(loaded.headings.all { it.kind == TocKind.CHAPTER })
    }

    @Test
    fun splitBlocksWindowMapsGlobalOffsets() {
        val window = com.vibecoding.reader.domain.reader.WindowedText(
            text = "abc\ndef\nghi",
            startOffset = 100,
            endOffset = 111,
            totalLength = 200
        )
        val blocks = TextPaginator.splitBlocksWindow(window, 100, 111, targetBlockChars = 4)
        assertTrue(blocks.isNotEmpty())
        assertEquals(100, blocks.first().startOffset)
        assertEquals(111, blocks.last().endOffset)
        for (i in 1 until blocks.size) {
            assertEquals(blocks[i - 1].endOffset, blocks[i].startOffset)
        }
    }
}
