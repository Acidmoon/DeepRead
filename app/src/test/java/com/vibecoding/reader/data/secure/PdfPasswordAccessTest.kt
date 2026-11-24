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

    @Test
    fun includeDatabaseOnlyWhenNoPlaintextRowsRemain() {
        assertTrue(PdfPasswordAccess.includeDatabaseInBackup(0))
        assertFalse(PdfPasswordAccess.includeDatabaseInBackup(1))
        assertFalse(PdfPasswordAccess.includeDatabaseInBackup(3))
    }
}
