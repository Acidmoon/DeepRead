package com.vibecoding.reader.domain.update

import com.vibecoding.reader.data.update.UpdateRedirectPolicy
import com.vibecoding.reader.data.update.UpdateRedirector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TrustedUpdateUrlsTest {
    private val repo = GithubRepo.DeepRead

    @Test
    fun allowsOfficialApiAndAssets() {
        assertTrue(TrustedUpdateUrls.isAllowedApiUrl(repo.latestReleaseApiUrl, repo))
        assertTrue(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/DeepRead-0.2.4.apk",
                repo
            )
        )
        assertTrue(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc",
                repo
            )
        )
        assertTrue(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/1",
                repo
            )
        )
    }

    @Test
    fun rejectsUnsafeUrls() {
        assertFalse(TrustedUpdateUrls.isAllowedApiUrl("http://api.github.com/repos/Acidmoon/DeepRead/releases/latest", repo))
        assertFalse(TrustedUpdateUrls.isAllowedApiUrl("https://api.github.com/repos/Acidmoon/DeepRead/releases/latest?foo=1", repo))
        assertFalse(TrustedUpdateUrls.isAllowedApiUrl("https://user:pass@api.github.com/repos/Acidmoon/DeepRead/releases/latest", repo))
        assertFalse(TrustedUpdateUrls.isAllowedApiUrl("https://api.github.com/repos/octocat/Hello-World/releases/latest", repo))
        assertFalse(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://github.com/octocat/Hello-World/releases/download/v1/a.apk",
                repo
            )
        )
        assertFalse(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://evil.example/DeepRead.apk",
                repo
            )
        )
        assertFalse(
            TrustedUpdateUrls.isAllowedAssetUrl(
                "https://release-assets.githubusercontent.com.evil.example/a",
                repo
            )
        )
        assertFalse(TrustedUpdateUrls.isAllowedAssetUrl("https://github.com/Acidmoon/DeepRead/releases/download/v1/../a.apk", repo))
        assertTrue(TrustedUpdateUrls.parseHttps("https://github.com:80/x") == null)
    }

    @Test
    fun redirectorRejectsDowngradeAndOpenRedirect() {
        try {
            UpdateRedirector.nextUrl(
                "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/a.apk",
                "http://release-assets.githubusercontent.com/a",
                UpdateRedirectPolicy.TrustedAssets,
                repo
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(e.userMessage.contains("不受信任") || e.userMessage.contains("无效"))
        }
        try {
            UpdateRedirector.nextUrl(
                "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/a.apk",
                "https://evil.example/a.apk",
                UpdateRedirectPolicy.TrustedAssets,
                repo
            )
            fail()
        } catch (e: UpdateError.Security) {
            assertTrue(e.userMessage.isNotBlank())
        }
        val ok = UpdateRedirector.nextUrl(
            "https://github.com/Acidmoon/DeepRead/releases/download/v0.2.4/a.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1",
            UpdateRedirectPolicy.TrustedAssets,
            repo
        )
        assertTrue(ok.startsWith("https://release-assets.githubusercontent.com/"))
    }
}
