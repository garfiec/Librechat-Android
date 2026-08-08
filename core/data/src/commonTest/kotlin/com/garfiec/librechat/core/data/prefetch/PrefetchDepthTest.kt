package com.garfiec.librechat.core.data.prefetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A step count that does not divide the range puts the slider on positions the store then snaps
 * away from, and the control jumps under the user's finger.
 */
class PrefetchDepthTest {

    @Test
    fun `the default is what the feature shipped with`() {
        assertEquals(20, PrefetchDepth.DEFAULT)
        assertTrue(PrefetchDepth.DEFAULT in PrefetchDepth.RANGE)
    }

    @Test
    fun `the range divides evenly into steps`() {
        assertEquals(0, (PrefetchDepth.MAX - PrefetchDepth.MIN) % PrefetchDepth.STEP)
        // One slider position per step, minus the two ends.
        val positions = (PrefetchDepth.MAX - PrefetchDepth.MIN) / PrefetchDepth.STEP + 1
        assertEquals(positions - 2, PrefetchDepth.STEPS_BETWEEN_ENDS)
    }

    @Test
    fun `snapping lands on a step`() {
        assertEquals(20, PrefetchDepth.snap(21))
        assertEquals(30, PrefetchDepth.snap(26))
        assertEquals(30, PrefetchDepth.snap(30))
    }

    @Test
    fun `every step is a fixed point of snapping`() {
        var depth = PrefetchDepth.MIN
        while (depth <= PrefetchDepth.MAX) {
            assertEquals(depth, PrefetchDepth.snap(depth))
            depth += PrefetchDepth.STEP
        }
    }

    @Test
    fun `snapping clamps to the range`() {
        assertEquals(PrefetchDepth.MIN, PrefetchDepth.snap(0))
        assertEquals(PrefetchDepth.MIN, PrefetchDepth.snap(-100))
        assertEquals(PrefetchDepth.MAX, PrefetchDepth.snap(10_000))
    }
}
