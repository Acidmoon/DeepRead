package com.vibecoding.reader.domain.update

object UpdateDiscovery {
    fun complete(
        currentVersionCode: Int,
        repo: GithubRepo,
        release: GithubRelease,
        manifestText: String
    ): UpdateCheckOutcome {
        val manifest = try {
            UpdateManifestParser.parse(manifestText)
        } catch (e: UpdateError) {
            return UpdateCheckOutcome.Failed(e)
        }
        val apk = try {
            UpdateAssetSelector.select(release, manifest, repo)
        } catch (e: UpdateError) {
            return UpdateCheckOutcome.Failed(e)
        }
        return UpdateVersionPolicy.evaluate(currentVersionCode, manifest, apk, release.tagName)
    }

    fun header(headers: Map<String, String>, name: String): String? {
        val key = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == key }?.value
    }

    fun rateLimitRemaining(headers: Map<String, String>): Int? =
        header(headers, "x-ratelimit-remaining")?.toIntOrNull()

    fun rateLimitReset(headers: Map<String, String>): Long? =
        header(headers, "x-ratelimit-reset")?.toLongOrNull()
}
