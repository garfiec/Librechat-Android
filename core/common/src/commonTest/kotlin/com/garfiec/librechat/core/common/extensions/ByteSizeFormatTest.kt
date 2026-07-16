package com.garfiec.librechat.core.common.extensions

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSizeFormatTest {

    @Test
    fun bytesUnderOneKbShowRawBytes() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("512 B", formatByteSize(512))
        assertEquals("1023 B", formatByteSize(1023))
    }

    @Test
    fun wholeUnitsDropTrailingDecimal() {
        assertEquals("1 KB", formatByteSize(1024))
        assertEquals("20 MB", formatByteSize(20L * 1024 * 1024))
        assertEquals("1 GB", formatByteSize(1024L * 1024 * 1024))
    }

    @Test
    fun fractionalUnitsShowOneDecimal() {
        assertEquals("1.5 KB", formatByteSize(1536))
        assertEquals("1.2 MB", formatByteSize((1.2 * 1024 * 1024).toLong()))
    }

    @Test
    fun valueRoundingUpToUnitBoundaryRollsToNextUnit() {
        // ~1023.99 KB must render as "1 MB", never "1024 KB".
        assertEquals("1 MB", formatByteSize(1_048_565))
        // Same at the MB->GB boundary.
        assertEquals("1 GB", formatByteSize(1_073_741_772))
    }

    @Test
    fun justBelowRoundUpStaysInUnit() {
        // 1023.0 KB rounds to itself, stays "1023 KB".
        assertEquals("1023 KB", formatByteSize(1023L * 1024))
    }
}
