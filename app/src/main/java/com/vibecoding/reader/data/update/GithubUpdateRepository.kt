package com.vibecoding.reader.data.update

import com.vibecoding.reader.domain.update.GithubRelease
import com.vibecoding.reader.domain.update.GithubReleaseParser
import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.update.TrustedUpdateUrls
import com.vibecoding.reader.domain.update.UpdateCheckOutcome
import com.vibecoding.reader.domain.update.UpdateDiscovery
import com.vibecoding.reader.domain.update.UpdateError
import com.vibecoding.reader.domain.update.UpdateManifestParser

class GithubUpdateRepository(
    private val http: UpdateHttpClient,
    private val repo: GithubRepo,
    private val userAgent: String
) {
    fun check(currentVersionCode: Int, cached: CachedRelease?): UpdateCheckOutcome {
        val fetch = fetchLatest(cached?.etag)
        val release = when (fetch) {
            is LatestFetch.NotModified -> cached?.release
                ?: return UpdateCheckOutcome.Failed(UpdateError.Network("本地没有可用的更新缓存"))
            is LatestFetch.Body -> fetch.release
            is LatestFetch.Done -> return fetch.outcome
        }
        val manifestAsset = release.assets.singleOrNull {
            it.name == UpdateManifestParser.MANIFEST_FILE_NAME && it.state == "uploaded"
        } ?: return UpdateCheckOutcome.Failed(
            UpdateError.Parse("该 Release 缺少 ${UpdateManifestParser.MANIFEST_FILE_NAME}")
        )
        if (!TrustedUpdateUrls.isAllowedAssetUrl(manifestAsset.browserDownloadUrl, repo)) {
            return UpdateCheckOutcome.Failed(UpdateError.Security("更新清单地址不受信任"))
        }
        if (manifestAsset.size <= 0L || manifestAsset.size > UpdateManifestParser.MAX_BYTES) {
            return UpdateCheckOutcome.Failed(UpdateError.Parse("更新清单体积无效"))
        }
        val manifestText = try {
            downloadSmall(manifestAsset.browserDownloadUrl, UpdateManifestParser.MAX_BYTES.toLong())
        } catch (e: UpdateError) {
            return UpdateCheckOutcome.Failed(e)
        }
        return UpdateDiscovery.complete(currentVersionCode, repo, release, manifestText)
    }

    fun fetchLatest(etag: String?): LatestFetch {
        val url = repo.latestReleaseApiUrl
        if (!TrustedUpdateUrls.isAllowedApiUrl(url, repo)) {
            return LatestFetch.Done(UpdateCheckOutcome.Failed(UpdateError.Security("更新接口地址不受信任")))
        }
        val headers = linkedMapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to userAgent,
            "X-GitHub-Api-Version" to "2022-11-28"
        )
        if (!etag.isNullOrBlank()) headers["If-None-Match"] = etag
        return try {
            http.exchange(
                UpdateHttpRequest(url, headers),
                UpdateRedirectPolicy.ApiLatest,
                repo,
                GithubReleaseParser.MAX_RELEASE_JSON_BYTES.toLong()
            ) { status, hdrs, _, body ->
                val mapped = GithubReleaseParser.parseHttpStatus(
                    status,
                    UpdateDiscovery.rateLimitRemaining(hdrs),
                    UpdateDiscovery.rateLimitReset(hdrs)
                )
                if (mapped != null) return@exchange LatestFetch.Done(mapped)
                if (status == 304) return@exchange LatestFetch.NotModified
                if (status != 200) {
                    return@exchange LatestFetch.Done(
                        UpdateCheckOutcome.Failed(UpdateError.Network("检查更新失败（$status）"))
                    )
                }
                val text = body.reader(Charsets.UTF_8).readText()
                val newEtag = UpdateDiscovery.header(hdrs, "etag")
                val release = GithubReleaseParser.parseLatestPayload(text, newEtag)
                LatestFetch.Body(release)
            }
        } catch (e: UpdateError) {
            LatestFetch.Done(UpdateCheckOutcome.Failed(e))
        }
    }

    fun downloadToFile(
        url: String,
        dest: java.io.File,
        expectedBytes: Long,
        expectedSha256: String,
        maxBytes: Long,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit
    ) {
        if (!TrustedUpdateUrls.isAllowedAssetUrl(url, repo)) {
            throw UpdateError.Security("APK 下载地址不受信任")
        }
        val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/octet-stream")
        http.exchange(
            UpdateHttpRequest(url, headers),
            UpdateRedirectPolicy.TrustedAssets,
            repo,
            maxBytes
        ) { status, _, _, body ->
            if (status != 200) {
                throw UpdateError.Network("下载安装包失败（$status）")
            }
            com.vibecoding.reader.domain.update.UpdateStreamSaver.save(
                input = body,
                dest = dest,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                maxBytes = maxBytes,
                cancelled = cancelled,
                onProgress = onProgress
            )
        }
    }

    private fun downloadSmall(url: String, maxBytes: Long): String {
        val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/octet-stream")
        return http.exchange(
            UpdateHttpRequest(url, headers),
            UpdateRedirectPolicy.TrustedAssets,
            repo,
            maxBytes
        ) { status, _, _, body ->
            if (status != 200) throw UpdateError.Network("下载更新清单失败（$status）")
            body.reader(Charsets.UTF_8).readText()
        }
    }
}

sealed class LatestFetch {
    data class Body(val release: GithubRelease) : LatestFetch()
    data object NotModified : LatestFetch()
    data class Done(val outcome: UpdateCheckOutcome) : LatestFetch()
}

data class CachedRelease(
    val etag: String?,
    val release: GithubRelease
)
