package com.vibecoding.reader.domain.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSaveControllerTest {

    @Test
    fun debounceDoesNotPersistImmediately() = runBlocking {
        var saved = mutableListOf<String>()
        val c = ProgressSaveController(debounceMs = 120) { pos, _ -> saved += pos }
        c.onProgress("char:1", 0.1f, this)
        delay(40)
        assertTrue(saved.isEmpty())
        assertTrue(c.hasUnsaved)
        delay(120)
        assertEquals(listOf("char:1"), saved)
        assertFalse(c.hasUnsaved)
    }

    @Test
    fun flushWritesPendingBeforeDebounceFires() = runBlocking {
        var saved = mutableListOf<String>()
        val c = ProgressSaveController(debounceMs = 500) { pos, _ -> saved += pos }
        c.onProgress("char:10", 0.2f, this)
        c.flush()
        assertEquals(listOf("char:10"), saved)
        delay(80)
        assertEquals(listOf("char:10"), saved)
        assertFalse(c.hasUnsaved)
    }

    @Test
    fun latestPositionWinsWhenFlushing() = runBlocking {
        var saved = mutableListOf<Pair<String, Float>>()
        val c = ProgressSaveController(debounceMs = 400) { pos, p -> saved += pos to p }
        c.onProgress("char:1", 0.1f, this)
        c.onProgress("char:2", 0.5f, this)
        c.flush()
        assertEquals(listOf("char:2" to 0.5f), saved)
    }

    @Test
    fun blankPositionIgnored() = runBlocking {
        var saved = 0
        val c = ProgressSaveController(debounceMs = 10) { _, _ -> saved++ }
        c.onProgress("", 0.1f, this)
        c.flush()
        assertEquals(0, saved)
        assertFalse(c.hasUnsaved)
    }

    @Test
    fun samePositionNotWrittenAgain() = runBlocking {
        var saved = 0
        val c = ProgressSaveController(debounceMs = 10) { _, _ -> saved++ }
        c.onProgress("char:1", 0.1f, this)
        c.flush()
        c.onProgress("char:1", 0.1f, this)
        c.flush()
        assertEquals(1, saved)
    }
}
