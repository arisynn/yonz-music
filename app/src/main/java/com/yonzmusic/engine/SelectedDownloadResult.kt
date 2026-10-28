package com.yonzmusic.engine

import org.json.JSONObject

/** Only a receipt for the selected recording can establish success. */
object SelectedDownloadResult {
    fun receipt(plan:JSONObject,source:String):JSONObject? {
        val rows=plan.optJSONArray("entries") ?: return null
        return (0 until rows.length()).mapNotNull {rows.optJSONObject(it)}.firstOrNull {
            it.optJSONObject("song")?.optString("youtube_id")==source && it.optString("status") in setOf("downloaded","exists","duplicate") && it.optString("uri").isNotBlank()
        }
    }
    data class Failure(val code:String,val message:String)
    fun failure(plan:JSONObject,source:String):Failure {
        val rows=plan.optJSONArray("entries")
        val row=rows?.let {(0 until it.length()).mapNotNull(it::optJSONObject).firstOrNull {r->r.optJSONObject("song")?.optString("youtube_id")==source || r.optJSONObject("request")?.optJSONObject("song")?.optString("youtube_id")==source}}
        val error=row?.optJSONObject("error") ?: plan.optJSONObject("error")
        val code=error?.optString("code").orEmpty().takeIf {it.matches(Regex("[A-Z0-9_]{1,64}"))} ?: "PUBLICATION_UNVERIFIED"
        val text=when(code) {
            "NETWORK","SOURCE_NETWORK","HTTP_ERROR"->"Sumber tidak dapat dihubungi. Periksa koneksi lalu coba ulang."
            "SOURCE_UNAVAILABLE","UNAVAILABLE","NO_OFFICIAL_MATCH"->"Rekaman pilihan tidak tersedia dari sumber ini."
            "FORMAT_UNAVAILABLE","MERGE_REQUIRED","UNSUPPORTED_CONTAINER","PROBE_REQUIRED"->"Sumber belum menyediakan format audio mandiri yang dapat disimpan tanpa konversi."
            "INVALID_AUDIO","DURATION_MISMATCH","AUDIO_PROCESSING"->"Audio tidak lolos pemeriksaan kelengkapan atau durasi. File belum dinyatakan selesai."
            "STORAGE_COMMIT","METADATA_COMMIT","ARTWORK_COMMIT","SAF_PERMISSION"->"Penyimpanan audio atau metadata belum selesai. Periksa akses folder dan coba ulang."
            "TIMEOUT","PROCESS_TIMEOUT"->"Sumber melewati batas waktu. Coba ulang saat koneksi stabil."
            "CANCELLED","ITEM_CANCELLED"->"Unduhan dibatalkan."
            else->error?.optString("message").orEmpty().replace(Regex("(?:https?|content|file)://\\S+"),"[alamat disembunyikan]")
                .replace(Regex("gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+"),"[rahasia disembunyikan]")
                .filter {!it.isISOControl()}.take(240).ifBlank {"Audio belum berhasil dipublikasikan dan diverifikasi. Coba ulang atau periksa folder tujuan."}
        }
        return Failure(code,text)
    }
}
