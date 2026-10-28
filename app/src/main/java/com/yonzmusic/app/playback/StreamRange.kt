package com.yonzmusic.app.playback

/** Pure HTTP invariants. A seek must never silently consume bytes from a different offset. */
object StreamRange {
    const val MAX_BYTES=512L*1024*1024
    const val MAX_DISCARD=1024L*1024
    data class Slice(val start:Long,val end:Long,val total:Long?) {val length get()=end-start+1}
    fun parse(header:String?,position:Long,length:Long):Slice {
        val match=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(header.orEmpty()) ?: throw StreamFailure(StreamIssue.SEEK)
        val start=match.groupValues[1].toLongOrNull() ?: throw StreamFailure(StreamIssue.SEEK)
        val end=match.groupValues[2].toLongOrNull() ?: throw StreamFailure(StreamIssue.SEEK)
        val total=match.groupValues[3].takeIf {it!="*"}?.toLongOrNull()
        if(start!=position || end<start || end>=MAX_BYTES || (length>=0 && length!=end-start+1) ||
            (match.groupValues[3]!="*" && (total==null || total<=end || total>MAX_BYTES)))throw StreamFailure(StreamIssue.SEEK)
        return Slice(start,end,total)
    }
    fun eof(header:String?,position:Long):Long?=Regex("bytes \\*/([0-9]+)").matchEntire(header.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.takeIf {it==position && it in 0..MAX_BYTES}
    fun cacheMillis(url:String,wallMillis:Long):Long {
        val expiry=runCatching {java.net.URI(url).rawQuery.orEmpty().split('&').firstOrNull {it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()}.getOrNull()
        if(expiry==null)return 120000
        if(expiry !in 0..Long.MAX_VALUE/1000)return 0
        return (expiry*1000-wallMillis-30000).coerceIn(0,120000)
    }
}
