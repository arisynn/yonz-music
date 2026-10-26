package com.yonzmusic.app

import com.yonzmusic.app.network.SpotifyPkce
import org.junit.Assert.*
import org.junit.Test

class SpotifyPkceTest {
    @Test fun rfc7636S256Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",SpotifyPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun strongUniqueUrlSafeVerifierAndState() {
        val values=(1..100).map {SpotifyPkce.random()}
        assertEquals(100,values.toSet().size)
        assertTrue(values.all {it.matches(Regex("[A-Za-z0-9_-]{43,128}"))})
        assertTrue(SpotifyPkce.validState(values[0],values[0]))
        assertFalse(SpotifyPkce.validState(values[0],values[1]))
        assertFalse(SpotifyPkce.validState("",""))
        assertFalse(SpotifyPkce.validState(values[0],""))
    }
}
