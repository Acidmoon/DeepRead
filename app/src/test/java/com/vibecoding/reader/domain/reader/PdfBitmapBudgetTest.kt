package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBitmapBudgetTest {
    @Test
    fun evictsUnpinnedLruWhenOverBudget() {
        val budget = PdfBitmapBudget(maxBytes = 100)
        assertTrue(budget.register("a", 40, now = 1).isEmpty())
        assertTrue(budget.register("b", 40, now = 2).isEmpty())
        budget.unpin("a")
        val evicted = budget.register("c", 40, now = 3)
        assertEquals(listOf("a"), evicted)
        assertEquals(80, budget.currentBytes())
    }

    @Test
    fun doesNotEvictRetainedPages() {
        val budget = PdfBitmapBudget(maxBytes = 50)
        budget.register("visible", 40, now = 1)
        val evicted = budget.register("another", 40, now = 2)
        assertTrue(evicted.isEmpty())
        assertEquals(80, budget.currentBytes())
    }

    @Test
    fun releaseRemovesBytes() {
        val budget = PdfBitmapBudget(maxBytes = 100)
        budget.register("a", 60, now = 1)
        assertTrue(budget.release("a"))
        assertEquals(0, budget.currentBytes())
    }
}
