package com.yonzmusic.app.update

import org.json.JSONObject
import java.net.URI

data class ReleaseAsset(val name: String, val size: Long, val url: String, val id: Long)
data class PublishedRelease(val tag: String, val summary: String, val assets: List<ReleaseAsset>)
data class UpdateArtifact(val applicationId: String, val versionCode: Long, val versionName: String, val tag: String,
    val minSdk: Int, val name: String, val size: Long, val sha256: String, val certificate: String,
    val abis: Set<String>, val url: String, val summary: String)

object JsonGuard {
    fun parse(text: String, limit: Int): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= limit) { "Metadata is too large." }
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && c == '\\') { escaped = true; continue }
            if (c == '"') { quoted = !quoted; continue }
            if (!quoted && c in "[{") { depth++; require(depth <= 24) { "Metadata nesting is too deep." } }
            if (!quoted && c in "]}") { depth--; require(depth >= 0) { "Malformed metadata." } }
        }
        require(depth == 0 && !quoted) { "Incomplete metadata." }
        return JSONObject(text)
    }
}

/** Versioned stable-channel contract. Artifact names resolve only within the exact API release. */
object ReleaseContract {
    const val REPOSITORY = "arisynn/yonz-music"
    const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    const val MAX_APK = 150L * 1024 * 1024
    private val namePattern = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")
    fun release(text: String): PublishedRelease {
        val json = JsonGuard.parse(text, 2 * 1024 * 1024)
        require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "This release is not in the stable channel." }
        val tag = json.getString("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:[.][0-9]+)?").matches(tag)) { "Unsupported release tag." }
        val array = json.getJSONArray("assets"); require(array.length() in 1..100)
        val assets = (0 until array.length()).map { n ->
            val asset = array.getJSONObject(n); val name = asset.getString("name")
            require(namePattern.matches(name) && !name.contains("..")) { "Unsafe asset name." }
            val url = asset.getString("browser_download_url")
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
                uri.rawPath == "/$REPOSITORY/releases/download/$tag/$name") { "Asset is outside this release." }
            ReleaseAsset(name, asset.getLong("size"), url, asset.getLong("id")).also { require(it.size >= 0 && it.id > 0) }
        }
        require(assets.map { it.name }.distinct().size == assets.size && assets.map { it.id }.distinct().size == assets.size) { "Ambiguous release assets." }
        return PublishedRelease(tag, json.optString("body").take(3000), assets)
    }
    fun select(release: PublishedRelease, text: String, installedId: String, installedCode: Long, certificate: String, sdk: Int, abis: Set<String>): UpdateArtifact? {
        val json = JsonGuard.parse(text, 64 * 1024)
        require(json.getInt("schemaVersion") == 1) { "Unsupported update metadata schema." }
        require(json.getString("applicationId") == installedId) { "This release is for a different application." }
        require(json.getString("releaseTag") == release.tag) { "Metadata belongs to a different release." }
        require(json.get("versionCode") is Int || json.get("versionCode") is Long) { "versionCode must be an integer." }
        val code = json.getLong("versionCode"); require(code > 0)
        val version = json.getString("versionName"); require(version.length in 1..40)
        val array = json.getJSONArray("artifacts"); require(array.length() in 1..16)
        val eligible = (0 until array.length()).mapNotNull { n ->
            val item = array.getJSONObject(n)
            if (item.getString("channel") != "stable" || item.getString("variant") != "release") return@mapNotNull null
            val cert = item.getString("signingCertificateSha256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(cert)) { "Invalid signing certificate metadata." }
            if (cert != certificate.lowercase()) return@mapNotNull null
            val min = item.getInt("minSdk"); require(min >= 26)
            if (min > sdk) return@mapNotNull null
            val abiArray = item.getJSONArray("abis"); require(abiArray.length() in 1..8)
            val supported = (0 until abiArray.length()).map { abiArray.getString(it) }.toSet()
            require(supported.all { it in setOf("universal", "armeabi-v7a", "arm64-v8a", "x86", "x86_64") })
            if ("universal" !in supported && supported.intersect(abis).isEmpty()) return@mapNotNull null
            val name = item.getString("assetName")
            require(namePattern.matches(name) && name.endsWith(".apk") && !name.contains("..")) { "Invalid APK asset." }
            val asset = release.assets.singleOrNull { it.name == name } ?: error("The declared APK is missing.")
            val size = item.getLong("size"); require(size in 1..MAX_APK && asset.size == size) { "APK size metadata differs." }
            val sha = item.getString("sha256").lowercase(); require(Regex("[0-9a-f]{64}").matches(sha)) { "Invalid APK checksum." }
            UpdateArtifact(installedId, code, version, release.tag, min, name, size, sha, cert, supported, asset.url,
                json.optString("summary").ifBlank { release.summary }.take(1500))
        }
        require(eligible.size <= 1) { "Multiple eligible APKs; no automatic choice is safe." }
        return eligible.singleOrNull()?.takeIf { code > installedCode }
    }
}

/** Only the recorded, app-owned filenames can ever be cleaned, and success needs installed-version evidence. */
object UpdateCleanup {
    fun owned(name: String) = Regex("update-[1-9][0-9]*-[0-9a-f]{16}\\.apk").matches(name)
    fun installed(pendingId: String, expectedId: String, target: Long, installed: Long, expectedCert: String, installedCert: String): Boolean =
        pendingId == expectedId && target > 0 && installed >= target && expectedCert == installedCert
}
