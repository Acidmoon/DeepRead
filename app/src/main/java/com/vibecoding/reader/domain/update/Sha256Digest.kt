package com.vibecoding.reader.domain.update

import java.io.InputStream
import java.security.MessageDigest

object Sha256Digest {
    private val HEX = Regex("^[0-9a-fA-F]{64}$")
    private val GITHUB = Regex("^sha256:([0-9a-fA-F]{64})$")

    fun normalizeHex(value: String): String? {
        val trimmed = value.trim()
        if (!HEX.matches(trimmed)) return null
        return trimmed.lowercase()
    }

    /**
     * 解析 GitHub Release 资产的 digest 字段。
     * 官方 Changelog（2025-06-03）与 REST 文档将该字段定义为 SHA-256，实测格式为 sha256:<64 hex>；
     * 缺省为 JSON null（本函数返回 null）。出现其他算法前缀或非法值时拒绝，不猜测旧字段名。
     */
    fun parseGitHubDigest(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw illegal("GitHub digest 为空")
        val match = GITHUB.matchEntire(trimmed) ?: throw illegal("GitHub digest 必须是 sha256: 加 64 位十六进制")
        return match.groupValues[1].lowercase()
    }

    fun equal(a: String, b: String): Boolean = a.lowercase() == b.lowercase() && normalizeHex(a) != null

    fun ofBytes(data: ByteArray): String = toHex(digest().digest(data))

    fun ofStream(input: InputStream, onChunk: ((Int) -> Unit)? = null): String {
        val md = digest()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            md.update(buf, 0, n)
            onChunk?.invoke(n)
        }
        return toHex(md.digest())
    }

    fun newIncremental(): Incremental = Incremental()

    class Incremental {
        private val md = digest()
        fun update(buffer: ByteArray, offset: Int, length: Int) {
            if (length > 0) md.update(buffer, offset, length)
        }
        fun hex(): String = toHex(md.digest())
    }

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hex[v ushr 4]
            out[i * 2 + 1] = hex[v and 0x0f]
        }
        return String(out)
    }

    private fun illegal(message: String): IllegalArgumentException = IllegalArgumentException(message)
}
