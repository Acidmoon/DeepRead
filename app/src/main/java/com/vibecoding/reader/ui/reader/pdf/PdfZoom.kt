package com.vibecoding.reader.ui.reader.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.unit.IntSize

/**
 * PDF 页面缩放状态：双指捏合缩放、缩放后单指/双指拖动平移。
 *
 * 手势约定（与翻页手势不冲突的关键）：
 * - 1 倍时单指拖动 **不消费事件**，让位给 Pager 翻页 / 列表滚动；
 * - 双指始终消费（捏合缩放 + 双指平移）；
 * - 放大后单指拖动消费事件用于平移，翻页需先缩回 1 倍。
 */
class PdfZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(IntSize.Zero)

    val isZoomed: Boolean get() = scale > 1.01f

    fun onGesture(centroid: Offset, pan: Offset, zoom: Float) {
        val old = scale
        val new = (old * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        // 以捏合中心为锚点缩放：锚点在屏幕上保持不动
        val anchor = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        offset = offset * (new / old) + anchor * (1 - new / old) + pan
        scale = new
        clampOffset()
        if (scale <= MIN_SCALE) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    private fun clampOffset() {
        val maxX = (scale - 1f) * viewport.width / 2f
        val maxY = (scale - 1f) * viewport.height / 2f
        offset = Offset(
            offset.x.coerceIn(-maxX, maxX),
            offset.y.coerceIn(-maxY, maxY)
        )
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}

/** 把缩放/平移手势与图形变换挂到内容容器上 */
fun Modifier.pdfZoomable(state: PdfZoomState): Modifier = this
    .onSizeChanged { state.viewport = it }
    .pointerInput(state) {
        detectPdfZoomGestures(state)
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

private suspend fun PointerInputScope.detectPdfZoomGestures(state: PdfZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            // Initial pass：优先于 Pager / LazyColumn 的滚动手势拿事件；
            // 双指或已放大时消费掉，翻页/滚动即被抑制
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedCount = event.changes.count { it.pressed }
            // 关键分支：1 倍单指不消费，翻页/滚动手势才能生效
            if (pressedCount >= 2 || state.isZoomed) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    state.onGesture(event.calculateCentroid(), pan, zoom)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
