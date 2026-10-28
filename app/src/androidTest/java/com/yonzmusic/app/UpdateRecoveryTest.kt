package com.yonzmusic.app

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.network.SafeHttp
import com.yonzmusic.app.update.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/** Opt-in real signed higher-code APK fixture; installer UI is exercised separately. */
@RunWith(AndroidJUnit4::class)
class UpdateRecoveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<YonzApp>()
    private fun fixture(): File {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updateGate") == "true")
        return File(app.filesDir, "updater-fixture.apk").also { require(it.isFile) { "Provide the emulator-only higher-code same-key APK fixture first" } }
    }
    private fun artifact(file: File) = UpdateArtifact(app.packageName,
        requireNotNull(InstrumentationRegistry.getArguments().getString("qaTargetCode")).toLong(),
        requireNotNull(InstrumentationRegistry.getArguments().getString("qaTargetName")), "v-test-local-only", 26, "fixture.apk", file.length(),
        SafeHttp.sha256(file), ApkValidation.installedCertificate(app), setOf("arm64-v8a", "x86_64"), "", "")
    private fun rejects(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; assertTrue("Unsafe APK must be rejected", failed) }
    @Test fun actualSignatureAndManifestChecksRejectTamperingAndIncompatibleArtifacts() {
        val file = fixture(); val expected = artifact(file)
        ApkValidation.verify(app, file, expected)
        rejects { ApkValidation.verify(app, file, expected.copy(sha256 = "0".repeat(64))) }
        rejects { ApkValidation.verify(app, file, expected.copy(certificate = "0".repeat(64))) }
        rejects { ApkValidation.verify(app, file, expected.copy(applicationId = "another.application")) }
        rejects { ApkValidation.verify(app, file, expected.copy(versionCode = expected.versionCode + 1)) }
        rejects { ApkValidation.verify(app, file, expected.copy(minSdk = 99)) }
        rejects { ApkValidation.verify(app, file, expected.copy(abis = setOf("arm64-v8a"))) }
        val corrupt = File(app.cacheDir, "corrupt-updater-fixture.apk")
        try {
            file.copyTo(corrupt, overwrite = true)
            RandomAccessFile(corrupt, "rw").use { r -> val position = r.length() / 2; r.seek(position); val value = r.read(); r.seek(position); r.write(value xor 1) }
            // A matching newly-computed hash must not mask the broken APK signature.
            rejects { ApkValidation.verify(app, corrupt, expected.copy(sha256 = SafeHttp.sha256(corrupt))) }
        } finally { corrupt.delete() }
    }
    private fun record(expected: UpdateArtifact, name: String, status: String) = JSONObject().put("package", app.packageName).put("target", expected.versionCode)
        .put("versionName", expected.versionName).put("file", name).put("sha256", expected.sha256).put("size", expected.size)
        .put("certificate", expected.certificate).put("minSdk", 26).put("status", status).put("session", -1).put("created", System.currentTimeMillis())
    @Test fun interruptedVerifiedRenameRecoversButIncompleteOrTamperedCacheIsDiscarded() = runBlocking {
        val file = fixture(); val expected = artifact(file); val name = "update-${expected.versionCode}-${expected.sha256.take(16)}.apk"
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }; val complete = File(directory, name); val partial = File(directory, "$name.part")
        val prefs = app.getSharedPreferences("release-updates", 0); val manager = UpdateManager(app)
        try {
            file.copyTo(complete, overwrite = true); partial.writeText("interrupted")
            prefs.edit().putString("pending", record(expected, name, "Downloading").toString()).commit()
            manager.reconcileInstalled()
            assertEquals("Ready", manager.ui.value.status); assertTrue(complete.isFile); assertFalse(partial.exists())
            assertEquals("Ready", JSONObject(prefs.getString("pending", "")!!).getString("status"))
            assertTrue("Opening/reconciling without replacement must keep the ready APK", complete.exists())
            complete.writeText("not an APK"); partial.writeText("partial")
            prefs.edit().putString("pending", record(expected, name, "Downloading").toString()).commit()
            manager.reconcileInstalled(); assertFalse(complete.exists()); assertFalse(partial.exists()); assertFalse(prefs.contains("pending"))
        } finally { complete.delete(); partial.delete(); prefs.edit().remove("pending").commit() }
    }
    @Test fun forgedCallbackCannotTriggerCleanupAndVerifiedReplacementOnlyRemovesOwnedFiles() = runBlocking {
        val file = fixture(); val expected = artifact(file)
        val name = "update-${expected.versionCode}-${expected.sha256.take(16)}.apk"; val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        val complete = File(directory, name); val partial = File(directory, "$name.part"); val unrelated = File(directory, "keep-unrelated.apk")
        val prefs = app.getSharedPreferences("release-updates", 0); val manager = UpdateManager(app)
        try {
            complete.writeText("owned fixture"); partial.writeText("owned partial"); unrelated.writeText("do not delete")
            val pending = record(expected, name, "Installing").put("session", 123).put("nonce", "expected-private-nonce")
            prefs.edit().putString("pending", pending.toString()).commit()
            manager.installerResult(Intent().setData(Uri.parse("yono-update://result/forged")).putExtra(PackageInstaller.EXTRA_SESSION_ID, 123).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
            manager.reconcileInstalled(); assertTrue(complete.exists()); assertTrue(unrelated.exists()); assertTrue(prefs.contains("pending"))
            // State-transition fixture for a replacement whose installed identity already matches.
            pending.put("target", ApkValidation.installedCode(app)); prefs.edit().putString("pending", pending.toString()).commit()
            manager.reconcileInstalled()
            assertFalse(complete.exists()); assertFalse(partial.exists()); assertFalse(prefs.contains("pending")); assertTrue(unrelated.exists())
            manager.reconcileInstalled(); assertTrue(unrelated.exists()) // idempotent fallback
        } finally { complete.delete(); partial.delete(); unrelated.delete(); prefs.edit().remove("pending").commit() }
    }
}
