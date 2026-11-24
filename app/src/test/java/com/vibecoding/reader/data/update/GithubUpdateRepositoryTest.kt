package com.vibecoding.reader.data.update

import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.update.Sha256Digest
import com.vibecoding.reader.domain.update.UpdateCheckOutcome
import com.vibecoding.reader.domain.update.UpdateError
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GithubUpdateRepositoryTest {
    private val repo = GithubRepo.DeepRead
    private val apkBytes = "fake-apk-bytes".toByteArray()
    private val sha = Sha256Digest.ofBytes(apkBytes)

    @Test
    fun latest404IsNoPublishedRelease() {
        val http = ScriptedHttp { _, _, _, consume ->
            consume(404, emptyMap(), repo.latestReleaseApiUrl, ByteArrayInputStream(ByteArray(0)))
        }
        val outcome = GithubUpdateRepository(http, repo, "DeepRead-test").check(8, null)
        assertEquals(UpdateCheckOutcome.NoPublishedRelease, outcome)
    }

    @Test
    fun rateLimited403And429() {
        val limited = ScriptedHttp { _, _, _, consume ->
            consume(
                403,
                mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "100"),
                repo.latestReleaseApiUrl,
                ByteArrayInputStream(ByteArray(0))
            )
        }
        val a = GithubUpdateRepository(limited, repo, "DeepRead-test").check(8, null)
        assertTrue(a is UpdateCheckOutcome.RateLimited)
        val tooMany = ScriptedHttp { _, _, _, consume ->
            consume(429, emptyMap(), repo.latestReleaseApiUrl, ByteArrayInputStream(ByteArray(0)))
        }
        assertTrue(GithubUpdateRepository(tooMany, repo, "DeepRead-test").check(8, null) is UpdateCheckOutcome.RateLimited)
    }

    @Test
    fun discoversAvailableUpdateFromManifestAndAsset() {
        val http = ScriptedHttp { request, _, _, consume ->
            when {
                request.url == repo.latestReleaseApiUrl -> consume(
                    200, emptyMap(), request.url, ByteArrayInputStream(releaseJson().toByteArray())
                )
                request.url.endsWith("update.json") -> consume(
                    200, emptyMap(), request.url, ByteArrayInputStream(manifestJson().toByteArray())
                )
                else -> throw AssertionError("unexpected ${request.url}")
            }
        }
        val outcome = GithubUpdateRepository(http, repo, "DeepRead-test").check(8, null)
        val available = outcome as UpdateCheckOutcome.Available
        assertEquals(9, available.offer.manifest.versionCode)
        assertEquals("DeepRead-0.2.4.apk", available.offer.apk.name)
    }

    @Test
    fun sameVersionIsUpToDate() {
        val http = ScriptedHttp { request, _, _, consume ->
            when {
                request.url == repo.latestReleaseApiUrl -> consume(
                    200, emptyMap(), request.url, ByteArrayInputStream(releaseJson().toByteArray())
                )
                request.url.endsWith("update.json") -> consume(
                    200, emptyMap(), request.url, ByteArrayInputStream(manifestJson().toByteArray())
                )
                else -> throw AssertionError(request.url)
            }
        }
        val outcome = GithubUpdateRepository(http, repo, "DeepRead-test").check(9, null)
        assertEquals(UpdateCheckOutcome.UpToDate, outcome)
    }

    @Test
    fun rejectsUntrustedInitialUrl() {
        val http = ScriptedHttp { _, _, _, consume ->
            fail("should not call network")
            consume(200, emptyMap(), "https://evil.example", ByteArrayInputStream(ByteArray(0)))
        }
        try {
            UpdateRedirector.allow("https://evil.example/a.apk", UpdateRedirectPolicy.TrustedAssets, repo)
                .let { assertTrue(!it) }
            GithubUpdateRepository(http, GithubRepo("Acidmoon", "DeepRead"), "DeepRead-test")
        } catch (_: Exception) { }
    }

    @Test
    fun downloadRejectsUntrustedUrlWithoutFetching() {
        val http = ScriptedHttp { _, _, _, _ -> fail("must not fetch") }
        try {
            GithubUpdateRepository(http, repo, "DeepRead-test").downloadToFile(
                url = "https://evil.example/DeepRead.apk",
                dest = kotlin.io.path.createTempFile().toFile(),
                expectedBytes = 1,
                expectedSha256 = sha,
                maxBytes = 100,
                cancelled = { false },
                onProgress = { _, _ -> }
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(e.userMessage.contains("信任"))
        }
    }

    private fun manifestJson(): String = """
        {
          "schemaVersion": "1.0",
          "versionCode": 9,
          "versionName": "0.2.4",
          "minSupportedVersionCode": 8,
          "apkAssetName": "DeepRead-0.2.4.apk",
          "sha256": "$sha",
          "bytes": ${apkBytes.size},
          "releaseNotes": ["测试"],
          "publishedAt": "2026-11-24T16:00:00+08:00",
          "mandatory": false
        }
    """.trimIndent()

    private fun releaseJson(): String = """
        {
          "url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/1",
          "html_url": "https://github.com/Acidmoon/DeepRead/releases/tag/v0.2.4",
          "assets_url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/1/assets",
          "upload_url": "https://upload.github.com/",
          "tarball_url": null,
          "zipball_url": null,
          "id": 1,
          "node_id": "n",
          "tag_name": "v0.2.4",
          "target_commitish": "master",
          "name": "0.2.4",
          "body": null,
          "draft": false,
          "prerelease": false,
          "created_at": "2026-11-24T00:00:00Z",
          "published_at": "2026-11-24T00:00:00Z",
          "author": {"login":"Acidmoon","id":1,"node_id":"n","avatar_url":"https://example.com","gravatar_id":"","url":"https://example.com","html_url":"https://example.com","followers_url":"https://example.com","following_url":"https://example.com","gists_url":"https://example.com","starred_url":"https://example.com","subscriptions_url":"https://example.com","organizations_url":"https://example.com","repos_url":"https://example.com","events_url":"https://example.com","received_events_url":"https://example.com","type":"User","site_admin":false},
          "assets": [
            {
              "url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/assets/1",
              "browser_download_url": "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/update.json",
              "id": 1, "node_id": "n", "name": "update.json", "label": null,
              "state": "uploaded", "content_type": "application/json", "size": 200,
              "digest": null, "download_count": 0,
              "created_at": "2026-11-24T00:00:00Z", "updated_at": "2026-11-24T00:00:00Z"
            },
            {
              "url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/assets/2",
              "browser_download_url": "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/DeepRead-0.2.4.apk",
              "id": 2, "node_id": "n", "name": "DeepRead-0.2.4.apk", "label": null,
              "state": "uploaded", "content_type": "application/vnd.android.package-archive",
              "size": ${apkBytes.size},
              "digest": "sha256:$sha", "download_count": 0,
              "created_at": "2026-11-24T00:00:00Z", "updated_at": "2026-11-24T00:00:00Z"
            }
          ]
        }
    """.trimIndent()
}

private class ScriptedHttp(
    private val handler: (
        request: UpdateHttpRequest,
        policy: UpdateRedirectPolicy,
        repo: GithubRepo,
        consume: (Int, Map<String, String>, String, InputStream) -> Any?
    ) -> Any?
) : UpdateHttpClient {
    @Suppress("UNCHECKED_CAST")
    override fun <T> exchange(
        request: UpdateHttpRequest,
        policy: UpdateRedirectPolicy,
        repo: GithubRepo,
        maxBytes: Long,
        consume: (status: Int, headers: Map<String, String>, finalUrl: String, body: InputStream) -> T
    ): T = handler(request, policy, repo, consume as (Int, Map<String, String>, String, InputStream) -> Any?) as T
}
