package com.vibecoding.reader.domain

import com.vibecoding.reader.domain.model.ReaderPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPositionTest {
    @Test
    fun serializeAndParseChar() {
        val pos = ReaderPosition.CharOffset(12345)
        assertEquals("char:12345", pos.serialize())
        val parsed = ReaderPosition.parse(pos.serialize())
        assertTrue(parsed is ReaderPosition.CharOffset)
        assertEquals(12345, (parsed as ReaderPosition.CharOffset).offset)
    }

    @Test
    fun serializeAndParsePage() {
        val pos = ReaderPosition.PageIndex(7)
        assertEquals("page:7", pos.serialize())
        val parsed = ReaderPosition.parse(pos.serialize())
        assertTrue(parsed is ReaderPosition.PageIndex)
        assertEquals(7, (parsed as ReaderPosition.PageIndex).page)
    }

    @Test
    fun parseInvalid() {
        assertNull(ReaderPosition.parse(null))
        assertNull(ReaderPosition.parse(""))
        assertNull(ReaderPosition.parse("foo:1"))
        assertNull(ReaderPosition.parse("char:abc"))
    }
}
