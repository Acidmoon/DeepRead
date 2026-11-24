package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenIntentTest {

    @Test
    fun viewWithDataReturnsUri() {
        assertEquals(
            "content://com.example/doc.pdf",
            ExternalOpenIntent.dataUri(
                ExternalOpenIntent.ACTION_VIEW,
                "content://com.example/doc.pdf"
            )
        )
    }

    @Test
    fun launcherOrEmptyIgnored() {
        assertNull(ExternalOpenIntent.dataUri("android.intent.action.MAIN", null))
        assertNull(ExternalOpenIntent.dataUri(ExternalOpenIntent.ACTION_VIEW, null))
        assertNull(ExternalOpenIntent.dataUri(ExternalOpenIntent.ACTION_VIEW, "  "))
        assertNull(ExternalOpenIntent.dataUri(null, "content://x"))
    }

    @Test
    fun shouldOfferIgnoresDuplicatePending() {
        val uri = "content://com.example/doc.pdf"
        assertTrue(ExternalOpenIntent.shouldOffer(null, uri))
        assertFalse(ExternalOpenIntent.shouldOffer(uri, uri))
        assertTrue(ExternalOpenIntent.shouldOffer(uri, "content://other/file.txt"))
        assertFalse(ExternalOpenIntent.shouldOffer(null, "  "))
    }
}
