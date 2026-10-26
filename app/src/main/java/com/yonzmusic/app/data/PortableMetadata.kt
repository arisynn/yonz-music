package com.yonzmusic.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.yonzmusic.app.update.JsonGuard
import org.json.JSONArray
import org.json.JSONObject

/** Untrusted JSON is bound to audio bytes, never a filename, arbitrary URI or imported internal ID. */
class PortableMetadata(private val context: Context) {
    data class Record(val hash: String, val fields: MetadataPolicy.Fields, val duration: Long, val sourceIdentity: String,
        val sourceUrl: String, val artName: String, val artHash: String, val revision: Long)
    fun parse(text: String): Record? = runCatching {
        val root = JsonGuard.parse(text, 128 * 1024)
        require(root.optString("type") == "yono.music.metadata" && root.optInt("schema") == 1)
        val audio = root.getJSONObject("audio"); val meta = root.getJSONObject("metadata")
        val hash = audio.getString("sha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
        val artists = meta.getJSONArray("artists"); require(artists.length() <= 50)
        fun bounded(value: String, max: Int = 512) = value.also { require(it.length <= max && '\u0000' !in it) }
        val names = (0 until artists.length()).map { bounded(artists.getString(it)) }
        val artwork = root.optJSONObject("artwork") ?: JSONObject()
        val artName = bounded(artwork.optString("file_name"),220)
        require(artName.none { it == '/' || it == '\\' } && artName !in setOf(".",".."))
        val artHash = artwork.optString("sha256"); require(artHash.isBlank() || artHash.matches(Regex("[a-f0-9]{64}")))
        val source = bounded(meta.optString("source_url"),4096)
        require(source.isBlank() || Uri.parse(source).scheme == "https")
        Record(hash, MetadataPolicy.Fields(bounded(meta.optString("title")), names.joinToString("; "), bounded(meta.optString("album")),
            if (meta.optString("origin") == "filename") "filename" else "manifest"), audio.optLong("duration_ms").coerceAtLeast(0),
            bounded(meta.optString("source_identity")), source, artName, artHash, root.optLong("revision"))
    }.getOrNull()
    fun read(uri: Uri): Record? = runCatching { context.contentResolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer); if(n<0) break; require(out.size()+n<=128*1024);out.write(buffer,0,n) }
        parse(out.toString("UTF-8"))
    } }.getOrNull()
    fun encode(track: Track, coverName: String = "", coverHash: String = ""): String {
        val source = if(track.sourceIdentity.startsWith("youtube:")) "https://music.youtube.com/watch?v="+track.sourceIdentity.substringAfter(':') else runCatching { Uri.parse(track.sourceUrl).buildUpon().clearQuery().fragment(null).build().toString() }.getOrDefault("")
        return JSONObject().put("type","yono.music.metadata").put("schema",1).put("revision",System.currentTimeMillis())
            .put("audio",JSONObject().put("sha256",track.sha256).put("size",track.fileSize).put("duration_ms",track.duration).put("file_name",track.fileName))
            .put("metadata",JSONObject().put("title",track.title).put("artists",JSONArray(track.artist.split(';').map(String::trim).filter(String::isNotBlank)))
                .put("album",MetadataPolicy.known(track.album)).put("source_identity",track.sourceIdentity).put("source_url",source).put("origin",track.metadataOrigin))
            .put("artwork",JSONObject().put("file_name",coverName).put("sha256",coverHash)).toString(2)
    }
    /** Write only a new companion inside an explicitly writable selected tree. Never overwrite audio/sidecars. */
    fun writeNextTo(track: Track, text: String): Uri? = runCatching {
        val uri=Uri.parse(track.uri); require(DocumentsContract.isTreeUri(uri))
        val id=DocumentsContract.getDocumentId(uri);require('/' in id)
        val parent=DocumentsContract.buildDocumentUriUsingTree(uri,id.substringBeforeLast('/'))
        val root=JSONObject(text)
        if(track.artUri.isNotBlank()) {
            val imageUri=Uri.parse(track.artUri)
            val sameParent=runCatching { imageUri.authority==uri.authority && DocumentsContract.getDocumentId(imageUri).substringBeforeLast('/')==id.substringBeforeLast('/') }.getOrDefault(false)
            if(!sameParent) {
                val bytes=context.contentResolver.openInputStream(imageUri)!!.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=8*1024*1024);out.write(buffer,0,n)};out.toByteArray()
                }
                val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true };android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth>0 && bounds.outHeight>0)
                val mime=bounds.outMimeType ?: "image/jpeg";val ext=when(mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
                val copy=DocumentsContract.createDocument(context.contentResolver,parent,mime,track.fileName+".cover."+ext) ?: error("Tidak dapat menyalin sampul")
                try { context.contentResolver.openOutputStream(copy,"w")!!.use { it.write(bytes) } }
                catch(e:Exception){runCatching { DocumentsContract.deleteDocument(context.contentResolver,copy) };throw e}
                root.put("artwork",JSONObject().put("file_name",displayName(copy)).put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}))
            }
        }
        val created=DocumentsContract.createDocument(context.contentResolver,parent,"application/json",track.fileName+".yono.json") ?: return null
        try { context.contentResolver.openOutputStream(created,"w")!!.use { it.write(root.toString(2).toByteArray()) }; require(read(created)?.hash==track.sha256); created }
        catch(e:Exception) { runCatching { DocumentsContract.deleteDocument(context.contentResolver,created) }; throw e }
    }.getOrNull()
    fun displayName(uri: Uri): String = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else "" }.orEmpty()
}
