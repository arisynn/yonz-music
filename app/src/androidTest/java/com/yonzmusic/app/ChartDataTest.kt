package com.yonzmusic.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yonzmusic.app.search.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartDataTest {
    @Test fun regionsRanksAttributionDatesCacheAndUnavailableFallbackRemainTruthful():Unit=runBlocking {
        val app=ApplicationProvider.getApplicationContext<YonzApp>();val dao=app.repository.dao
        val keys=listOf("charts-v2:daily_videos:ID","charts-v2:daily_videos:ZZ")
        val previous=keys.associateWith {dao.catalogCache(it)};val requests=mutableListOf<String>()
        try {
            app.charts.fixture={_,region->
                requests+=region
                val song=JSONObject().put("video_id","QACH0000001").put("title","Owned chart fixture").put("page","https://www.youtube.com/watch?v=QACH0000001").put("official_status","UNVERIFIED")
                JSONObject().put("ok",true).put("status","available").put("source","QA chart fixture").put("sourceUrl","https://artists.youtube.com/charts/tracks").put("sourceUpdated","2024-01-01").put("country",region)
                    .put("entries",JSONArray().put(JSONObject().put("song",song).put("rank",3).put("movement","up").put("peak",2).put("lastWeek",5))).toString()
            }
            for(region in listOf("ID","ZZ")) {
                val page=app.charts.load(ChartCategory.DAILY_VIDEOS,region,true)
                assertTrue(page.available);assertEquals(region,page.region);assertEquals(3,page.entries.single().rank)
                assertEquals("QA chart fixture",page.source);assertEquals("2024-01-01",page.sourceUpdated)
                assertEquals(2,page.entries.single().peak);assertEquals(5,page.entries.single().lastWeek)
                assertFalse(com.yonzmusic.app.metadata.OfficialFilter.OFFICIAL.accepts(page.entries.single().song))
            }
            assertEquals(listOf("ID","ZZ"),requests)
            assertTrue(app.charts.load(ChartCategory.DAILY_VIDEOS,"ID").cached);assertEquals(2,requests.size)
            app.charts.fixture={_,_->throw java.io.IOException("Owned simulated source outage")}
            val stale=app.charts.load(ChartCategory.DAILY_VIDEOS,"ID",true);assertTrue(stale.stale);assertEquals(3,stale.entries.single().rank)
            for(category in ChartCategory.entries.filter {it.externalOnly}) {val fallback=app.charts.load(category,"ID");assertFalse(fallback.available);assertTrue(fallback.entries.isEmpty());assertTrue(fallback.sourceUrl.startsWith("https://"))}
        }finally {
            app.charts.fixture=null
            previous.forEach {(key,row)->if(row==null)app.database.openHelper.writableDatabase.execSQL("DELETE FROM catalog_cache WHERE identity=?",arrayOf(key))else dao.catalogCache(row)}
        }
    }
}
