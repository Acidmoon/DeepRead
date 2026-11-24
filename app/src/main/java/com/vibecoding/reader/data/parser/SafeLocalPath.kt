package com.vibecoding.reader.data.parser

import com.vibecoding.reader.data.parser.ImagePathResolver
import java.io.File

/**
 * 将相对路径解析到指定根目录内，拒绝越出根目录的路径穿越。
 */
object SafeLocalPath {

    fun isInside(base: File, target: File): Boolean {
        val b = runCatching { base.canonicalFile }.getOrNull() ?: return false
        val t = runCatching { target.canonicalFile }.getOrNull() ?: return false
        return t == b || t.path.startsWith(b.path + File.separator)
    }

    /**
     * 在 [baseDir] 下解析 [rawSrc]。远程地址、空路径、或越出根目录时返回 null。
     */
    fun resolveUnder(baseDir: File, rawSrc: String): File? {
        if (rawSrc.isBlank() || ImagePathResolver.isRemote(rawSrc)) return null
        val decoded = ImagePathResolver.decode(rawSrc).replace('\\', '/')
        if (decoded.split('/').any { it == ".." }) {
            // 先按段拒绝 ..，避免依赖规范路径在部分 SAF/异常路径上失真
            val candidate = File(baseDir, decoded)
            val allowed = runCatching { isInside(baseDir, candidate) }.getOrDefault(false)
            if (!allowed) return null
        }
        val candidate = when {
            decoded.startsWith("file:") ->
                File(decoded.removePrefix("file://").removePrefix("file:"))
            decoded.startsWith("/") -> File(decoded)
            else -> File(baseDir, decoded)
        }
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.isFile && isInside(baseDir, resolved) }
    }

    fun safeFileName(name: String, index: Int): String {
        val base = name.substringAfterLast('/').ifBlank { "image" }
        val cleaned = base.replace(Regex("""[^\w.\-]+"""), "_")
        return "${index}_$cleaned"
    }
}
