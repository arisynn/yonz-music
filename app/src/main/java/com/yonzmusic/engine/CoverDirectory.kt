package com.yonzmusic.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as D

/** Creates only the app's companion directory; never marks the audio directory .nomedia. */
object CoverDirectory {
    fun obtain(resolver: ContentResolver, tree: Uri, parent: Uri): Uri {
        fun children(uri: Uri): List<Triple<String,String,String>> {
            val list=D.buildChildDocumentsUriUsingTree(tree,D.getDocumentId(uri))
            return resolver.query(list,arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE),null,null,null)?.use { c ->
                buildList { while(c.moveToNext()){check(size<100000);add(Triple(c.getString(0),c.getString(1),c.getString(2)))} }
            } ?: error("Folder sampul belum terbaca")
        }
        val matches=children(parent).filter { it.second==".cover" }
        require(matches.size<=1 && matches.all { it.third==D.Document.MIME_TYPE_DIR }) { "Nama .cover sudah digunakan berkas lain" }
        val folder=matches.singleOrNull()?.let { D.buildDocumentUriUsingTree(tree,it.first) }
            ?: D.createDocument(resolver,parent,D.Document.MIME_TYPE_DIR,".cover") ?: error("Folder sampul belum dapat dibuat")
        if(children(folder).none { it.second==".nomedia" }) {
            val marker=D.createDocument(resolver,folder,"application/octet-stream",".nomedia") ?: error("Penanda galeri belum dapat dibuat")
            resolver.openOutputStream(marker,"w")?.use { it.flush() } ?: error("Penanda galeri belum dapat ditulis")
        }
        return folder
    }
}
