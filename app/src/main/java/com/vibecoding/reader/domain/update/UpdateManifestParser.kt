package com.vibecoding.reader.domain.update

object UpdateManifestParser {
    const val SCHEMA_VERSION = "1.0"
    const val MAX_BYTES = 64 * 1024
    const val MAX_NOTES = 32
    const val MAX_NOTE_LENGTH = 500
    const val MAX_VERSION_CODE = 2_147_483_647
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MANIFEST_FILE_NAME = "update.json"

    private val ALLOWED_KEYS = setOf(
        "schemaVersion",
        "versionCode",
        "versionName",
        "minSupportedVersionCode",
        "apkAssetName",
        "apkUrl",
        "sha256",
        "bytes",
        "releaseNotes",
        "publishedAt",
        "mandatory"
    )

    private val ASSET_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._() +-]{0,191}\\.apk$")
    private val ISO_TIMESTAMP = Regex(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$"
    )

    fun parse(text: String): UpdateManifest {
        if (text.length > MAX_BYTES) {
            throw UpdateError.Parse("更新清单过大")
        }
        val fields = try {
            StrictJson.asObject(text)
        } catch (e: JsonParseException) {
            throw UpdateError.Parse("更新清单不是合法 JSON")
        }
        val unknown = fields.keys - ALLOWED_KEYS
        if (unknown.isNotEmpty()) {
            throw UpdateError.Parse("更新清单含未声明字段：${unknown.sorted().joinToString()}")
        }
        if (str(fields, "schemaVersion") != SCHEMA_VERSION) {
            throw UpdateError.Parse("更新清单仅支持 schemaVersion $SCHEMA_VERSION")
        }
        val versionCode = int(fields, "versionCode", 1, MAX_VERSION_CODE)
        val versionName = str(fields, "versionName", 1, 64)
        val minSupported = int(fields, "minSupportedVersionCode", 1, versionCode)
        val apkAssetName = str(fields, "apkAssetName", 1, 200)
        if (!ASSET_NAME.matches(apkAssetName) || apkAssetName.contains("..") || apkAssetName.contains('/')) {
            throw UpdateError.Parse("apkAssetName 非法")
        }
        val sha256 = Sha256Digest.normalizeHex(str(fields, "sha256", 64, 64))
            ?: throw UpdateError.Parse("sha256 必须是 64 位十六进制")
        val bytes = long(fields, "bytes", 1, MAX_APK_BYTES)
        val notesVal = fields["releaseNotes"] as? JsonVal.Arr
            ?: throw UpdateError.Parse("releaseNotes 必须是数组")
        if (notesVal.items.size > MAX_NOTES) {
            throw UpdateError.Parse("releaseNotes 最多 $MAX_NOTES 条")
        }
        val releaseNotes = notesVal.items.mapIndexed { idx, item ->
            val s = (item as? JsonVal.Str)?.value?.trim()
                ?: throw UpdateError.Parse("releaseNotes[$idx] 必须是字符串")
            if (s.isEmpty() || s.length > MAX_NOTE_LENGTH || s.any { it.isISOControl() }) {
                throw UpdateError.Parse("releaseNotes[$idx] 格式无效")
            }
            s
        }
        val publishedAtRaw = str(fields, "publishedAt", 10, 40)
        if (!ISO_TIMESTAMP.matches(publishedAtRaw)) {
            throw UpdateError.Parse("publishedAt 必须是带时区的 ISO 8601 时间")
        }
        val publishedAt = try {
            java.time.OffsetDateTime.parse(publishedAtRaw).toInstant().toString()
        } catch (_: Exception) {
            throw UpdateError.Parse("publishedAt 必须是带时区的 ISO 8601 时间")
        }
        val mandatory = (fields["mandatory"] as? JsonVal.Bool)?.value
            ?: throw UpdateError.Parse("mandatory 必须是布尔值")
        val apkUrl = if (fields.containsKey("apkUrl")) {
            val url = str(fields, "apkUrl", 1, 2048)
            url
        } else null
        return UpdateManifest(
            schemaVersion = SCHEMA_VERSION,
            versionCode = versionCode,
            versionName = versionName,
            minSupportedVersionCode = minSupported,
            apkAssetName = apkAssetName,
            sha256 = sha256,
            bytes = bytes,
            releaseNotes = releaseNotes,
            publishedAt = publishedAt,
            mandatory = mandatory,
            apkUrl = apkUrl
        )
    }

    private fun str(fields: Map<String, JsonVal>, key: String, min: Int = 1, max: Int = 256): String {
        val raw = (fields[key] as? JsonVal.Str)?.value?.trim()
            ?: throw UpdateError.Parse("字段 $key 必须是字符串")
        if (raw.length !in min..max || raw.any { it.isISOControl() }) {
            throw UpdateError.Parse("字段 $key 格式无效")
        }
        return raw
    }

    private fun int(fields: Map<String, JsonVal>, key: String, min: Int, max: Int): Int {
        val v = (fields[key] as? JsonVal.IntNum)?.value
            ?: throw UpdateError.Parse("字段 $key 必须是整数")
        if (v < min.toLong() || v > max.toLong()) {
            throw UpdateError.Parse("字段 $key 必须是 $min–$max 的整数")
        }
        return v.toInt()
    }

    private fun long(fields: Map<String, JsonVal>, key: String, min: Long, max: Long): Long {
        val v = (fields[key] as? JsonVal.IntNum)?.value
            ?: throw UpdateError.Parse("字段 $key 必须是整数")
        if (v < min || v > max) {
            throw UpdateError.Parse("字段 $key 超出允许范围")
        }
        return v
    }
}
