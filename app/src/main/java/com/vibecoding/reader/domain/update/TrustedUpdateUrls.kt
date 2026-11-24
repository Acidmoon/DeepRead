package com.vibecoding.reader.domain.update

import java.net.URI

/**
 * 限制更新流量只走 HTTPS，且仅允许本仓库的 GitHub API / Release 资产域。
 * 下载过程中的重定向也必须通过同一套规则，禁止 HTTP、内嵌凭据、任意主机或降级。
 */
object TrustedUpdateUrls {
    const val MAX_REDIRECTS = 5

    private val DOWNLOAD_PATH = Regex(
        "^/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/releases/download/([^/]+)/([^/]+)$"
    )

    private val ASSET_CDN_HOSTS = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    fun latestApi(repo: GithubRepo): String = repo.latestReleaseApiUrl

    fun isAllowedApiUrl(url: String, repo: GithubRepo): Boolean {
        val uri = parseHttps(url) ?: return false
        if (uri.host != "api.github.com") return false
        if (!uri.rawQuery.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return false
        val expected = "/repos/${repo.owner}/${repo.repo}/releases/latest"
        return normalizePath(uri) == expected
    }

    fun isAllowedAssetUrl(url: String, repo: GithubRepo): Boolean {
        val uri = parseHttps(url) ?: return false
        val host = uri.host ?: return false
        return when (host) {
            "github.com" -> isRepoDownloadPath(uri, repo)
            in ASSET_CDN_HOSTS -> true
            else -> false
        }
    }

    fun parseHttps(url: String): URI? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096) return null
        if (trimmed.any { it.isWhitespace() || it == '\u0000' }) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (!uri.isAbsolute || uri.scheme != "https") return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".")) return null
        val port = uri.port
        if (port != -1 && port != 443) return null
        return URI("https", host, uri.path ?: "", uri.query, uri.fragment)
    }

    fun redirectTarget(base: String, location: String): String? {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return null
        val resolved = try {
            val baseUri = URI(base)
            baseUri.resolve(trimmed).toString()
        } catch (_: Exception) {
            return null
        }
        return resolved
    }

    private fun isRepoDownloadPath(uri: URI, repo: GithubRepo): Boolean {
        val path = normalizePath(uri)
        val match = DOWNLOAD_PATH.matchEntire(path) ?: return false
        return match.groupValues[1].equals(repo.owner, ignoreCase = true) &&
            match.groupValues[2].equals(repo.repo, ignoreCase = true) &&
            match.groupValues[3].isNotBlank() &&
            match.groupValues[4].isNotBlank() &&
            !match.groupValues[3].contains("..") &&
            !match.groupValues[4].contains("..")
    }

    private fun normalizePath(uri: URI): String {
        val path = uri.path ?: return ""
        return if (path.startsWith("/")) path else "/$path"
    }
}
