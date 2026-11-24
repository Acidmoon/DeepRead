package com.vibecoding.reader.domain.update

object UpdateAssetSelector {
    fun select(
        release: GithubRelease,
        manifest: UpdateManifest,
        repo: GithubRepo
    ): GithubReleaseAsset {
        if (release.draft || release.prerelease) {
            throw UpdateError.Security("不是已发布的正式版本，已忽略")
        }
        val manifests = release.assets.filter { it.name == UpdateManifestParser.MANIFEST_FILE_NAME }
        if (manifests.size != 1) {
            throw UpdateError.Parse("Release 必须恰好包含一份 ${UpdateManifestParser.MANIFEST_FILE_NAME}")
        }
        val uploaded = release.assets.filter { it.state == "uploaded" }
        val matches = uploaded.filter { it.name == manifest.apkAssetName }
        if (matches.isEmpty()) {
            throw UpdateError.Parse("未找到名为 ${manifest.apkAssetName} 的 APK 资产")
        }
        if (matches.size != 1) {
            throw UpdateError.Parse("APK 资产名称不唯一：${manifest.apkAssetName}")
        }
        val apkAssets = uploaded.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.size != 1) {
            throw UpdateError.Parse("Release 必须恰好包含一个 APK 资产")
        }
        if (apkAssets.single().name != manifest.apkAssetName) {
            throw UpdateError.Parse("清单中的 apkAssetName 与唯一 APK 资产不一致")
        }
        val apk = matches.single()
        if (apk.size != manifest.bytes) {
            throw UpdateError.Verification("清单声明的体积与 GitHub 资产大小不一致")
        }
        if (!TrustedUpdateUrls.isAllowedAssetUrl(apk.browserDownloadUrl, repo)) {
            throw UpdateError.Security("APK 下载地址不在可信的 GitHub 资产域内")
        }
        manifest.apkUrl?.let { declared ->
            if (!TrustedUpdateUrls.isAllowedAssetUrl(declared, repo)) {
                throw UpdateError.Security("清单中的 apkUrl 不受信任")
            }
            if (!sameAsset(declared, apk.browserDownloadUrl)) {
                throw UpdateError.Security("清单中的 apkUrl 与 GitHub 资产地址不一致")
            }
        }
        apk.digestSha256?.let { digest ->
            if (!Sha256Digest.equal(digest, manifest.sha256)) {
                throw UpdateError.Verification("清单 sha256 与 GitHub 资产 digest 不一致")
            }
        }
        return apk
    }

    private fun sameAsset(declared: String, actual: String): Boolean {
        val a = TrustedUpdateUrls.parseHttps(declared) ?: return false
        val b = TrustedUpdateUrls.parseHttps(actual) ?: return false
        if (a.host != b.host) return false
        val pathA = a.path?.trimEnd('/') ?: ""
        val pathB = b.path?.trimEnd('/') ?: ""
        return pathA == pathB
    }
}
