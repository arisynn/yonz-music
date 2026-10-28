package com.yonzmusic.app.download

import android.content.Context
import com.yonzmusic.app.BuildConfig
import com.yonzmusic.app.data.DownloadJob
import com.yonzmusic.app.update.JsonGuard
import org.json.JSONObject
import java.io.File

/** A typed Kotlin→Python library bridge. This never runs the Termux CLI or a user-supplied command. */
object EmbeddedEngine {
    interface Callback {
        fun cancelled(): Boolean
        fun progress(received: Long, total: Long)
    }
    private fun module(context: Context): com.chaquo.python.PyObject {
        return com.yonzmusic.engine.PythonRuntime.module(context,"yono_engine")
    }
    fun quickJs(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libqjs.so").takeIf { it.isFile }?.absolutePath.orEmpty()
    private fun transport(context: Context, url: String, test: JSONObject?): String {
        require(test == null || BuildConfig.DEBUG) { "Test transport is unavailable in release builds." }
        val options = com.yonzmusic.app.network.AndroidNetworkContext.options(context, url)
        test?.keys()?.forEach { options.put(it, test.get(it)) }
        return options.toString()
    }
    fun inspect(context: Context, url: String, servicePermission: Boolean, test: JSONObject? = null): AudioCandidate {
        val result = module(context).callAttr("inspect", url, servicePermission, quickJs(context), transport(context, url, test)).toString()
        val json = JsonGuard.parse(result, 32000)
        return AudioCandidate(json.getString("id"), json.getString("title"), json.getString("artist"), json.optString("album"),
            json.optLong("duration"), json.optLong("size"), url, json.optString("license"), json.optString("licenseUrl"),
            json.optString("page"), json.optString("description"), json.optString("artUrl"))
    }
    fun transfer(context: Context, job: DownloadJob, workspace: File, servicePermission: Boolean, callback: Callback, test: JSONObject? = null): File {
        val output = module(context).callAttr("transfer", job.url, workspace.absolutePath, servicePermission, callback, quickJs(context), transport(context, job.url, test)).toString()
        val record = JsonGuard.parse(output, 32000)
        val file = File(record.getString("path")).canonicalFile
        require(file.parentFile == workspace.canonicalFile && file.isFile) { "Engine output escaped the private workspace." }
        val metadata = record.getJSONObject("metadata")
        require(metadata.getString("id") == job.sourceId) { "Source identity changed. Analyze it again." }
        if (job.expectedDuration > 0 && metadata.optLong("duration") > 0) {
            require(kotlin.math.abs(job.expectedDuration - metadata.optLong("duration")) <= maxOf(2500, (job.expectedDuration * .015).toLong())) { "Source duration changed." }
        }
        return file
    }
    fun tags(context: Context, file: File, job: DownloadJob, artwork: File?): Boolean {
        val metadata = JSONObject().put("title", job.title).put("artist", job.artist).put("album", job.album)
            .put("sourceId", job.sourceId).put("url", job.url).put("license", "${job.license} ${job.licenseUrl}")
        val result = module(context).callAttr("tags", file.absolutePath, metadata.toString(), artwork?.absolutePath.orEmpty()).toString()
        return JSONObject(result).optBoolean("embedded")
    }
    fun runtimeReport(context: Context): String = module(context).callAttr("runtime_report", quickJs(context)).toString()
    fun artwork(context: Context, url: String, target: File, servicePermission: Boolean): File {
        module(context).callAttr("artwork", url, target.absolutePath, servicePermission, quickJs(context), transport(context, url, null))
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(target.absolutePath, options)
        require(options.outWidth in 1..4096 && options.outHeight in 1..4096) { "Artwork is not a bounded valid image." }
        var sample = 1
        while (options.outWidth / sample > 1024 || options.outHeight / sample > 1024) sample *= 2
        val bitmap = android.graphics.BitmapFactory.decodeFile(target.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Artwork could not be decoded.")
        try { target.outputStream().use { require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)) } } finally { bitmap.recycle() }
        require(target.length() <= 1024 * 1024) { "Artwork exceeds 1 MB." }
        return target
    }
}
