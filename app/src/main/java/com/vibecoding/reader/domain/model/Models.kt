package com.vibecoding.reader.domain.model

/**
 * 文档格式。
 * - **电子书** [isEbook]：TXT / MD / EPUB（及 DOCX 流式正文）——共用文本阅读设置与分页体系
 * - **版式文档**：PDF —— 独立全屏渲染与设置
 */
enum class BookFormat {
    TXT,
    MD,
    EPUB,
    DOCX,
    PDF;

    /** 电子书：可重排、共用背景/字号/行距/翻页模式 */
    val isEbook: Boolean
        get() = when (this) {
            TXT, MD, EPUB, DOCX -> true
            PDF -> false
        }

    val displayLabel: String
        get() = when (this) {
            TXT -> "TXT"
            MD -> "MD"
            EPUB -> "EPUB"
            DOCX -> "DOCX"
            PDF -> "PDF"
        }

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".txt") -> TXT
                lower.endsWith(".md") || lower.endsWith(".markdown") -> MD
                lower.endsWith(".epub") -> EPUB
                lower.endsWith(".pdf") -> PDF
                lower.endsWith(".docx") -> DOCX
                else -> null
            }
        }

        fun fromMime(mime: String?): BookFormat? = when (mime) {
            "text/plain" -> TXT
            "text/markdown", "text/x-markdown" -> MD
            "application/epub+zip" -> EPUB
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
    val updatedAt: Long = addedAt,
    /** 所属书架文件夹；null 表示在根目录 */
    val folderId: String? = null
)

/**
 * 书架文件夹：在网格中占一格，点开后显示其中的书籍。
 */
data class BookFolder(
    val id: String,
    val name: String,
    val addedAt: Long,
    val updatedAt: Long = addedAt
)

/** 书架网格条目：文件夹或单本书 */
sealed class ShelfItem {
    abstract val sortKey: Long

    data class Folder(
        val folder: BookFolder,
        val bookCount: Int,
        val previewCoverPaths: List<String>
    ) : ShelfItem() {
        override val sortKey: Long get() = folder.updatedAt
    }

    data class BookItem(val book: Book) : ShelfItem() {
        override val sortKey: Long
            get() = if (book.lastOpenedAt == 0L) book.addedAt else book.lastOpenedAt
    }
}

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
    /** 0=卷，1=章；无卷结构时章也可为 0；Markdown 用 level 表示标题层级 */
    val level: Int = 0,
    val kind: TocKind = TocKind.CHAPTER
)

/**
 * 电子书富内容块（含图片）。MD / EPUB 优先用此渲染。
 */
sealed class EbookBlock {
    abstract val charOffset: Int

    data class Heading(
        override val charOffset: Int,
        val level: Int,
        val text: String
    ) : EbookBlock()

    data class Paragraph(
        override val charOffset: Int,
        val text: String
    ) : EbookBlock()

    data class Bullet(
        override val charOffset: Int,
        val text: String
    ) : EbookBlock()

    data class Quote(
        override val charOffset: Int,
        val text: String
    ) : EbookBlock()

    data class Code(
        override val charOffset: Int,
        val text: String
    ) : EbookBlock()

    data class Image(
        override val charOffset: Int,
        /** 本地绝对路径 */
        val path: String,
        val alt: String = ""
    ) : EbookBlock()

    data class Divider(override val charOffset: Int) : EbookBlock()
}

/**
 * 统一电子书加载结果。
 * - [plainText]：进度/书签/左右分页用
 * - [blocks]：富渲染（标题、段落、图片等）；非空时优先展示
 */
data class EbookDocument(
    val plainText: String,
    val toc: List<TocEntry>,
    val title: String? = null,
    val author: String? = null,
    val markdownSource: String? = null,
    val blocks: List<EbookBlock> = emptyList()
) {
    val hasImages: Boolean get() = blocks.any { it is EbookBlock.Image }
}

data class ReadingSettings(
    // —— 电子书（TXT / MD / EPUB / DOCX）——
    val backgroundColor: Long = 0xFFF5F0E6,
    val textColor: Long = 0xFF1A1A1A,
    val fontSizeSp: Float = 20f,
    val lineSpacingMultiplier: Float = 1.6f,
    val pageTurnMode: PageTurnMode = PageTurnMode.VERTICAL,
    val horizontalPaddingDp: Float = 24f,
    val verticalPaddingDp: Float = 20f,
    // —— PDF（与电子书设置分离）——
    val pdfPageTurnMode: PageTurnMode = PageTurnMode.BOTH,
    val pdfBackgroundColor: Long = 0xFF000000
)

/**
 * 统一位置协议：
 * - 电子书：char:{offset}
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
