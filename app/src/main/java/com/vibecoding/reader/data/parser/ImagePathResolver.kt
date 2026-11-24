package com.vibecoding.reader.data.parser

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object ImagePathResolver {

    fun isRemote(src: String): Boolean {
        val s = src.trim().lowercase()
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("data:")
    }

    fun decode(src: String): String =
        runCatching {
            URLDecoder.decode(src.trim().substringBefore('#'), StandardCharsets.UTF_8.name())
        }.getOrDefault(src.trim().substringBefore('#'))

    /**
     * 将相对路径解析为绝对文件；不存在或越出 [baseDir] 则返回 null。
     */
    fun resolveLocal(baseDir: File?, src: String): String? {
        if (src.isBlank() || isRemote(src)) return null
        val base = baseDir ?: return null
        return SafeLocalPath.resolveUnder(base, src)?.absolutePath
    }
}
