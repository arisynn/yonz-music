package com.yonzmusic.app

import com.yonzmusic.app.ui.ProgressFraction
import org.junit.Assert.*
import org.junit.Test

class ProgressFractionTest {
    @Test fun unknownZeroNegativeAndOverflowingCountersRemainSafe() {
        assertNull(ProgressFraction.of(0,0))
        assertNull(ProgressFraction.of(200,-1))
        assertEquals(0f,ProgressFraction.of(Long.MIN_VALUE,100)!!,0f)
        assertEquals(1f,ProgressFraction.of(Long.MAX_VALUE,100)!!,0f)
        assertEquals(.5f,ProgressFraction.of(50,100)!!,0f)
        assertEquals(1f,ProgressFraction.of(Long.MAX_VALUE,Long.MAX_VALUE)!!,0f)
    }
    @Test fun completionToVerificationKeepsAlreadyCapturedDrawValuesFinite() {
        var counters=100L to 100L
        val frozen=ProgressFraction.of(counters.first,counters.second)!!
        val draw={frozen}
        counters=0L to 0L
        assertNull(ProgressFraction.of(counters.first,counters.second))
        assertTrue(draw().isFinite());assertEquals(1f,draw(),0f)
    }
}
