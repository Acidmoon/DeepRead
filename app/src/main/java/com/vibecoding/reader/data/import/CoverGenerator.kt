package com.vibecoding.reader.data.import

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.vibecoding.reader.data.parser.EbookLoader
import com.vibecoding.reader.data.parser.EpubParser
import com.vibecoding.reader.domain.model.BookFormat
import java.io.File
import java.io.FileOutputStream

/**
 * 书架封面生成：
 * - 已有 cover.jpg 则复用
 * - PDF：渲染第一页
 * - EPUB：优先提取内嵌封面，否则正文节选封面
 * - TXT / MD / DOCX：正文节选 + 书页风封面图
 */
object CoverGenerator {

    const val COVER_FILE = "cover.jpg"
    const val EXCERPT_FILE = "cover_excerpt.txt"

    private const val COVER_WIDTH = 480
    private const val COVER_HEIGHT = 680

    data class CoverResult(
        val coverPath: String?,
        val excerpt: String?
    )

    fun coverFile(bookDir: File): File = File(bookDir, COVER_FILE)

    fun excerptFile(bookDir: File): File = File(bookDir, EXCERPT_FILE)

    fun bookDirOf(localPath: String): File? = File(localPath).parentFile

    /**
     * 若封面资源缺失则生成；已存在则直接返回路径。
     */
    fun ensureCover(
        format: BookFormat,
        localFile: File,
        bookDir: File,
        title: String
    ): CoverResult {
        bookDir.mkdirs()
        val cover = coverFile(bookDir)
        val excerptOut = excerptFile(bookDir)

        if (cover.exists() && cover.length() > 0) {
            val excerpt = if (excerptOut.exists()) {
                runCatching { excerptOut.readText() }.getOrNull()
            } else null
            return CoverResult(cover.absolutePath, excerpt)
        }

        return when (format) {
            BookFormat.PDF -> {
                val ok = renderPdfFirstPage(localFile, cover)
                CoverResult(
                    coverPath = if (ok) cover.absolutePath else null,
                    excerpt = null
                )
            }
            BookFormat.EPUB -> {
                val parsed = runCatching { EpubParser.parse(localFile) }.getOrNull()
                val fromEpub = parsed?.coverEntryName?.let { entry ->
                    EpubParser.extractCover(localFile, entry, cover)
                } == true
                if (fromEpub) {
                    val excerpt = buildExcerpt(parsed?.document?.plainText.orEmpty())
                    writeExcerpt(excerptOut, excerpt)
                    CoverResult(cover.absolutePath, excerpt)
                } else {
                    textStyleCover(
                        title = title,
                        text = parsed?.document?.plainText.orEmpty(),
                        cover = cover,
                        excerptOut = excerptOut,
                        format = format
                    )
                }
            }
            BookFormat.TXT, BookFormat.MD, BookFormat.DOCX -> {
                val text = runCatching {
                    EbookLoader.load(format, localFile).plainText
                }.getOrDefault("")
                textStyleCover(title, text, cover, excerptOut, format)
            }
        }
    }

    private fun textStyleCover(
        title: String,
        text: String,
        cover: File,
        excerptOut: File,
        format: BookFormat
    ): CoverResult {
        val excerpt = buildExcerpt(text)
        writeExcerpt(excerptOut, excerpt)
        val ok = renderTextCover(title, excerpt, cover, format)
        return CoverResult(
            coverPath = if (ok) cover.absolutePath else null,
            excerpt = excerpt
        )
    }

    fun loadExcerpt(bookDir: File?): String? {
        if (bookDir == null) return null
        val f = excerptFile(bookDir)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun buildExcerpt(text: String, maxChars: Int = 220): String {
        if (text.isBlank()) return ""
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // 跳过过像目录行的极短标题可保留，正文为主
            .take(16)
            .toList()
        if (lines.isEmpty()) return text.trim().take(maxChars)
        val joined = lines.joinToString("\n")
        return if (joined.length <= maxChars) joined
        else joined.take(maxChars).trimEnd() + "…"
    }

    private fun writeExcerpt(file: File, excerpt: String) {
        runCatching { file.writeText(excerpt) }
    }

    private fun renderPdfFirstPage(pdfFile: File, outFile: File): Boolean {
        return runCatching {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) return false
                    renderer.openPage(0).use { page ->
                        val scale = COVER_WIDTH.toFloat() / page.width.toFloat()
                        val w = COVER_WIDTH
                        val h = (page.height * scale).toInt().coerceIn(1, COVER_HEIGHT * 2)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        // 裁成书架比例画布
                        val cover = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(cover)
                        canvas.drawColor(Color.WHITE)
                        val dstH = minOf(COVER_HEIGHT, h)
                        val src = android.graphics.Rect(0, 0, w, minOf(h, dstH))
                        val dst = android.graphics.Rect(0, 0, COVER_WIDTH, dstH)
                        canvas.drawBitmap(bitmap, src, dst, null)
                        bitmap.recycle()
                        FileOutputStream(outFile).use { fos ->
                            cover.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                        }
                        cover.recycle()
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 为纯文本生成「书页」风格封面：标题 + 正文节选。
     */
    private fun renderTextCover(
        title: String,
        excerpt: String,
        outFile: File,
        format: BookFormat
    ): Boolean {
        return runCatching {
            val bitmap = Bitmap.createBitmap(COVER_WIDTH, COVER_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bg = when (format) {
                BookFormat.TXT -> intArrayOf(0xFFEEF4FF.toInt(), 0xFFD6E6FF.toInt())
                BookFormat.MD -> intArrayOf(0xFFF3EEFF.toInt(), 0xFFE4D9FF.toInt())
                BookFormat.EPUB -> intArrayOf(0xFFFFF1E8.toInt(), 0xFFFFDCC8.toInt())
                BookFormat.DOCX -> intArrayOf(0xFFE8F7FC.toInt(), 0xFFCDECF7.toInt())
                else -> intArrayOf(0xFFF5F5F5.toInt(), 0xFFE0E0E0.toInt())
            }
            val shader = android.graphics.LinearGradient(
                0f, 0f, 0f, COVER_HEIGHT.toFloat(),
                bg[0], bg[1],
                android.graphics.Shader.TileMode.CLAMP
            )
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
            canvas.drawRect(0f, 0f, COVER_WIDTH.toFloat(), COVER_HEIGHT.toFloat(), bgPaint)

            // 内页纸
            val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFCF5.toInt() }
            val left = 28f
            val top = 36f
            val right = COVER_WIDTH - 28f
            val bottom = COVER_HEIGHT - 36f
            canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, paper)

            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (format) {
                    BookFormat.TXT -> 0xFF2563EB.toInt()
                    BookFormat.MD -> 0xFF7C3AED.toInt()
                    BookFormat.EPUB -> 0xFFEA580C.toInt()
                    BookFormat.DOCX -> 0xFF0284C7.toInt()
                    else -> 0xFF64748B.toInt()
                }
            }
            canvas.drawRoundRect(left, top, left + 10f, bottom, 8f, 8f, accent)

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0F172A.toInt()
                textSize = 36f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            val titleLayout = StaticLayout.Builder
                .obtain(title, 0, title.length.coerceAtMost(40), titlePaint, (right - left - 40).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(left + 28f, top + 28f)
            titleLayout.draw(canvas)
            canvas.restore()

            val titleBottom = top + 28f + titleLayout.height + 16f
            val divider = Paint().apply {
                color = 0x332563EB
                strokeWidth = 2f
            }
            canvas.drawLine(left + 28f, titleBottom, right - 24f, titleBottom, divider)

            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF334155.toInt()
                textSize = 22f
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            val bodyText = excerpt.ifBlank { "（暂无正文预览）" }
            val bodyWidth = (right - left - 48).toInt().coerceAtLeast(100)
            val bodyLayout = StaticLayout.Builder
                .obtain(bodyText, 0, bodyText.length, bodyPaint, bodyWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.15f)
                .setMaxLines(14)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(left + 28f, titleBottom + 18f)
            bodyLayout.draw(canvas)
            canvas.restore()

            // 格式角标
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0F172A.toInt() }
            val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val label = format.displayLabel
            val bw = 72f
            val bh = 32f
            val bx = right - bw - 12f
            val by = top + 14f
            canvas.drawRoundRect(bx, by, bx + bw, by + bh, 10f, 10f, badgePaint)
            canvas.drawText(label, bx + bw / 2f, by + bh / 2f + 7f, badgeText)

            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            bitmap.recycle()
            true
        }.getOrDefault(false)
    }
}
