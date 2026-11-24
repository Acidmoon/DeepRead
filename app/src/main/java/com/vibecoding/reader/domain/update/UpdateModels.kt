package com.vibecoding.reader.domain.update

/**
 * GitHub Release 附属的 update.json 契约（schemaVersion 1.0）。
 * 版本比较以 [versionCode] 为准，不用 tag 或 versionName。
 */
data class UpdateManifest(
    val schemaVersion: String,
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int,
    val apkAssetName: String,
    val sha256: String,
    val bytes: Long,
    val releaseNotes: List<String>,
    val publishedAt: String,
    val mandatory: Boolean,
    val apkUrl: String? = null
)

data class GithubRepo(
    val owner: String,
    val repo: String
) {
    init {
        require(OWNER_REPO.matches(owner)) { "非法仓库 owner" }
        require(OWNER_REPO.matches(repo)) { "非法仓库名" }
    }

    val latestReleaseApiUrl: String
        get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    companion object {
        private val OWNER_REPO = Regex("^[A-Za-z0-9_.-]+$")
        val DeepRead = GithubRepo("Acidmoon", "DeepRead")
    }
}

data class GithubReleaseAsset(
    val name: String,
    val state: String,
    val size: Long,
    val digestSha256: String?,
    val browserDownloadUrl: String
)

data class GithubRelease(
    val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GithubReleaseAsset>,
    val etag: String? = null
)

data class UpdateOffer(
    val manifest: UpdateManifest,
    val apk: GithubReleaseAsset,
    val releaseTag: String,
    val belowMinSupported: Boolean
)

enum class UpdateNoticeKind {
    /** 自动检查中的可解释状态，界面应保持安静。 */
    Silent,
    /** 用户主动检查，需要把原因说清楚。 */
    Manual
}

sealed class UpdateCheckOutcome {
    data class Available(val offer: UpdateOffer) : UpdateCheckOutcome()
    data object UpToDate : UpdateCheckOutcome()
    data object NoPublishedRelease : UpdateCheckOutcome()
    data class RateLimited(val resetEpochSeconds: Long?) : UpdateCheckOutcome()
    data class Failed(val error: UpdateError) : UpdateCheckOutcome()
    data object Disabled : UpdateCheckOutcome()
}

sealed class UpdateError(val userMessage: String) : Exception(userMessage) {
    class Parse(detail: String) : UpdateError(detail)
    class Security(detail: String) : UpdateError(detail)
    class Network(detail: String) : UpdateError(detail)
    class Storage(detail: String) : UpdateError(detail)
    class Cancelled : UpdateError("已取消")
    class Timeout : UpdateError("连接或下载超时，请稍后重试")
    class Verification(detail: String) : UpdateError(detail)
    class Install(detail: String) : UpdateError(detail)
}
