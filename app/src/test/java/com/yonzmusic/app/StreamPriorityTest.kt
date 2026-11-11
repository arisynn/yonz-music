package com.yonzmusic.app

import com.yonzmusic.app.playback.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean

class StreamPriorityTest {
    @Test fun backgroundYieldsToCurrentWorkAndPromotesWithoutCancellingTheSameSource() {
        val oldId=StreamWork.currentMediaId;val oldLoading=StreamWork.foregroundLoading;val oldBuffer=StreamWork.buffering
        try {
            StreamWork.currentMediaId="engine:first";StreamWork.foregroundLoading=false;StreamWork.buffering=false
            StreamWork.forSource("engine:next") {
                assertTrue(StreamWork.isSpeculative)
                val callback=StreamRepository.Cancellation(AtomicBoolean(false),Thread.currentThread(),StreamWork.sourceId)
                assertFalse(callback.isCancelled())
                StreamWork.foregroundLoading=true;assertTrue(callback.isCancelled())
                StreamWork.currentMediaId="engine:next";assertFalse(StreamWork.isSpeculative);assertFalse(callback.isCancelled())
            }
            assertNull(StreamWork.sourceId)
        }finally{StreamWork.currentMediaId=oldId;StreamWork.foregroundLoading=oldLoading;StreamWork.buffering=oldBuffer}
    }
    @Test fun speculativeOpenDoesNotMakeItselfUrgentAndScopesUnwindOnFailure() {
        val old=StreamWork.buffering
        try {
            StreamWork.buffering=false;val urgent=StreamWork.urgent
            StreamWork.begin(true);assertEquals(urgent,StreamWork.urgent);StreamWork.end(true)
            try {StreamWork.forSource("next"){error("owned fixture")}}catch(_:IllegalStateException){}
            assertNull(StreamWork.sourceId)
        }finally{StreamWork.buffering=old}
    }
}
