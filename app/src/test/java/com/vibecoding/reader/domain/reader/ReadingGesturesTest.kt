package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.PageTurnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingGesturesTest {

    @Test
    fun resolveTap_onlyCenterOpensMenu() {
        val w = 900f
        assertEquals(
            ReadingGestures.TapAction.NONE,
            ReadingGestures.resolveTap(100f, w)
        )
        assertEquals(
            ReadingGestures.TapAction.TOGGLE_CHROME,
            ReadingGestures.resolveTap(450f, w)
        )
        assertEquals(
            ReadingGestures.TapAction.NONE,
            ReadingGestures.resolveTap(800f, w)
        )
    }

    @Test
    fun modeFlags_slideOnly() {
        assertTrue(ReadingGestures.allowsSlidePageTurn(PageTurnMode.SLIDE))
        assertTrue(ReadingGestures.allowsSlidePageTurn(PageTurnMode.BOTH))
        assertFalse(ReadingGestures.allowsSlidePageTurn(PageTurnMode.VERTICAL))
        assertFalse(ReadingGestures.allowsSlidePageTurn(PageTurnMode.TAP))
    }

    @Test
    fun resolveHorizontalDrag() {
        assertEquals(
            ReadingGestures.SlideTurn.PREV,
            ReadingGestures.resolveHorizontalDrag(100f)
        )
        assertEquals(
            ReadingGestures.SlideTurn.NEXT,
            ReadingGestures.resolveHorizontalDrag(-100f)
        )
        assertNull(ReadingGestures.resolveHorizontalDrag(10f))
    }
}
