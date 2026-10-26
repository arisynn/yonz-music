package com.yonzmusic.app

import android.content.Intent
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yonzmusic.app.ui.ProgressFraction
import com.yonzmusic.app.update.UpdateUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateProgressTest {
    @Test fun oldDeferredCalculationReproducesReportedExceptionWhileSnapshotSurvivesTransition() {
        var counters=100L to 100L
        val oldDraw={ (counters.first.toFloat()/counters.second).coerceIn(0f,1f) }
        val frozen=ProgressFraction.of(counters.first,counters.second)!!
        counters=0L to 0L
        try { ProgressBarRangeInfo(oldDraw(),0f..1f);fail("Old deferred division must reproduce the crash") }
        catch(e:IllegalArgumentException){assertTrue(e.message.orEmpty().contains("current must not be NaN"))}
        assertEquals(1f,ProgressBarRangeInfo(frozen,0f..1f).current,0f)
    }
    @Test fun actualUpdaterDialogSurvivesRepeatedCompletedUnknownAndVerifyingFrames() {
        val app=ApplicationProvider.getApplicationContext<YonzApp>()
        val inst=InstrumentationRegistry.getInstrumentation()
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            repeat(40) { n ->
                inst.runOnMainSync {app.updates.ui.value=UpdateUi("Downloading",received=29_879_242,total=29_879_242)}
                Thread.sleep(20)
                inst.runOnMainSync {app.updates.ui.value=UpdateUi(if(n%2==0)"Verifying" else "Downloading")}
                Thread.sleep(20)
                inst.uiAutomation.rootInActiveWindow // Force an actual accessibility/semantics read.
            }
            inst.runOnMainSync {app.updates.ui.value=UpdateUi("Ready","Verified fixture state")}
            inst.waitForIdleSync()
        } finally {inst.runOnMainSync {app.updates.ui.value=UpdateUi()}}
    }
}
