package com.yonzmusic.app

import com.yonzmusic.app.playback.ShuffleCycle
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ShuffleCycleTest {
    @Test fun everyQueueOccurrenceIsReachableOnceFromEveryStartingIndex() {
        for (size in 1..40) for (current in 0 until size) for (seed in 0..9) {
            val order = ShuffleCycle.indices(size, current, Random(seed)).toList()
            assertEquals(current, order.first())
            assertEquals((0 until size).toSet(), order.toSet())
            assertEquals(size, order.size)
            if (size > 2) assertNotEquals((0 until size).map { (current + it) % size }, order)
        }
    }
    @Test fun emptySingleAndTwoTrackQueuesAreSafe() {
        assertArrayEquals(intArrayOf(), ShuffleCycle.indices(0, 0))
        assertArrayEquals(intArrayOf(0), ShuffleCycle.indices(1, -1))
        assertArrayEquals(intArrayOf(1, 0), ShuffleCycle.indices(2, 1))
    }
    @Test fun newCyclesCanHaveDifferentOrders() {
        assertTrue((0..20).map { ShuffleCycle.indices(10, 4, Random(it)).toList() }.toSet().size > 10)
    }
}
