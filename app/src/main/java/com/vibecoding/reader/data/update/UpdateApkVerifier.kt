package com.vibecoding.reader.data.update

import android.content.pm.PackageManager
import android.os.Build
import com.vibecoding.reader.domain.update.Sha256Digest
import com.vibecoding.reader.domain.update.UpdateError
import com.vibecoding.reader.domain.update.UpdateManifest
import com.vibecoding.reader.domain.update.UpdateVersionPolicy
import java.io.File

class UpdateApkVerifier(
    private val packageManager: PackageManager,
    private val installedPackageName: String
) {
    fun verify(file: File, manifest: UpdateManifest) {
        if (!file.isFile || file.length() != manifest.bytes) {
            throw UpdateError.Verification("下载的安装包不完整")
        }
        val parsed = archiveInfo(file)
            ?: throw UpdateError.Verification("无法解析下载的安装包")
        val signaturesMatch = signerDigests(parsed).let { apkSigs ->
            apkSigs.isNotEmpty() && apkSigs == installedSignerDigests()
        }
        UpdateVersionPolicy.confirmApkMeta(
            expected = manifest,
            actualPackageName = parsed.packageName ?: "",
            expectedPackageName = installedPackageName,
            actualVersionCode = versionCodeOf(parsed),
            signaturesMatch = signaturesMatch
        )
    }

    private fun archiveInfo(file: File): android.content.pm.PackageInfo? {
        val flags = signingFlags()
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        info.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        return info
    }

    private fun installedSignerDigests(): Set<String> {
        val flags = signingFlags()
        val info = packageManager.getPackageInfo(installedPackageName, flags)
        return signerDigests(info)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val certs: Array<android.content.pm.Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        if (certs.isNullOrEmpty()) return emptySet()
        return certs.map { Sha256Digest.ofBytes(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            info.versionCode
        }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }
}
