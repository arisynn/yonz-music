package com.yonzmusic.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yonzmusic.app.BuildConfig
import com.yonzmusic.app.MainActivity
import com.yonzmusic.app.R
import com.yonzmusic.app.network.SafeHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

data class UpdateUi(val status: String = "Idle", val message: String = "", val artifact: UpdateArtifact? = null, val received: Long = 0, val total: Long = 0)

class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("release-updates", Context.MODE_PRIVATE)
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }
    val ui = MutableStateFlow(UpdateUi())
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile var foreground = false
        private set
    private var offeredThisSession = false
    @Volatile private var interacting = false
    private val installLock = java.util.concurrent.atomic.AtomicBoolean(false)
    fun interaction(active: Boolean) { interacting = active }
    private fun ownedFile(name: String, partial: Boolean = false): File {
        require(UpdateCleanup.owned(name))
        val file = File(directory, name + if (partial) ".part" else "")
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid update cache path." }
        return file
    }
    fun onForeground() {
        foreground = true; offeredThisSession = false
        scope.launch { reconcileInstalled(); if (ui.value.status in setOf("Idle", "Error", "UpToDate")) check(false) }
    }
    fun onBackground() { foreground = false; interacting = false }
    fun resume() { scope.launch { reconcileInstalled(); if (ui.value.status == "Permission" && context.packageManager.canRequestPackageInstalls()) ui.value = ui.value.copy(status = "Ready", message = "Ready for the Android installer.") } }
    @Synchronized fun check(manual: Boolean) {
        if (BuildConfig.DEBUG || context.packageName != "com.yonzmusic.app") {
            if (manual) ui.value = UpdateUi("UpToDate", "Development channel: production updates are disabled.")
            return
        }
        if (checkJob?.isActive == true || downloadJob?.isActive == true || ui.value.status in setOf("Installing", "Permission", "Ready", "Confirming")) return
        checkJob = scope.launch {
            if (System.currentTimeMillis() < prefs.getLong("backoff_until", 0)) {
                if (manual) ui.value = UpdateUi("Error", "GitHub asked us to wait. Try again later.")
                return@launch
            }
            if (manual) ui.value = UpdateUi("Checking", "Checking GitHub Releases…")
            try {
                val response = SafeHttp.get(ReleaseContract.LATEST, ReleaseContract.hosts, etag = prefs.getString("etag", null))
                if (response.status in setOf(403, 429)) {
                    val value = response.retryAfter?.toLongOrNull()
                    val until = if (value != null && value > 1000000000L) value * 1000 else System.currentTimeMillis() + (value ?: 3600) * 1000
                    prefs.edit().putLong("backoff_until", until.coerceIn(System.currentTimeMillis() + 60000, System.currentTimeMillis() + 86400000)).apply()
                    error("GitHub rate limit reached. Try again later.")
                }
                if (response.status == 404) { if (manual) ui.value = UpdateUi("UpToDate", "No stable update is published yet."); return@launch }
                require(response.status in setOf(200, 304)) { "GitHub returned HTTP ${response.status}." }
                val releaseText = if (response.status == 304) prefs.getString("release_json", null) ?: error("Update cache expired. Try again.") else String(response.bytes, Charsets.UTF_8)
                val release = ReleaseContract.release(releaseText)
                val metadata = release.assets.singleOrNull { it.name == "update.json" }
                if (metadata == null) { if (manual) ui.value = UpdateUi("UpToDate", "No compatible signed update is published yet."); return@launch }
                require(metadata.size in 1..65536) { "Update metadata size is invalid." }
                val cachedTag = prefs.getString("metadata_tag", "")
                val metaResponse = SafeHttp.get(metadata.url, ReleaseContract.hosts, 65536, if (cachedTag == release.tag) prefs.getString("metadata_etag", null) else null)
                require(metaResponse.status in setOf(200, 304)) { "Update metadata is unavailable." }
                val metaText = if (metaResponse.status == 304) prefs.getString("metadata_json", null) ?: error("Update cache expired.") else String(metaResponse.bytes, Charsets.UTF_8)
                val artifact = ReleaseContract.select(release, metaText, context.packageName, ApkValidation.installedCode(context), ApkValidation.installedCertificate(context), Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet())
                prefs.edit().putString("release_json", releaseText).putString("etag", response.etag ?: prefs.getString("etag", null))
                    .putString("metadata_tag", release.tag).putString("metadata_json", metaText).putString("metadata_etag", metaResponse.etag ?: prefs.getString("metadata_etag", null)).apply()
                if (artifact != null && (!offeredThisSession || manual)) { offeredThisSession = true; ui.value = UpdateUi("Available", artifact = artifact) }
                else if (manual) ui.value = UpdateUi("UpToDate", "You have the latest compatible stable build.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (manual) ui.value = UpdateUi("Error", "Cannot check for updates. Check your connection and try again later.") }
        }
    }
    fun dismiss() { offeredThisSession = true; if (ui.value.status == "Available") ui.value = UpdateUi() }
    fun cancelDownload() { downloadJob?.cancel(); offeredThisSession = true }
    fun download() {
        val artifact = ui.value.artifact ?: return
        if (ui.value.status != "Available" || downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            val fileName = "update-${artifact.versionCode}-${artifact.sha256.take(16)}.apk"
            require(UpdateCleanup.owned(fileName))
            val partial = ownedFile(fileName, true); val complete = ownedFile(fileName)
            try {
                // One app-owned update at a time. No music/public Downloads path is ever accessed.
                ui.value = UpdateUi("Downloading", artifact = artifact, total = artifact.size)
                prefs.edit().putString("pending", encode(artifact, fileName, "Downloading").toString()).commit()
                SafeHttp.download(artifact.url, ReleaseContract.hosts, partial, ReleaseContract.MAX_APK, artifact.size,
                    progress = { bytes, total -> ui.value = UpdateUi("Downloading", artifact = artifact, received = bytes, total = total) })
                ui.value = UpdateUi("Verifying", artifact = artifact)
                ApkValidation.verify(context, partial, artifact)
                currentCoroutineContext().ensureActive()
                require(partial.renameTo(complete)) { "Could not finalize the APK cache." }
                prefs.edit().putString("pending", encode(artifact, fileName, "Ready").toString()).commit()
                ui.value = UpdateUi("Ready", "Verified APK is ready for Android's installer.", artifact)
                if (interacting) install() else readyNotification("Update ready to install")
            } catch (e: CancellationException) {
                partial.delete(); prefs.edit().remove("pending").commit(); ui.value = UpdateUi("Idle", "Update download cancelled."); throw e
            } catch (_: Exception) {
                partial.delete(); prefs.edit().remove("pending").commit(); ui.value = UpdateUi("Error", "Update failed validation or could not be downloaded. Nothing was installed.")
            }
        }
    }
    private fun encode(a: UpdateArtifact, name: String, status: String): JSONObject = JSONObject().put("package", a.applicationId)
        .put("target", a.versionCode).put("versionName", a.versionName).put("file", name).put("sha256", a.sha256).put("size", a.size)
        .put("certificate", a.certificate).put("minSdk", a.minSdk).put("status", status).put("session", -1).put("created", System.currentTimeMillis())
    private fun pending(): JSONObject? = runCatching { prefs.getString("pending", null)?.let { JsonGuard.parse(it, 8192) } }.getOrNull()
    fun install() {
        if (!interacting) { readyNotification("Update ready to install"); return }
        if (!installLock.compareAndSet(false, true)) return
        scope.launch {
          try {
            val record = pending() ?: return@launch
            if (record.optString("status") == "Downloading") return@launch
            if (!context.packageManager.canRequestPackageInstalls()) { ui.value = ui.value.copy(status = "Permission", message = "Allow Yono Music to open the Android installer for this update. You still confirm installation in Android."); return@launch }
            try {
                val name = record.getString("file"); require(UpdateCleanup.owned(name))
                val file = ownedFile(name)
                val expected = UpdateArtifact(context.packageName, record.getLong("target"), record.getString("versionName"), "", record.getInt("minSdk"), name,
                    record.getLong("size"), record.getString("sha256"), record.getString("certificate"), setOf("universal"), "", "")
                ApkValidation.verify(context, file, expected)
                val installer = context.packageManager.packageInstaller
                val previous = record.optInt("session", -1)
                if (previous >= 0) runCatching { installer.abandonSession(previous) }
                val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName); setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val sessionId = installer.createSession(parameters)
                val nonce = java.util.UUID.randomUUID().toString()
                record.put("session", sessionId).put("nonce", nonce).put("status", "Installing")
                prefs.edit().putString("pending", record.toString()).commit()
                val callback = Intent(context, InstallResultReceiver::class.java).setData(Uri.parse("yono-update://result/$nonce"))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val receiver = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                    if (!interacting) {
                        installer.abandonSession(sessionId); record.put("status", "Ready").put("session", -1)
                        prefs.edit().putString("pending", record.toString()).commit(); readyNotification("Update ready to install"); return@launch
                    }
                    ui.value = ui.value.copy(status = "Installing", message = "Waiting for Android's installation result.")
                    session.commit(receiver.intentSender)
                }
            } catch (_: Exception) { ui.value = ui.value.copy(status = "Ready", message = "The installer could not start. You can retry when ready.") }
          } finally { installLock.set(false) }
        }
    }
    fun installerResult(intent: Intent) {
        val record = pending() ?: return
        if (intent.data?.lastPathSegment != record.optString("nonce") || record.optInt("session", -1) < 0) return
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, record.optInt("session"))
        if (session != record.optInt("session")) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                record.put("status", "Confirming"); prefs.edit().putString("pending", record.toString()).commit()
                ui.value = ui.value.copy(status = "Confirming", message = "Confirm or cancel in Android's installer.")
                if (interacting) runCatching { context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { readyNotification("Confirm update installation", confirmation) }
                else readyNotification("Confirm update installation", confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> scope.launch { reconcileInstalled() }
            else -> {
                record.put("status", "Ready"); prefs.edit().putString("pending", record.toString()).commit()
                ui.value = ui.value.copy(status = "Ready", message = "Installation cancelled or rejected. The installed app and your music are unchanged.")
            }
        }
    }
    suspend fun reconcileInstalled() {
        if (downloadJob?.isActive == true) return
        val record = pending() ?: return
        val name = record.optString("file")
        if (!UpdateCleanup.owned(name)) { prefs.edit().remove("pending").commit(); return }
        if (UpdateCleanup.installed(record.optString("package"), context.packageName, record.optLong("target"), ApkValidation.installedCode(context), record.optString("certificate"), ApkValidation.installedCertificate(context))) {
            ownedFile(name).delete(); ownedFile(name, true).delete()
            prefs.edit().remove("pending").commit(); context.getSystemService(NotificationManager::class.java).cancel(4200)
            ui.value = UpdateUi("UpToDate", "Update installed. Temporary APK cleaned.")
        } else if (record.optString("status") == "Downloading") {
            ownedFile(name, true).delete()
            // The process may have died between the verified rename and the Ready journal write.
            val complete = ownedFile(name)
            val recovered = complete.isFile && runCatching {
                val expected = UpdateArtifact(context.packageName, record.getLong("target"), record.getString("versionName"), "", record.getInt("minSdk"), name,
                    record.getLong("size"), record.getString("sha256"), record.getString("certificate"), setOf("universal"), "", "")
                ApkValidation.verify(context, complete, expected)
            }.isSuccess
            if (recovered) {
                record.put("status", "Ready"); prefs.edit().putString("pending", record.toString()).commit()
                ui.value = UpdateUi("Ready", "Verified update recovered. Tap Install to continue.")
            } else {
                complete.delete(); prefs.edit().remove("pending").commit()
                ui.value = UpdateUi("Idle", "Previous update download was interrupted.")
            }
        } else if (ownedFile(name).isFile && ui.value.status !in setOf("Downloading", "Verifying", "Permission")) {
            ui.value = UpdateUi("Ready", "A previously verified update is ready. Tap Install to continue.")
        }
    }
    fun discard() {
        downloadJob?.cancel()
        scope.launch {
            pending()?.optInt("session", -1)?.takeIf { it >= 0 }?.let { runCatching { context.packageManager.packageInstaller.abandonSession(it) } }
            directory.listFiles().orEmpty().forEach { file ->
                val name = file.name.removeSuffix(".part")
                if (UpdateCleanup.owned(name)) runCatching { ownedFile(name, file.name.endsWith(".part")).delete() }
            }
            prefs.edit().remove("pending").commit(); ui.value = UpdateUi("Idle", "App-owned update cache cleared. Your music is untouched.")
        }
    }
    private fun readyNotification(title: String, confirmation: Intent? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 4200, confirmation ?: Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(4200, NotificationCompat.Builder(context, "app-updates").setSmallIcon(R.drawable.brand_y).setContentTitle(title)
            .setContentText("Tap to continue with Android's confirmation.").setContentIntent(open).setAutoCancel(true).build()) }
    }
}
