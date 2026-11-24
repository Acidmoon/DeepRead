package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverDecodeTest {
    @Test
    fun noDownsampleWhenAlreadySmall() {
        assertEquals(1, CoverDecode.inSampleSize(200, 300, 480))
    }

    @Test
    fun downsamplesLargeCover() {
        assertEquals(4, CoverDecode.inSampleSize(2000, 2800, 480))
        assertEquals(2, CoverDecode.inSampleSize(960, 1280, 480))
    }

    @Test
    fun invalidSizes() {
        assertEquals(1, CoverDecode.inSampleSize(0, 100, 480))
        assertEquals(1, CoverDecode.inSampleSize(100, 100, 0))
    }
}
