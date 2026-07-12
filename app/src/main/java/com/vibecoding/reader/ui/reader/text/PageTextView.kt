package com.vibecoding.reader.ui.reader.text

import android.graphics.Canvas
import android.text.TextPaint
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 用与分页完全相同的 StaticLayout 绘制一页，避免 Compose Text 度量不一致导致底部大块留白。
 */
@Composable
fun PageTextView(
    text: String,
    startOffset: Int,
    endOffset: Int,
    fontSizePx: Float,
    lineSpacingMultiplier: Float,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val paint = remember(fontSizePx, textColor) {
        TextPaginator.createPaint(fontSizePx, textColor.toArgb())
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PageRenderView(context).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        },
        update = { view ->
            view.bind(
                text = text,
                start = startOffset,
                end = endOffset,
                paint = paint,
                lineSpacingMultiplier = lineSpacingMultiplier
            )
        }
    )
}

private class PageRenderView(context: android.content.Context) : View(context) {
    private var text: String = ""
    private var start: Int = 0
    private var end: Int = 0
    private var paint: TextPaint = TextPaginator.createPaint(40f)
    private var lineSpacingMultiplier: Float = 1.6f
    private var layout: android.text.StaticLayout? = null

    fun bind(
        text: String,
        start: Int,
        end: Int,
        paint: TextPaint,
        lineSpacingMultiplier: Float
    ) {
        val changed = this.text !== text ||
            this.start != start ||
            this.end != end ||
            this.paint !== paint ||
            this.lineSpacingMultiplier != lineSpacingMultiplier ||
            layout == null
        this.text = text
        this.start = start
        this.end = end
        this.paint = paint
        this.lineSpacingMultiplier = lineSpacingMultiplier
        if (changed && width > 0) {
            rebuildLayout(width)
            invalidate()
        } else if (changed) {
            layout = null
            requestLayout()
            invalidate()
        }
    }

    private fun rebuildLayout(widthPx: Int) {
        if (text.isEmpty() || widthPx <= 0) {
            layout = null
            return
        }
        layout = TextPaginator.buildLayout(
            text = text,
            start = start,
            end = end,
            widthPx = widthPx,
            paint = paint,
            lineSpacingMultiplier = lineSpacingMultiplier
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) rebuildLayout(w)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        layout?.draw(canvas)
    }
}
