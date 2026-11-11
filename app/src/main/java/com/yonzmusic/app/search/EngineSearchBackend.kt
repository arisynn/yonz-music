package com.yonzmusic.app.search

import android.content.Context
import org.json.JSONObject

/** Discovery changes only metadata lookup. Playback still resolves the selected stable video ID. */
class EngineSearchBackend(context: Context): SearchBackend {
    private val client=MusicCatalog(context)
    override suspend fun search(provider:SearchProvider,query:String,page:String):SearchPage =
        SearchContract.engine(client.request(JSONObject().put("operation","search").put("query",query)
            .put("page",page.toIntOrNull() ?: 0)).toString())
}
