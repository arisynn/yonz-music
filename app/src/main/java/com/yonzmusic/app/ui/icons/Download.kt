package com.yonzmusic.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Original geometric download glyph, matching the rounded 24dp icon family. */
val Icons.Rounded.Download:ImageVector get()=downloadIcon
private val downloadIcon=materialIcon(name="Rounded.YonoDownload") {
    materialPath {
        moveTo(11f,3f);lineTo(13f,3f);lineTo(13f,12.2f);lineTo(16.6f,8.6f);lineTo(18f,10f);lineTo(12f,16f);lineTo(6f,10f);lineTo(7.4f,8.6f);lineTo(11f,12.2f);close()
        moveTo(5f,18f);lineTo(19f,18f);lineTo(19f,20f);lineTo(5f,20f);close()
    }
}
