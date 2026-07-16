package com.garfiec.librechat.feature.chat.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileUtilsTest {

    @Test
    fun `no downsample when image already within budget`() {
        assertThat(computeInSampleSize(2048, 1536, 2048)).isEqualTo(1)
        assertThat(computeInSampleSize(800, 600, 2048)).isEqualTo(1)
    }

    @Test
    fun `sample size is a power of two that bounds the longest edge`() {
        // 8000x6000 -> /4 = 2000x1500, both <= 2048; /2 would leave 4000 > 2048.
        assertThat(computeInSampleSize(8000, 6000, 2048)).isEqualTo(4)
        // 4096 on the long edge just over budget -> /2 = 2048.
        assertThat(computeInSampleSize(4096, 100, 2048)).isEqualTo(2)
        // 4097 -> /2 = 2048.5 truncates to 2048 which is within budget.
        assertThat(computeInSampleSize(4097, 100, 2048)).isEqualTo(2)
    }

    @Test
    fun `either dimension over budget triggers downsample`() {
        assertThat(computeInSampleSize(100, 5000, 2048)).isEqualTo(4)
    }

    @Test
    fun `degenerate dimensions return one`() {
        assertThat(computeInSampleSize(0, 0, 2048)).isEqualTo(1)
        assertThat(computeInSampleSize(-1, 100, 2048)).isEqualTo(1)
        assertThat(computeInSampleSize(4000, 3000, 0)).isEqualTo(1)
    }
}
