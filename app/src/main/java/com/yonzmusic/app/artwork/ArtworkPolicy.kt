package com.yonzmusic.app.artwork

import com.yonzmusic.app.search.SearchContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Dimensions are supplied metadata, not an HD claim. The bitmap decoder validates actual pixels. */
data class ArtworkCandidate(val url:String,val width:Int=0,val height:Int=0)
object ArtworkPolicy {
    fun bucket(pixels:Int)=listOf(128,256,512,1024,1536).firstOrNull {it>=pixels} ?: 1536
    fun sample(width:Int,height:Int,target:Int):Int {
        var value=1
        while(minOf(width,height)/(value*2)>=target)value*=2
        return value
    }
    fun ordered(images:List<ArtworkCandidate>,pixels:Int):List<ArtworkCandidate> = images
        .filter {SearchContract.https(it.url,SearchContract.artHosts)}.distinctBy {it.url}
        .sortedWith(compareBy<ArtworkCandidate> {if(minOf(it.width,it.height)>=pixels)0 else 1}
            .thenBy {if(it.width>0 && it.height>0 && maxOf(it.width,it.height).toDouble()/minOf(it.width,it.height)<=1.25)0 else 1}
            .thenBy {if(minOf(it.width,it.height)==0)kotlin.math.abs(maxOf(it.width,it.height)-pixels)else 0}
            .thenBy {if(minOf(it.width,it.height)>=pixels)it.width.toLong()*it.height else -(it.width.toLong()*it.height)})
    fun decode(rows:JSONArray?):List<ArtworkCandidate> = (0 until minOf(rows?.length() ?: 0,16)).mapNotNull {i->
        val row=rows?.optJSONObject(i) ?: return@mapNotNull null
        val url=row.optString("url")
        if(!SearchContract.https(url,SearchContract.artHosts))null else ArtworkCandidate(url,row.optInt("width").coerceIn(0,8192),row.optInt("height").coerceIn(0,8192))
    }.distinctBy {it.url}
    fun encode(images:List<ArtworkCandidate>)=JSONArray(images.take(16).map {JSONObject().put("url",it.url).put("width",it.width).put("height",it.height)})
    fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}
/** This directory contains disposable images only; it never points to SAF audio or download covers. */
class ImageDiskCache(private val directory:File,private val capacity:Long=100L*1024*1024,private val now:()->Long={System.currentTimeMillis()}) {
    init {directory.mkdirs()}
    private fun file(key:String)=File(directory,ArtworkPolicy.hash(key)+".img")
    @Synchronized fun get(key:String):ByteArray? {
        val file=file(key)
        if(!file.isFile || file.length()>4*1024*1024)return null
        return runCatching {file.readBytes().also {file.setLastModified(now())}}.getOrNull()
    }
    @Synchronized fun remove(key:String) {file(key).delete()}
    @Synchronized fun put(key:String,bytes:ByteArray) {
        if(bytes.size>4*1024*1024 || bytes.size>capacity)return
        val target=file(key);val temp=File(directory,target.name+".tmp")
        temp.writeBytes(bytes);check(temp.renameTo(target));target.setLastModified(now())
        val files=directory.listFiles()?.filter {it.name.matches(Regex("[a-f0-9]{64}\\.img"))}.orEmpty()
        var size=files.sumOf {it.length()}
        for(old in files.sortedBy {it.lastModified()}) {if(size<=capacity)break;val bytesOld=old.length();if(old.delete())size-=bytesOld}
    }
    @Synchronized fun clear() {directory.listFiles()?.filter {it.name.matches(Regex("[a-f0-9]{64}\\.img(?:\\.tmp)?"))}?.forEach {it.delete()}}
}
