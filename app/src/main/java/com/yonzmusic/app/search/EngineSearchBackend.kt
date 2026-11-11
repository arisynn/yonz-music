package com.yonzmusic.app.search

import android.content.Context
import com.yonzmusic.app.YonzApp

class EngineSearchBackend(context: Context): SearchBackend {
    private val catalog=(context.applicationContext as YonzApp).catalog
    override suspend fun search(provider: SearchProvider, query: String, page: String): SearchPage = catalog.search(query,page)
}
