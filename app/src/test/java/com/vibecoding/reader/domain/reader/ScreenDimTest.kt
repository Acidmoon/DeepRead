package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDimTest {
    @Test
    fun clampBounds() {
        assertEquals(0f, ScreenDim.clamp(-1f), 1e-5f)
        assertEquals(0.7f, ScreenDim.clamp(1f), 1e-5f)
        assertEquals(0.35f, ScreenDim.clamp(0.35f), 1e-5f)
    }

    @Test
    fun overlayAlphaMatchesClamp() {
        assertEquals(ScreenDim.clamp(0.5f), ScreenDim.overlayAlpha(0.5f), 1e-5f)
    }

    @Test
    fun brightnessPercentRoundTrip() {
        val dim = ScreenDim.fromBrightnessPercent(100)
        assertEquals(0f, dim, 0.02f)
        val dimDark = ScreenDim.fromBrightnessPercent(30)
        assertTrue(dimDark >= 0.65f)
        val mid = ScreenDim.fromBrightnessPercent(65)
        assertTrue(mid > 0f && mid < ScreenDim.MAX)
    }
}
