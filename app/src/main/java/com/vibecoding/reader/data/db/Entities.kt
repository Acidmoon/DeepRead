package com.vibecoding.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vibecoding.reader.domain.model.Book
import com.vibecoding.reader.domain.model.BookFolder
import com.vibecoding.reader.domain.model.BookFormat
import com.vibecoding.reader.domain.model.Bookmark
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReadingSettings

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val addedAt: Long,
    val updatedAt: Long
) {
    fun toDomain() = BookFolder(
        id = id,
        name = name,
        addedAt = addedAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(folder: BookFolder) = FolderEntity(
            id = folder.id,
            name = folder.name,
            addedAt = folder.addedAt,
            updatedAt = folder.updatedAt
        )
    }
}

@Entity(
    tableName = "books",
    indices = [Index("folderId")]
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val format: String,
    val sourceUri: String,
    val localPath: String,
    val coverPath: String?,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val lastPosition: String,
    val progressPercent: Float,
    val remoteId: String?,
    val updatedAt: Long,
    val folderId: String? = null
) {
    fun toDomain(): Book = Book(
        id = id,
        title = title,
        author = author,
        format = BookFormat.valueOf(format),
        sourceUri = sourceUri,
        localPath = localPath,
        coverPath = coverPath,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        lastPosition = lastPosition,
        progressPercent = progressPercent,
        remoteId = remoteId,
        updatedAt = updatedAt,
        folderId = folderId
    )

    companion object {
        fun fromDomain(book: Book) = BookEntity(
            id = book.id,
            title = book.title,
            author = book.author,
            format = book.format.name,
            sourceUri = book.sourceUri,
            localPath = book.localPath,
            coverPath = book.coverPath,
            addedAt = book.addedAt,
            lastOpenedAt = book.lastOpenedAt,
            lastPosition = book.lastPosition,
            progressPercent = book.progressPercent,
            remoteId = book.remoteId,
            updatedAt = book.updatedAt,
            folderId = book.folderId
        )
    }
}

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val position: String,
    val label: String?,
    val createdAt: Long,
    val remoteId: String?,
    val updatedAt: Long
) {
    fun toDomain(): Bookmark = Bookmark(
        id = id,
        bookId = bookId,
        position = position,
        label = label,
        createdAt = createdAt,
        remoteId = remoteId,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(bookmark: Bookmark) = BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            position = bookmark.position,
            label = bookmark.label,
            createdAt = bookmark.createdAt,
            remoteId = bookmark.remoteId,
            updatedAt = bookmark.updatedAt
        )
    }
}

@Entity(tableName = "reading_settings")
data class ReadingSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val backgroundColor: Long,
    val textColor: Long,
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
    val pageTurnMode: String,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val pdfPageTurnMode: String = PageTurnMode.BOTH.name,
    val pdfBackgroundColor: Long = 0xFF000000,
    val screenDim: Float = 0f,
    val autoPageTurnEnabled: Boolean = false,
    val autoPageIntervalSec: Float = 8f,
    val autoScrollLinesPerSec: Float = 1.2f
) {
    fun toDomain(): ReadingSettings = ReadingSettings(
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSizeSp = fontSizeSp,
        lineSpacingMultiplier = lineSpacingMultiplier,
        pageTurnMode = runCatching { PageTurnMode.valueOf(pageTurnMode) }
            .getOrDefault(PageTurnMode.BOTH),
        horizontalPaddingDp = horizontalPaddingDp,
        verticalPaddingDp = verticalPaddingDp,
        screenDim = screenDim,
        autoPageTurnEnabled = autoPageTurnEnabled,
        autoPageIntervalSec = autoPageIntervalSec,
        autoScrollLinesPerSec = autoScrollLinesPerSec,
        pdfPageTurnMode = runCatching { PageTurnMode.valueOf(pdfPageTurnMode) }
            .getOrDefault(PageTurnMode.BOTH),
        pdfBackgroundColor = pdfBackgroundColor
    )

    companion object {
        fun fromDomain(settings: ReadingSettings) = ReadingSettingsEntity(
            id = 1,
            backgroundColor = settings.backgroundColor,
            textColor = settings.textColor,
            fontSizeSp = settings.fontSizeSp,
            lineSpacingMultiplier = settings.lineSpacingMultiplier,
            pageTurnMode = settings.pageTurnMode.name,
            horizontalPaddingDp = settings.horizontalPaddingDp,
            verticalPaddingDp = settings.verticalPaddingDp,
            pdfPageTurnMode = settings.pdfPageTurnMode.name,
            pdfBackgroundColor = settings.pdfBackgroundColor,
            screenDim = settings.screenDim,
            autoPageTurnEnabled = settings.autoPageTurnEnabled,
            autoPageIntervalSec = settings.autoPageIntervalSec,
            autoScrollLinesPerSec = settings.autoScrollLinesPerSec
        )

        fun default() = fromDomain(ReadingSettings())
    }
}
