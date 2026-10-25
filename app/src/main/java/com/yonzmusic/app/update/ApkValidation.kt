package com.yonzmusic.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import com.yonzmusic.app.network.SafeHttp
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object ApkValidation {
    fun fingerprint(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Suppress("DEPRECATION")
    fun installedCertificate(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        require(certificates?.size == 1) { "Multiple signing identities are not supported by this update channel." }
        return fingerprint(certificates.single().toByteArray())
    }
    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, expected: UpdateArtifact) {
        require(expected.applicationId == context.packageName) { "Update metadata targets a different package." }
        require(file.length() == expected.size && SafeHttp.sha256(file) == expected.sha256) { "The APK checksum or size did not match. Download it again." }
        // Parsing a manifest/certificate alone is insufficient: verify the APK's actual signatures and content.
        val verified = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        require(verified.isVerified && verified.signerCertificates.size == 1) { "The APK is unsigned or its signature is invalid." }
        val certificate = fingerprint(verified.signerCertificates.single().encoded)
        require(certificate == expected.certificate && certificate == installedCertificate(context)) { "This APK uses a different signing key." }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: error("Android could not read this APK.")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && code == expected.versionCode && code > installedCode(context) && archive.versionName == expected.versionName) { "APK package or version metadata differs." }
        val min = archive.applicationInfo?.minSdkVersion ?: error("APK SDK information is missing.")
        if (!com.yonzmusic.app.BuildConfig.DEBUG) require(archive.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Development APKs are not eligible for the production channel." }
        require(min == expected.minSdk && min <= Build.VERSION.SDK_INT) { "This APK requires a different Android version." }
        ZipFile(file).use { zip ->
            val nativeAbis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            require(nativeAbis.isEmpty() || nativeAbis.intersect(Build.SUPPORTED_ABIS.toSet()).isNotEmpty()) { "This APK does not support this device's CPU." }
            require("universal" in expected.abis || nativeAbis.isEmpty() || nativeAbis.all { it in expected.abis }) { "APK ABI metadata differs." }
        }
    }
}
