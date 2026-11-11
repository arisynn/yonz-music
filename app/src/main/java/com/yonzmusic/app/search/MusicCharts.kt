package com.yonzmusic.app.search

import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.CatalogCache
import com.yonzmusic.app.network.AndroidNetworkContext
import com.yonzmusic.app.playback.StreamWork
import com.yonzmusic.engine.PythonRuntime
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class ChartCategory(val key:String,val label:String,val description:String,val ttl:Long=6*3600000L) {
    TRENDING("trending","Musik Trending","YouTube Music Explore; bukan ranking chart harian",900000),
    DAILY_VIDEOS("daily_videos","Daily Top Music Videos","Video musik resmi · harian"),
    DAILY_SHORTS("daily_shorts","Daily Top Songs on Shorts","Popularitas penggunaan lagu pada Shorts"),
    WEEKLY_VIDEOS("weekly_videos","Weekly Top Music Videos","Video musik resmi · mingguan",12*3600000),
    WEEKLY_SONGS("weekly_songs","Weekly Top Songs","Gabungan video resmi, lirik dan penggunaan lagu",12*3600000),
    WEEKLY_ARTISTS("weekly_artists","Weekly Top Artists","Keseluruhan katalog artis, termasuk live/remix",12*3600000),
    WEEKLY_SHORTS("weekly_shorts","Weekly Top Songs on Shorts","Penggunaan lagu pada Shorts · mingguan",12*3600000),
    SPOTIFY_50("spotify_50","Spotify Top 50","Indonesia / Global · buka sumber resmi"),
    SPOTIFY_200("spotify_200","Spotify Top 200","Indonesia / Global · buka sumber resmi"),
    SPOTIFY_GENRE("spotify_genre","Spotify Genre Charts","Ketersediaan mengikuti sumber"),
    SPOTIFY_CITY("spotify_city","Spotify City Charts","Popularitas dalam kota"),
    SPOTIFY_PULSE("spotify_pulse","Spotify Local Pulse","Lagu yang unik populer di kota"),
    SPOTIFY_VIRAL("spotify_viral","Spotify Viral Charts","Hanya melalui sumber yang berizin"),
    SEA("sea","Official SEA Charts","Chart nasional resmi · pilih negara"),
    SEA_DOMESTIC("sea_domestic","Official Indonesia Domestic Chart","Lagu domestik Indonesia"),
    NEW_RELEASES("new_releases","Rilisan Baru","Rilisan katalog; status resmi hanya jika terverifikasi",3600000),
    GENRES("genres","Genre Discovery","Jelajah genre; bukan ranking",86400000);
    val externalOnly get()=key.startsWith("spotify_") || this==SEA || this==SEA_DOMESTIC
}
data class ChartEntry(val song:OnlineSong,val rank:Int?=null,val movement:String="",val label:String="",val peak:Int?=null,val lastWeek:Int?=null)
data class ChartPage(val entries:List<ChartEntry> = emptyList(),val items:List<CatalogItem> = emptyList(),val genres:List<Pair<String,String>> = emptyList(),
    val source:String="",val sourceUrl:String="https://artists.youtube.com/charts/tracks",val sourceUpdated:String="",val fetchedAt:Long=0,
    val available:Boolean=false,val cached:Boolean=false,val stale:Boolean=false,val message:String="",val countries:List<String> = emptyList(),val region:String="")

/** One selected category at a time; separate low-priority dispatcher and existing Room cache. */
class MusicCharts(private val app:YonzApp) {
    private val worker=Dispatchers.IO.limitedParallelism(1)
    internal var fixture:(suspend (ChartCategory,String)->String)?=null
    suspend fun load(category:ChartCategory,country:String,force:Boolean=false):ChartPage=withContext(Dispatchers.IO) {
        require(country in regions)
        if(category.externalOnly)return@withContext fallback(category,country)
        val key="charts-v2:${category.key}:$country"
        val cached=app.repository.dao.catalogCache(key)
        val now=System.currentTimeMillis()
        if(!force && cached!=null && now-cached.updated in 0 until category.ttl)return@withContext parse(JSONObject(cached.metadata),cached.updated,true,false)
        try {
            StreamWork.awaitAudio()
            val raw=withTimeoutOrNull(35000) {fixture?.invoke(category,country) ?: runInterruptible(worker) {
                val payload=JSONObject().put("category",category.key).put("country",country)
                val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("network",AndroidNetworkContext.options(app,"https://music.youtube.com"))
                PythonRuntime.module(app,"yono_charts").callAttr("execute",payload.toString(),config.toString(),MusicCatalog.Callback()).toString()
            }} ?: throw java.io.IOException("Chart timeout")
            require(raw.toByteArray().size<=2*1024*1024)
            val root=JSONObject(raw);require(root.optBoolean("ok"))
            val page=parse(root,now,false,false)
            // Do not cache an unavailable response over good rows.
            if(page.available){app.repository.dao.catalogCache(CatalogCache(key,raw,now));app.repository.dao.trimCatalogCache()}
            page
        }catch(e:CancellationException){throw e}catch(_:Exception) {
            if(cached!=null)parse(JSONObject(cached.metadata),cached.updated,true,true)
            else ChartPage(source="YouTube Music",message="Data chart belum dapat diakses. Buka sumber resmi atau coba lagi.")
        }
    }
    suspend fun genre(params:String):List<CatalogItem> = withContext(Dispatchers.IO) {
        require(params.length in 1..500 && params.none {it.isISOControl()})
        StreamWork.awaitAudio()
        val raw=withTimeoutOrNull(35000){runInterruptible(worker) {
            val config=JSONObject().put("job_id",UUID.randomUUID().toString()).put("network",AndroidNetworkContext.options(app,"https://music.youtube.com"))
            PythonRuntime.module(app,"yono_charts").callAttr("execute",JSONObject().put("category","genre_playlists").put("params",params).put("country","ZZ").toString(),config.toString(),MusicCatalog.Callback()).toString()
        }} ?: throw java.io.IOException("Genre timeout")
        require(raw.toByteArray().size<=2*1024*1024);val root=JSONObject(raw);require(root.optBoolean("ok"));parse(root,System.currentTimeMillis(),false,false).items
    }
    companion object {
        // YouTube Help's published supported locations; the adapter can additionally report its options.
        val regions=(listOf("ID","ZZ","MY","SG","PH","TH","VN")+"AR AU AT BE BO BR CA CL CO CR CZ DK DO EC EG SV EE FI FR DE GT HN HK HU IS IN IE IL IT JP KE LU MX NL NZ NI NG NO PA PY PE PL PT RO RU SA RS ZA KR ES SE CH TW TZ TR UG GB UA AE UY US ZW".split(' ')).distinct()
        fun regionName(code:String)=if(code=="ZZ")"Global" else java.util.Locale("",code).getDisplayCountry(java.util.Locale("id"))
        fun fallback(category:ChartCategory,country:String):ChartPage {
            val spotify=category.key.startsWith("spotify_")
            val supported=spotify && country in setOf("ID","ZZ") || category==ChartCategory.SEA_DOMESTIC && country=="ID" || category==ChartCategory.SEA && country in setOf("ID","MY","SG","PH","TH","VN")
            return ChartPage(source=if(spotify)"Spotify Charts" else "Official SEA Charts",sourceUrl=if(spotify)"https://charts.spotify.com/charts/overview/${if(country=="ID")"id" else "global"}" else "https://www.officialseacharts.com/weeklychart",
                message=if(!supported)"Chart ini tidak tersedia untuk wilayah yang dipilih." else "Data chart belum tersedia langsung di aplikasi. Lihat ranking, label, pergerakan dan tanggal di situs sumber.",region=country)
        }
        private fun rank(j:JSONObject,key:String)=j.optInt(key).takeIf {it in 1..1000}
        private fun chartDate(value:String)=value.takeIf {it.matches(Regex("\\d{4}-\\d{2}-\\d{2}(?:/\\d{4}-\\d{2}-\\d{2})?")) && it.split('/').all {day->runCatching {java.time.LocalDate.parse(day)}.isSuccess}}.orEmpty()
        fun parse(root:JSONObject,fetched:Long,cached:Boolean,stale:Boolean):ChartPage {
            fun song(row:JSONObject)=SearchContract.engine(JSONObject().put("ok",true).put("songs",JSONArray().put(row)).toString(),1).songs.firstOrNull()
            val values=root.optJSONArray("entries") ?: JSONArray()
            val entries=(0 until minOf(values.length(),100)).mapNotNull {i->runCatching {val row=values.getJSONObject(i);song(row.getJSONObject("song"))?.let {ChartEntry(it,rank(row,"rank"),row.optString("movement").takeIf {it in setOf("up","down","neutral","new")}.orEmpty(),SearchContract.text(row.optString("label")),rank(row,"peak"),rank(row,"lastWeek"))}}.getOrNull()}.distinctBy {it.song.id}
            val items=root.optJSONArray("items") ?: JSONArray();val countries=root.optJSONArray("countries") ?: JSONArray();val genres=root.optJSONArray("genres") ?: JSONArray()
            val sourceUrl=root.optString("sourceUrl").takeIf {SearchContract.https(it,setOf("artists.youtube.com","music.youtube.com"))} ?: "https://artists.youtube.com/charts/tracks"
            return ChartPage(entries,(0 until minOf(items.length(),100)).mapNotNull {runCatching {DiscoveryRepository.parseItem(items.getJSONObject(it))}.getOrNull()},
                (0 until minOf(genres.length(),100)).mapNotNull {i->genres.optJSONObject(i)?.let {SearchContract.text(it.optString("title")) to it.optString("params").take(500)}?.takeIf {it.first.isNotBlank() && it.second.isNotBlank()}},
                SearchContract.text(root.optString("source")),sourceUrl,chartDate(root.optString("sourceUpdated")),fetched,
                root.optString("status")=="available",cached,stale,SearchContract.text(root.optString("message"),500),
                (0 until countries.length()).map {countries.optString(it)}.filter {it in regions},root.optString("country").takeIf {it in regions}.orEmpty())
        }
    }
}
