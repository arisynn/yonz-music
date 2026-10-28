package com.yonzmusic.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract as D

/** A single hidden sibling of the audio/.cover directories. Never replaces an existing document. */
object MetadataDirectory {
    fun obtain(resolver: ContentResolver, tree: Uri, parent: Uri): Uri {
        val list = D.buildChildDocumentsUriUsingTree(tree, D.getDocumentId(parent))
        val matches = resolver.query(list, arrayOf(D.Document.COLUMN_DOCUMENT_ID,
            D.Document.COLUMN_DISPLAY_NAME, D.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            buildList<Pair<String, String>> {
                var count = 0
                while (c.moveToNext()) {
                    check(count++ < 100000) { "Folder tujuan terlalu besar." }
                    if (c.getString(1) == ".yono") add(c.getString(0) to c.getString(2))
                }
            }
        } ?: error("Folder metadata belum terbaca.")
        require(matches.size <= 1 && matches.all { it.second == D.Document.MIME_TYPE_DIR }) {
            "Nama .yono sudah dipakai berkas lain. Metadata lama tidak ditimpa."
        }
        return matches.singleOrNull()?.let { D.buildDocumentUriUsingTree(tree, it.first) }
            ?: D.createDocument(resolver, parent, D.Document.MIME_TYPE_DIR, ".yono")
            ?: error("Folder metadata belum dapat dibuat.")
    }
}
