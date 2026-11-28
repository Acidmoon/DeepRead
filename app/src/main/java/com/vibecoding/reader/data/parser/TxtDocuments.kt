package com.vibecoding.reader.data.parser

import com.vibecoding.reader.domain.reader.InMemoryTextSource
import com.vibecoding.reader.domain.reader.TextSource

import java.io.File

/**
 * TXT 文本源入口。
 *
 * 小于 [IN_MEMORY_MAX_BYTES]（默认 2MB）的文件仍整本读入内存，避免为短篇维护索引；
 * 更大的文件在导入或首次打开时流式扫描，把索引写到书籍私有目录的 sidecar
 *（[TxtIndexStore.FILE_NAME]），阅读时只解码当前窗口。
 */
object TxtDocuments {
    /**
     * 内存路径阈值。选用 2MB 而非 [com.vibecoding.reader.domain.reader.BookLoadGuard.LARGE_WARN_BYTES]
     *（8MB）是为了更早进入窗口化，11MB 级小说一定走索引。
     */
    const val IN_MEMORY_MAX_BYTES: Long = 2L * 1024 * 1024

    /**
     * 打开 TXT。[inMemoryMaxBytes] 可在测试中设为 0，强制走窗口路径。
     */
    fun open(
        file: File,
        bookDir: File? = null,
        inMemoryMaxBytes: Long = IN_MEMORY_MAX_BYTES
    ): TextSource {
        if (!file.exists() || !file.isFile) {
            error("本地文件丢失，请重新导入")
        }
        if (file.length() <= inMemoryMaxBytes) {
            val text = TxtParser.readText(file)
            return InMemoryTextSource(text, TxtParser.buildToc(text))
        }
        val index = TxtIndexStore.loadOrBuild(file, bookDir)
        return IndexedTxtTextSource(file, index)
    }

    /** 只解码前缀，供封面摘录，不读整本。 */
    fun readPrefix(file: File, maxChars: Int): String = TxtIndexer.readPrefix(file, maxChars)
}
