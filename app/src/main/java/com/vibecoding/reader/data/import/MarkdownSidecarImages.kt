package com.vibecoding.reader.data.import

import com.vibecoding.reader.data.parser.SafeLocalPath
import java.io.File
import java.io.InputStream

/**
 * 把 Markdown 引用的本地图片安全复制到书籍目录，并改写为相对路径 media/...
 */
object MarkdownSidecarImages {
    private val mdImage = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val htmlImg = Regex("""(?i)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>""")

    fun findLocalSources(markdown: String): List<String> {
        val out = ArrayList<String>()
        mdImage.findAll(markdown).forEach { out += it.groupValues[2].trim() }
        htmlImg.findAll(markdown).forEach { out += it.groupValues[1].trim() }
        return out.distinct()
    }

    /**
     * @return 改写后的 markdown 与成功复制的图片数
     */
    fun copyAndRewrite(
        markdown: String,
        sourceBase: File,
        bookDir: File,
        openSource: ((File) -> InputStream)? = null
    ): Pair<String, Int> {
        val media = File(bookDir, "media").apply { mkdirs() }
        val mapped = LinkedHashMap<String, String>()
        var copied = 0
        for (src in findLocalSources(markdown)) {
            val file = SafeLocalPath.resolveUnder(sourceBase, src) ?: continue
            val destName = SafeLocalPath.safeFileName(file.name, mapped.size)
            val dest = File(media, destName)
            val ok = runCatching {
                val input = openSource?.invoke(file) ?: file.inputStream()
                input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
                dest.exists() && dest.length() > 0L
            }.getOrDefault(false)
            if (ok) {
                mapped[src] = "media/$destName"
                copied++
            } else {
                runCatching { dest.delete() }
            }
        }
        if (mapped.isEmpty()) return markdown to 0
        var rewritten = markdown
        for ((from, to) in mapped) {
            rewritten = rewritten.replace(from, to)
        }
        return rewritten to copied
    }

    /**
     * 仅改写已经给出的「原引用 -> media/相对路径」映射（供 DocumentFile 导入使用）。
     */
    fun applyRewrite(markdown: String, mapped: Map<String, String>): String {
        if (mapped.isEmpty()) return markdown
        var rewritten = markdown
        for ((from, to) in mapped) {
            rewritten = rewritten.replace(from, to)
        }
        return rewritten
    }

    fun isUnsafeRelative(src: String): Boolean {
        val decoded = src.trim().replace('\\', '/')
        if (decoded.isBlank()) return true
        val parts = decoded.split('/')
        var depth = 0
        for (part in parts) {
            when (part) {
                "", "." -> continue
                ".." -> {
                    depth--
                    if (depth < 0) return true
                }
                else -> depth++
            }
        }
        return false
    }
}
