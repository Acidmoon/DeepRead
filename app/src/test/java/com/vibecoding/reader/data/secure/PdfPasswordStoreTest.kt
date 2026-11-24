package com.vibecoding.reader.data.secure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PdfPasswordStoreTest {
    private val reversible = object : PayloadCipher {
        override fun encrypt(plain: String): String = "enc:$plain"
        override fun decrypt(payload: String): String? =
            payload.takeIf { it.startsWith("enc:") }?.removePrefix("enc:")
    }

    @Test
    fun putGetAndRemove() {
        val file = File.createTempFile("pdfpw", ".dat")
        file.delete()
        val store = PdfPasswordStore(file, reversible)
        assertNull(store.get("book-1"))
        store.put("book-1", "secret")
        assertEquals("secret", store.get("book-1"))
        store.put("book-2", "other")
        assertEquals("other", store.get("book-2"))
        store.remove("book-1")
        assertNull(store.get("book-1"))
        assertEquals("other", store.get("book-2"))
        file.delete()
    }
}
