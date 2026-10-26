package com.yonzmusic.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.data.Track
import com.yonzmusic.app.ui.HomeIdentity
import com.yonzmusic.app.ui.YonzTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real production composable; original solid-colour cover fixtures, never added to the library. */
@RunWith(AndroidJUnit4::class)
class HomeCarouselTest {
    @Test fun visibleHomeCrossfadesLocalCoversAndReducedMotionStaysStatic() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<YonzApp>()
        val folder = File(app.cacheDir, "carousel-qa-${System.nanoTime()}").apply { mkdirs() }
        val colours = listOf(Color.rgb(238, 40, 40), Color.rgb(30, 50, 235))
        val tracks = colours.mapIndexed { i, colour ->
            val file = File(folder, "$i.png")
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).let { image ->
                image.eraseColor(colour)
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            Track("carousel-$i", "content://qa/unused/$i", "Original colour fixture $i", "Karisyono QA", artUri = android.net.Uri.fromFile(file).toString())
        }
        fun visibleColour(): Int {
            val image = inst.uiAutomation.takeScreenshot() ?: return -1
            try {
                val hits = IntArray(colours.size)
                for (x in 0 until image.width step 4) for (y in 0 until image.height step 4) {
                    val pixel = image.getPixel(x, y)
                    colours.forEachIndexed { i, colour ->
                        if (kotlin.math.abs(Color.red(pixel) - Color.red(colour)) < 12 &&
                            kotlin.math.abs(Color.green(pixel) - Color.green(colour)) < 12 &&
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(colour)) < 12) hits[i]++
                    }
                }
                return hits.indices.maxByOrNull { hits[it] }?.takeIf { hits[it] > 200 } ?: -1
            } finally { image.recycle() }
        }
        fun awaitColour(expected: Int) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 25000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (visibleColour() == expected) return
                Thread.sleep(200)
            }
            fail("Expected visible local cover $expected")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    YonzTheme("Light") { Column(Modifier.fillMaxSize().safeDrawingPadding()) { HomeIdentity(tracks) } }
                } }
                awaitColour(0)
                awaitColour(1)
                // The decorative component exposes no click target or navigation action.
                fun clicks(node: android.view.accessibility.AccessibilityNodeInfo): Int {
                    val ownClick = if (node.packageName == app.packageName && node.isClickable) 1 else 0
                    return ownClick + (0 until node.childCount).sumOf { index -> node.getChild(index)?.let(::clicks) ?: 0 }
                }
                inst.uiAutomation.rootInActiveWindow?.let { assertEquals(0, clicks(it)) }
                scenario.onActivity { activity -> activity.setContent {
                    key("reduced") { YonzTheme("Light", reduceMotion = true) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) { HomeIdentity(tracks) }
                    } }
                } }
                awaitColour(0)
                Thread.sleep(7500)
                assertEquals("Reduced motion keeps a static cover beyond the rotation interval", 0, visibleColour())
            }
        } finally { folder.deleteRecursively() } // Only this test's private temporary artwork.
    }
}
