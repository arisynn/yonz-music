package com.yonzmusic.app.playback

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Session-local, bounded diagnostics. Only enum names, opaque counters and numeric measurements leave memory. */
object StreamPerformance {
    enum class Stage { TAP, AUTO_TRANSITION, RESOLVER_START, RESOLVER_HIT, RESOLVER_LOAD, PYTHON_INIT, PYTHON_IMPORT,
        YTDLP_EXTRACT, CATALOG_MATCH, LOCAL_LOOKUP, DNS, HTTP_CONNECT, HTTP_HEADERS, FIRST_BYTE,
        OPEN_FIRST_BYTE, STATE_READY, AUDIO_ADVANCING, SEEK_READY, REBUFFER, TRANSFER, CACHE, PRELOAD_CANCEL, UPCOMING_READY }
    enum class Intent { PLAY, NEXT, SEEK, AUTO }
    data class Event(val trace:Long,val stage:Stage,val ms:Long,val bytes:Long=0,val count:Long=0) {
        fun json()=JSONObject().put("schema",1).put("trace",trace).put("event",stage.name.lowercase()).put("ms",ms).put("bytes",bytes).put("count",count).apply {
            if(stage==Stage.CACHE){put("hit_ratio",if(count>0)bytes.toDouble()/count else 0.0);put("miss_bytes",(count-bytes).coerceAtLeast(0))}
            if(stage==Stage.TRANSFER)put("effective_bytes_per_second",bytes*1000.0/ms.coerceAtLeast(1))
        }
    }
    private data class Trace(val number:Long,val started:Long,val intent:Intent,var ready:Boolean=false,var advancing:Boolean=false,var bufferAt:Long=0,var rebufferCount:Long=0)
    private val sequence=AtomicLong()
    private val traces=linkedMapOf<String,Trace>()
    private val events=ArrayDeque<Event>()
    @Synchronized fun begin(id:String,intent:Intent=Intent.PLAY):Long {
        val trace=Trace(sequence.incrementAndGet(),SystemClock.elapsedRealtime(),intent);traces.remove(id);traces[id]=trace
        while(traces.size>32)traces.remove(traces.keys.first())
        record(trace.number,if(intent==Intent.AUTO)Stage.AUTO_TRANSITION else Stage.TAP,0);return trace.number
    }
    @Synchronized fun activate(id:String){if(traces[id]==null || traces[id]?.advancing==true)begin(id,Intent.AUTO)}
    @Synchronized fun alias(from:String,to:String){traces[from]?.let {traces[to]=it}}
    @Synchronized fun number(id:String?):Long=traces[id]?.number ?: 0
    @Synchronized fun mark(id:String?,stage:Stage,ms:Long,bytes:Long=0,count:Long=0)=record(number(id),stage,ms,bytes,count)
    @Synchronized fun sinceTap(id:String?,stage:Stage) {traces[id]?.let {record(it.number,stage,SystemClock.elapsedRealtime()-it.started)}}
    @Synchronized fun ready(id:String?) {traces[id]?.let {t->
        val now=SystemClock.elapsedRealtime()
        if(!t.ready){t.ready=true;record(t.number,if(t.intent==Intent.SEEK)Stage.SEEK_READY else Stage.STATE_READY,now-t.started)}
        if(t.bufferAt>0){record(t.number,Stage.REBUFFER,now-t.bufferAt,count=t.rebufferCount);t.bufferAt=0}
    }}
    @Synchronized fun buffering(id:String?) {traces[id]?.let {if(it.advancing && it.bufferAt==0L){it.bufferAt=SystemClock.elapsedRealtime();it.rebufferCount++}}}
    @Synchronized fun advancing(id:String?) {traces[id]?.let {if(it.ready && !it.advancing){it.advancing=true;record(it.number,Stage.AUDIO_ADVANCING,SystemClock.elapsedRealtime()-it.started)}}}
    @Synchronized fun emit(trace:Long,stage:Stage,ms:Long,bytes:Long=0,count:Long=0)=record(trace,stage,ms,bytes,count)
    @Synchronized private fun record(trace:Long,stage:Stage,ms:Long,bytes:Long=0,count:Long=0) {
        val e=Event(trace,stage,ms.coerceAtLeast(0),bytes.coerceAtLeast(0),count.coerceAtLeast(0))
        if(events.size==512)events.removeFirst();events.addLast(e);if(stage==Stage.AUDIO_ADVANCING)StreamTimings.record("first_audio",e.ms);Log.i("YonoStreamPerf",e.json().toString())
    }
    @Synchronized fun snapshot():List<Event> = events.toList()
    @Synchronized internal fun reset(){traces.clear();events.clear()}
}
