package com.vibecoding.reader.data.update

import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.update.TrustedUpdateUrls
import com.vibecoding.reader.domain.update.UpdateError
import java.io.InputStream

data class UpdateHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

class UpdateHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val finalUrl: String
)

enum class UpdateRedirectPolicy {
    None,
    ApiLatest,
    TrustedAssets
}

interface UpdateHttpClient {
    fun <T> exchange(
        request: UpdateHttpRequest,
        policy: UpdateRedirectPolicy,
        repo: GithubRepo,
        maxBytes: Long,
        consume: (status: Int, headers: Map<String, String>, finalUrl: String, body: InputStream) -> T
    ): T
}

object UpdateRedirector {
    fun allow(url: String, policy: UpdateRedirectPolicy, repo: GithubRepo): Boolean {
        return when (policy) {
            UpdateRedirectPolicy.None -> false
            UpdateRedirectPolicy.ApiLatest -> TrustedUpdateUrls.isAllowedApiUrl(url, repo)
            UpdateRedirectPolicy.TrustedAssets -> TrustedUpdateUrls.isAllowedAssetUrl(url, repo)
        }
    }

    fun nextUrl(currentUrl: String, location: String, policy: UpdateRedirectPolicy, repo: GithubRepo): String {
        val resolved = TrustedUpdateUrls.redirectTarget(currentUrl, location)
            ?: throw UpdateError.Security("重定向地址无效")
        if (!allow(resolved, policy, repo)) {
            throw UpdateError.Security("拒绝重定向到不受信任的地址")
        }
        return resolved
    }
}
