package com.vibecoding.reader.domain.update

object GithubReleaseParser {
    const val MAX_RELEASE_JSON_BYTES = 512 * 1024

    fun parseLatestPayload(body: String, etag: String? = null): GithubRelease {
        if (body.length > MAX_RELEASE_JSON_BYTES) {
            throw UpdateError.Parse("GitHub Release 响应过大")
        }
        val root = try {
            StrictJson.asObject(body)
        } catch (_: JsonParseException) {
            throw UpdateError.Parse("无法解析 GitHub Release 响应")
        }
        val draft = bool(root, "draft")
        val prerelease = bool(root, "prerelease")
        val tagName = str(root, "tag_name")
        val assetsVal = root["assets"] as? JsonVal.Arr
            ?: throw UpdateError.Parse("GitHub Release 缺少 assets")
        val assets = assetsVal.items.mapIndexed { idx, item ->
            val obj = (item as? JsonVal.Obj)?.fields
                ?: throw UpdateError.Parse("assets[$idx] 必须是对象")
            parseAsset(obj, idx)
        }
        return GithubRelease(
            tagName = tagName,
            draft = draft,
            prerelease = prerelease,
            assets = assets,
            etag = etag
        )
    }

    fun parseHttpStatus(
        status: Int,
        rateLimitRemaining: Int?,
        resetEpochSeconds: Long?
    ): UpdateCheckOutcome? {
        when (status) {
            200 -> return null
            304 -> return null
            404 -> return UpdateCheckOutcome.NoPublishedRelease
            429 -> return UpdateCheckOutcome.RateLimited(resetEpochSeconds)
            403 -> {
                if (rateLimitRemaining != null && rateLimitRemaining <= 0) {
                    return UpdateCheckOutcome.RateLimited(resetEpochSeconds)
                }
                return UpdateCheckOutcome.Failed(UpdateError.Network("GitHub 暂时拒绝了更新检查"))
            }
        }
        if (status in 500..599) {
            return UpdateCheckOutcome.Failed(UpdateError.Network("GitHub 服务暂时不可用（$status）"))
        }
        return UpdateCheckOutcome.Failed(UpdateError.Network("检查更新失败（$status）"))
    }

    private fun parseAsset(fields: Map<String, JsonVal>, idx: Int): GithubReleaseAsset {
        val name = str(fields, "name")
        val state = str(fields, "state")
        val size = (fields["size"] as? JsonVal.IntNum)?.value
            ?: throw UpdateError.Parse("assets[$idx].size 必须是整数")
        if (size < 0) throw UpdateError.Parse("assets[$idx].size 非法")
        val digestRaw = when (val d = fields["digest"]) {
            null, JsonVal.Null -> null
            is JsonVal.Str -> d.value
            else -> throw UpdateError.Parse("assets[$idx].digest 格式无效")
        }
        val digest = try {
            Sha256Digest.parseGitHubDigest(digestRaw)
        } catch (e: IllegalArgumentException) {
            throw UpdateError.Parse(e.message ?: "digest 无效")
        }
        val url = str(fields, "browser_download_url")
        return GithubReleaseAsset(
            name = name,
            state = state,
            size = size,
            digestSha256 = digest,
            browserDownloadUrl = url
        )
    }

    private fun bool(fields: Map<String, JsonVal>, key: String): Boolean =
        (fields[key] as? JsonVal.Bool)?.value
            ?: throw UpdateError.Parse("GitHub Release 缺少布尔字段 $key")

    private fun str(fields: Map<String, JsonVal>, key: String): String {
        val v = (fields[key] as? JsonVal.Str)?.value?.trim()
            ?: throw UpdateError.Parse("GitHub Release 缺少字段 $key")
        if (v.isEmpty() || v.length > 2048) throw UpdateError.Parse("GitHub Release 字段 $key 无效")
        return v
    }
}
