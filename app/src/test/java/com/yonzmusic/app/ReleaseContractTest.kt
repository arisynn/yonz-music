package com.yonzmusic.app

import com.yonzmusic.app.update.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseContractTest {
    private val cert = "a".repeat(64)
    private fun asset(name: String, id: Int, size: Long) = JSONObject().put("name", name).put("id", id).put("size", size)
        .put("browser_download_url", "https://github.com/arisynn/yonz-music/releases/download/v1.2.0/$name")
    private fun releaseJson() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v1.2.0").put("body", "Changes")
        .put("assets", JSONArray().put(asset("update.json", 1, 600)).put(asset("Yono.apk", 2, 2048)).put(asset("debug.apk", 3, 2048)))
    private fun artifact() = JSONObject().put("channel", "stable").put("variant", "release").put("assetName", "Yono.apk").put("size", 2048)
        .put("sha256", "b".repeat(64)).put("signingCertificateSha256", cert).put("minSdk", 26).put("abis", JSONArray().put("universal"))
    private fun metadata() = JSONObject().put("schemaVersion", 1).put("applicationId", "com.yonzmusic.app").put("versionCode", 3).put("versionName", "1.2.0")
        .put("releaseTag", "v1.2.0").put("artifacts", JSONArray().put(artifact()))
    private fun choose(meta: JSONObject = metadata(), code: Long = 2) = ReleaseContract.select(ReleaseContract.release(releaseJson().toString()), meta.toString(), "com.yonzmusic.app", code, cert, 29, setOf("x86_64"))
    @Test fun exactDeclaredArtifactWinsOverOtherApks() { assertEquals("Yono.apk", choose()!!.name) }
    @Test fun equalAndOlderCodesAreNotOffered() { assertNull(choose(code = 3)); assertNull(choose(code = 4)) }
    @Test fun draftAndPrereleaseAreRejected() {
        for (flag in listOf("draft", "prerelease")) assertThrows(IllegalArgumentException::class.java) { ReleaseContract.release(releaseJson().put(flag, true).toString()) }
    }
    @Test fun wrongPackageAndCrossReleaseAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("applicationId", "other.app")) }
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("releaseTag", "v9.9.9")) }
    }
    @Test fun debugUnsignedAndDifferentCertificatesAreNeverEligible() {
        assertNull(choose(metadata().put("artifacts", JSONArray().put(artifact().put("variant", "debug")))))
        assertNull(choose(metadata().put("artifacts", JSONArray().put(artifact().put("signingCertificateSha256", "c".repeat(64))))))
        assertThrows(org.json.JSONException::class.java) { choose(metadata().put("artifacts", JSONArray().put(artifact().apply { remove("signingCertificateSha256") }))) }
    }
    @Test fun unsupportedSdkAndAbiAreNotOffered() {
        assertNull(choose(metadata().put("artifacts", JSONArray().put(artifact().put("minSdk", 35)))))
        assertNull(choose(metadata().put("artifacts", JSONArray().put(artifact().put("abis", JSONArray().put("arm64-v8a"))))))
    }
    @Test fun wrongSizeAndChecksumAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("artifacts", JSONArray().put(artifact().put("size", 999)))) }
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("artifacts", JSONArray().put(artifact().put("sha256", "wrong")))) }
    }
    @Test fun missingAndAmbiguousApksAreRejected() {
        assertThrows(IllegalStateException::class.java) { choose(metadata().put("artifacts", JSONArray().put(artifact().put("assetName", "missing.apk")))) }
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("artifacts", JSONArray().put(artifact()).put(artifact()))) }
    }
    @Test fun unsafeOrCrossRepositoryAssetUrlsAreRejected() {
        val json = releaseJson(); json.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://github.com/other/repo/releases/download/v1.2.0/Yono.apk")
        assertThrows(IllegalArgumentException::class.java) { ReleaseContract.release(json.toString()) }
    }
    @Test fun duplicateApiAssetNamesAreRejected() {
        val json = releaseJson(); json.getJSONArray("assets").put(asset("Yono.apk", 44, 2048))
        assertThrows(IllegalArgumentException::class.java) { ReleaseContract.release(json.toString()) }
    }
    @Test fun deeplyNestedAndNonNumericMetadataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { JsonGuard.parse("{\"x\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}", 65536) }
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("versionCode", "3")) }
        assertThrows(IllegalArgumentException::class.java) { choose(metadata().put("versionCode", 3.5)) }
    }
    @Test fun cancellationAndFailureCannotSatisfyCleanupByThemselves() {
        assertFalse(UpdateCleanup.installed("app", "app", 3, 2, cert, cert))
        assertFalse(UpdateCleanup.installed("other", "app", 3, 3, cert, cert))
        assertFalse(UpdateCleanup.installed("app", "app", 3, 3, cert, "d".repeat(64)))
        assertTrue(UpdateCleanup.installed("app", "app", 3, 3, cert, cert))
    }
    @Test fun cleanupNamesCannotEscapeTheDedicatedDirectory() {
        assertTrue(UpdateCleanup.owned("update-3-${"b".repeat(16)}.apk"))
        listOf("../music.mp3", "/sdcard/Download/app.apk", "update.apk", "update-0-${"b".repeat(16)}.apk").forEach { assertFalse(UpdateCleanup.owned(it)) }
    }
}
