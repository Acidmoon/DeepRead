package com.vibecoding.reader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
