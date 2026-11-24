package com.vibecoding.reader.data.secure

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPasswordAccessTest {
    @Test
    fun clearPlaintextOnlyWhenVaultHoldsPassword() {
        assertTrue(PdfPasswordAccess.canClearPlaintextColumn(true))
        assertFalse(PdfPasswordAccess.canClearPlaintextColumn(false))
    }
}
