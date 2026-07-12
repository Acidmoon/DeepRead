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
     * 将相对路径解析为绝对文件；不存在则返回 null。
     */
    fun resolveLocal(baseDir: File?, src: String): String? {
        if (src.isBlank() || isRemote(src)) return null
        val decoded = decode(src).replace('\\', '/')
        if (decoded.startsWith("file:")) {
            val f = File(decoded.removePrefix("file://").removePrefix("file:"))
            return f.takeIf { it.exists() }?.absolutePath
        }
        val base = baseDir ?: return null
        // 去掉开头 ./
        val rel = decoded.removePrefix("./")
        val candidates = listOf(
            File(base, rel),
            File(base, rel.substringAfterLast('/')),
            File(base.parentFile ?: base, rel)
        )
        return candidates.firstOrNull { it.exists() && it.isFile }?.absolutePath
    }
}
