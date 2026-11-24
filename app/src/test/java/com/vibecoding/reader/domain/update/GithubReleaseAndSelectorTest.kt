package com.vibecoding.reader.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GithubReleaseAndSelectorTest {
    private val repo = GithubRepo.DeepRead
    private val sha = "234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84"
    private val apkUrl = "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/DeepRead-0.2.4.apk"
    private val manifestUrl = "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/update.json"

    private val manifest = UpdateManifest(
        schemaVersion = "1.0",
        versionCode = 9,
        versionName = "0.2.4",
        minSupportedVersionCode = 8,
        apkAssetName = "DeepRead-0.2.4.apk",
        sha256 = sha,
        bytes = 100,
        releaseNotes = listOf("x"),
        publishedAt = "2026-11-24T08:00:00Z",
        mandatory = false
    )

    @Test
    fun parseLatestAndIgnoreDraft() {
        val json = releaseJson(draft = false, prerelease = false, digest = "sha256:$sha")
        val release = GithubReleaseParser.parseLatestPayload(json)
        assertEquals("v0.2.4", release.tagName)
        assertEquals(sha, release.assets.single { it.name.endsWith(".apk") }.digestSha256)
        val apk = UpdateAssetSelector.select(release, manifest, repo)
        assertEquals("DeepRead-0.2.4.apk", apk.name)
    }

    @Test
    fun httpStatusMapping() {
        assertEquals(UpdateCheckOutcome.NoPublishedRelease, GithubReleaseParser.parseHttpStatus(404, 10, null))
        val limited = GithubReleaseParser.parseHttpStatus(403, 0, 1234L) as UpdateCheckOutcome.RateLimited
        assertEquals(1234L, limited.resetEpochSeconds)
        assertTrue(GithubReleaseParser.parseHttpStatus(429, null, 2L) is UpdateCheckOutcome.RateLimited)
        assertTrue(GithubReleaseParser.parseHttpStatus(403, 5, null) is UpdateCheckOutcome.Failed)
        assertEquals(null, GithubReleaseParser.parseHttpStatus(200, 50, null))
    }

    @Test
    fun rejectsDraftPrereleaseAndMismatchedDigest() {
        try {
            UpdateAssetSelector.select(
                GithubReleaseParser.parseLatestPayload(releaseJson(draft = true, digest = "sha256:$sha")),
                manifest,
                repo
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(e.userMessage.contains("正式"))
        }
        try {
            UpdateAssetSelector.select(
                GithubReleaseParser.parseLatestPayload(
                    releaseJson(digest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                ),
                manifest,
                repo
            )
            fail()
        } catch (e: UpdateError.Verification) {
            assertTrue(e.userMessage.contains("digest"))
        }
    }

    @Test
    fun rejectsUntrustedOrMismatchedApkUrlAndMultipleApks() {
        val withUrl = manifest.copy(apkUrl = "https://evil.example/a.apk")
        try {
            UpdateAssetSelector.select(
                GithubReleaseParser.parseLatestPayload(releaseJson(digest = "sha256:$sha")),
                withUrl,
                repo
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(e.userMessage.isNotBlank())
        }
        val extra = releaseJson(digest = "sha256:$sha").replace(
            "]",
            """,{"url":"u","browser_download_url":"https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/other.apk","id":3,"node_id":"n","name":"other.apk","label":null,"state":"uploaded","content_type":"x","size":1,"digest":null,"download_count":0,"created_at":"2026-11-24T00:00:00Z","updated_at":"2026-11-24T00:00:00Z"}]"""
        )
        try {
            UpdateAssetSelector.select(GithubReleaseParser.parseLatestPayload(extra), manifest, repo)
            fail()
        } catch (e: UpdateError) {
            assertTrue(e.userMessage.contains("一个 APK"))
        }
    }

    @Test
    fun rejectsUnknownDigestAlgorithm() {
        try {
            GithubReleaseParser.parseLatestPayload(releaseJson(digest = "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            fail()
        } catch (e: UpdateError.Parse) {
            assertTrue(e.userMessage.contains("digest"))
        }
    }

    @Test
    fun versionPolicyUsesVersionCodeNotTag() {
        val release = GithubReleaseParser.parseLatestPayload(releaseJson(digest = "sha256:$sha"))
        val apk = UpdateAssetSelector.select(release, manifest, repo)
        assertTrue(UpdateVersionPolicy.evaluate(9, manifest, apk, "v9.9.9") is UpdateCheckOutcome.UpToDate)
        val available = UpdateVersionPolicy.evaluate(8, manifest, apk, "not-a-code") as UpdateCheckOutcome.Available
        assertEquals(9, available.offer.manifest.versionCode)
        assertEquals(false, available.offer.belowMinSupported)
        val forced = UpdateVersionPolicy.evaluate(7, manifest, apk, "v0.2.4") as UpdateCheckOutcome.Available
        assertEquals(true, forced.offer.belowMinSupported)
        assertTrue(UpdateVersionPolicy.shouldAutoCheck(null, 1000L))
        assertTrue(!UpdateVersionPolicy.shouldAutoCheck(1000L, 1000L + 1000L))
        assertTrue(UpdateVersionPolicy.shouldAutoCheck(1000L, 1000L + UpdateVersionPolicy.AUTO_CHECK_INTERVAL_MS))
    }

    @Test
    fun confirmApkMeta() {
        UpdateVersionPolicy.confirmApkMeta(manifest, "com.vibecoding.reader", "com.vibecoding.reader", 9, true)
        try {
            UpdateVersionPolicy.confirmApkMeta(manifest, "com.other", "com.vibecoding.reader", 9, true)
            fail()
        } catch (e: UpdateError.Verification) { }
        try {
            UpdateVersionPolicy.confirmApkMeta(manifest, "com.vibecoding.reader", "com.vibecoding.reader", 10, true)
            fail()
        } catch (e: UpdateError.Verification) { }
        try {
            UpdateVersionPolicy.confirmApkMeta(manifest, "com.vibecoding.reader", "com.vibecoding.reader", 9, false)
            fail()
        } catch (e: UpdateError.Verification) { }
    }

    private fun releaseJson(draft: Boolean = false, prerelease: Boolean = false, digest: String?): String {
        val digestJson = if (digest == null) "null" else "\"$digest\""
        return """
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
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2026-11-24T00:00:00Z",
          "published_at": "2026-11-24T00:00:00Z",
          "author": {"login":"Acidmoon","id":1,"node_id":"n","avatar_url":"https://example.com","gravatar_id":"","url":"https://example.com","html_url":"https://example.com","followers_url":"https://example.com","following_url":"https://example.com","gists_url":"https://example.com","starred_url":"https://example.com","subscriptions_url":"https://example.com","organizations_url":"https://example.com","repos_url":"https://example.com","events_url":"https://example.com","received_events_url":"https://example.com","type":"User","site_admin":false},
          "assets": [
            {
              "url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/assets/1",
              "browser_download_url": "$manifestUrl",
              "id": 1, "node_id": "n", "name": "update.json", "label": null,
              "state": "uploaded", "content_type": "application/json", "size": 200,
              "digest": null, "download_count": 0,
              "created_at": "2026-11-24T00:00:00Z", "updated_at": "2026-11-24T00:00:00Z"
            },
            {
              "url": "https://api.github.com/repos/Acidmoon/DeepRead/releases/assets/2",
              "browser_download_url": "$apkUrl",
              "id": 2, "node_id": "n", "name": "DeepRead-0.2.4.apk", "label": null,
              "state": "uploaded", "content_type": "application/vnd.android.package-archive", "size": 100,
              "digest": $digestJson, "download_count": 0,
              "created_at": "2026-11-24T00:00:00Z", "updated_at": "2026-11-24T00:00:00Z"
            }
          ]
        }
        """.trimIndent()
    }
}
