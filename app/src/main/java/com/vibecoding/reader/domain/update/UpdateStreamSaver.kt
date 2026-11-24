package com.vibecoding.reader.domain.update

import java.io.File
import java.io.InputStream
import kotlin.math.min

object UpdateStreamSaver {
    const val SPACE_MARGIN_BYTES: Long = 2L * 1024 * 1024
    private const val BUFFER = 16 * 1024

    fun spaceError(neededBytes: Long, usableSpace: Long): String? {
        if (neededBytes <= 0L || usableSpace < 0L) return "无法确认存储空间"
        val required = neededBytes + SPACE_MARGIN_BYTES
        return if (usableSpace < required) {
            "存储空间不足，需要约 ${formatSize(required)}，可用 ${formatSize(usableSpace)}"
        } else null
    }

    fun save(
        input: InputStream,
        dest: File,
        expectedBytes: Long,
        expectedSha256: String,
        maxBytes: Long,
        cancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        if (expectedBytes <= 0L || expectedBytes > maxBytes) {
            throw UpdateError.Verification("声明的安装包体积不合法")
        }
        val expectedHash = Sha256Digest.normalizeHex(expectedSha256)
            ?: throw UpdateError.Verification("期望的 sha256 无效")
        dest.parentFile?.mkdirs()
        val digest = Sha256Digest.newIncremental()
        var downloaded = 0L
        val buf = ByteArray(BUFFER)
        try {
            dest.outputStream().use { out ->
                while (true) {
                    if (cancelled()) throw UpdateError.Cancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    if (downloaded + n > expectedBytes || downloaded + n > maxBytes) {
                        throw UpdateError.Security("下载体积超过声明或允许的上限")
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    downloaded += n
                    onProgress(downloaded, expectedBytes)
                }
                out.flush()
            }
            if (cancelled()) throw UpdateError.Cancelled()
            if (downloaded != expectedBytes) {
                throw UpdateError.Verification("下载体积与清单不一致")
            }
            val actual = digest.hex()
            if (!Sha256Digest.equal(actual, expectedHash)) {
                throw UpdateError.Verification("安装包校验失败")
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1fKB", kb)
        val mb = kb / 1024.0
        return String.format("%.1fMB", mb)
    }

    fun coerceProgress(downloaded: Long, total: Long): Float {
        if (total <= 0L) return 0f
        return min(1f, downloaded.toFloat() / total.toFloat())
    }
}
