package com.vibecoding.reader.domain.model

enum class BookFormat {
    TXT,
    PDF,
    DOCX;

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".txt") -> TXT
                lower.endsWith(".pdf") -> PDF
                lower.endsWith(".docx") -> DOCX
                else -> null
            }
        }

        fun fromMime(mime: String?): BookFormat? = when (mime) {
            "text/plain" -> TXT
            "application/pdf" -> PDF
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
            else -> null
        }
    }
}

enum class PageTurnMode {
    /** 点按左右区域翻页 */
    TAP,
    /** 左右滑动翻页 */
    SLIDE,
    /** 点按 + 左右滑动 */
    BOTH,
    /** 上下连续滚动 */
    VERTICAL
}

data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val format: BookFormat,
    val sourceUri: String = "",
    val localPath: String,
    val coverPath: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long = 0L,
    val lastPosition: String = "",
    val progressPercent: Float = 0f,
    val remoteId: String? = null,
    val updatedAt: Long = addedAt
)

data class Bookmark(
    val id: String,
    val bookId: String,
    val position: String,
    val label: String? = null,
    val createdAt: Long,
    val remoteId: String? = null,
    val updatedAt: Long = createdAt
)

enum class TocKind {
    /** 卷（如「第一卷」「Volume 1」） */
    VOLUME,
    /** 章（如「第一章」「Chapter 1」） */
    CHAPTER
}

data class TocEntry(
    val title: String,
    val position: String,
    /** 0=卷，1=章；无卷结构时章也可为 0 */
    val level: Int = 0,
    val kind: TocKind = TocKind.CHAPTER
)

data class ReadingSettings(
    // —— 文本（TXT / DOCX）——
    val backgroundColor: Long = 0xFFF5F0E6,
    val textColor: Long = 0xFF1A1A1A,
    val fontSizeSp: Float = 20f,
    val lineSpacingMultiplier: Float = 1.6f,
    val pageTurnMode: PageTurnMode = PageTurnMode.BOTH,
    val horizontalPaddingDp: Float = 24f,
    val verticalPaddingDp: Float = 20f,
    // —— PDF（与文本设置分离）——
    /** PDF 翻页：左右类模式或上下滚动 */
    val pdfPageTurnMode: PageTurnMode = PageTurnMode.BOTH,
    /** PDF 页外底色（全屏留边） */
    val pdfBackgroundColor: Long = 0xFF000000
)

/**
 * 统一位置协议：
 * - 文本：char:{offset}
 * - PDF：page:{index0}
 */
sealed class ReaderPosition {
    data class CharOffset(val offset: Int) : ReaderPosition() {
        override fun serialize(): String = "char:$offset"
    }

    data class PageIndex(val page: Int) : ReaderPosition() {
        override fun serialize(): String = "page:$page"
    }

    abstract fun serialize(): String

    companion object {
        fun parse(raw: String?): ReaderPosition? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(":", limit = 2)
            if (parts.size != 2) return null
            val value = parts[1].toIntOrNull() ?: return null
            return when (parts[0]) {
                "char" -> CharOffset(value.coerceAtLeast(0))
                "page" -> PageIndex(value.coerceAtLeast(0))
                else -> null
            }
        }
    }
}

data class ThemePreset(
    val name: String,
    val backgroundColor: Long,
    val textColor: Long
)

val DefaultThemePresets = listOf(
    ThemePreset("羊皮纸", 0xFFF5F0E6, 0xFF1A1A1A),
    ThemePreset("护眼绿", 0xFFC7EDCC, 0xFF1A1A1A),
    ThemePreset("日间白", 0xFFFAFAFA, 0xFF212121),
    ThemePreset("夜间黑", 0xFF121212, 0xFFE0E0E0),
    ThemePreset("深灰", 0xFF1E1E1E, 0xFFD0D0D0)
)
