package com.vibecoding.reader.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateManifestParserTest {

    private val valid = """
        {
          "schemaVersion": "1.0",
          "versionCode": 9,
          "versionName": "0.2.4",
          "minSupportedVersionCode": 8,
          "apkAssetName": "DeepRead-0.2.4.apk",
          "sha256": "234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84",
          "bytes": 10798209,
          "releaseNotes": ["修复更新检查"],
          "publishedAt": "2026-11-24T16:00:00+08:00",
          "mandatory": false
        }
    """.trimIndent()

    @Test
    fun parsesAndNormalizes() {
        val m = UpdateManifestParser.parse(valid)
        assertEquals(9, m.versionCode)
        assertEquals("0.2.4", m.versionName)
        assertEquals(8, m.minSupportedVersionCode)
        assertEquals("DeepRead-0.2.4.apk", m.apkAssetName)
        assertEquals("234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84", m.sha256)
        assertEquals(10_798_209L, m.bytes)
        assertEquals(listOf("修复更新检查"), m.releaseNotes)
        assertEquals("2026-11-24T08:00:00Z", m.publishedAt)
        assertEquals(false, m.mandatory)
        assertNull(m.apkUrl)
    }

    @Test
    fun rejectsUnknownKeysAndBadValues() {
        reject(valid.replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\""))
        reject(valid.replace("9", "0"))
        reject(valid.replace("\"minSupportedVersionCode\": 8", "\"minSupportedVersionCode\": 10"))
        reject(valid.replace("DeepRead-0.2.4.apk", "../evil.apk"))
        reject(valid.replace("DeepRead-0.2.4.apk", "notes.txt"))
        reject(valid.replace("234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84", "abc"))
        reject(valid.replace("10798209", "0"))
        reject(valid.replace("\"mandatory\": false", "\"mandatory\": \"false\""))
        reject(valid.replace("2026-11-24T16:00:00+08:00", "2026-11-24"))
        reject(valid.dropLast(1) + ", \"extra\": 1}")
        reject("[]")
    }

    @Test
    fun sha256IsCaseNormalized() {
        val upper = valid.replace(
            "234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84",
            "234006E0229C5B4E4724E86085D1F1A80DA55F1611264FE9EC046C500B601A84"
        )
        assertEquals(
            "234006e0229c5b4e4724e86085d1f1a80da55f1611264fe9ec046c500b601a84",
            UpdateManifestParser.parse(upper).sha256
        )
    }

    private fun reject(text: String) {
        try {
            UpdateManifestParser.parse(text)
            fail("should reject: $text")
        } catch (e: UpdateError) {
            assertTrue(e.userMessage.isNotBlank())
        }
    }
}
