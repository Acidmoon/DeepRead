package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPageTurnTest {

    @Test
    fun clampInterval() {
        assertEquals(2f, AutoPageTurn.clampIntervalSec(0.5f), 0.001f)
        assertEquals(60f, AutoPageTurn.clampIntervalSec(100f), 0.001f)
        assertEquals(8f, AutoPageTurn.clampIntervalSec(8f), 0.001f)
    }

    @Test
    fun clampLines() {
        assertEquals(0.3f, AutoPageTurn.clampLinesPerSec(0.01f), 0.001f)
        assertEquals(6f, AutoPageTurn.clampLinesPerSec(99f), 0.001f)
    }

    @Test
    fun intervalMs() {
        assertEquals(8000L, AutoPageTurn.intervalMs(8f))
        assertTrue(AutoPageTurn.intervalMs(1f) >= 500L)
    }

    @Test
    fun scrollPxPerFrame_positive() {
        val px = AutoPageTurn.scrollPxPerFrame(
            fontSizeSp = 20f,
            lineSpacing = 1.6f,
            density = 2f,
            linesPerSec = 1f,
            frameMs = 16L
        )
        assertTrue(px > 0f)
    }
}
