package com.vibecoding.reader.domain.update

object UpdateVersionPolicy {
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun shouldAutoCheck(lastAutoCheckAtMs: Long?, nowMs: Long): Boolean {
        if (lastAutoCheckAtMs == null || lastAutoCheckAtMs <= 0L) return true
        if (lastAutoCheckAtMs > nowMs) return true
        return nowMs - lastAutoCheckAtMs >= AUTO_CHECK_INTERVAL_MS
    }

    fun evaluate(currentVersionCode: Int, manifest: UpdateManifest, apk: GithubReleaseAsset, tag: String): UpdateCheckOutcome {
        if (currentVersionCode < 1) {
            return UpdateCheckOutcome.Failed(UpdateError.Verification("当前安装版本无效"))
        }
        if (manifest.versionCode <= currentVersionCode) {
            return UpdateCheckOutcome.UpToDate
        }
        return UpdateCheckOutcome.Available(
            UpdateOffer(
                manifest = manifest,
                apk = apk,
                releaseTag = tag,
                belowMinSupported = currentVersionCode < manifest.minSupportedVersionCode
            )
        )
    }

    fun confirmApkMeta(
        expected: UpdateManifest,
        actualPackageName: String,
        expectedPackageName: String,
        actualVersionCode: Int,
        signaturesMatch: Boolean
    ) {
        if (actualPackageName != expectedPackageName) {
            throw UpdateError.Verification("安装包名不匹配")
        }
        if (actualVersionCode != expected.versionCode) {
            throw UpdateError.Verification("安装包 versionCode 与清单不一致")
        }
        if (actualVersionCode <= 0) {
            throw UpdateError.Verification("安装包 versionCode 无效")
        }
        if (!signaturesMatch) {
            throw UpdateError.Verification("安装包签名与当前应用不一致，无法覆盖升级")
        }
    }
}
