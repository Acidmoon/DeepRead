package com.vibecoding.reader.ui.reader.pdf

import android.graphics.Bitmap
import com.vibecoding.reader.domain.reader.PdfBitmapBudget

/**
 * 持有 PDF 页位图并按预算淘汰引用。
 *
 * 刻意不调用 [Bitmap.recycle]：Compose [androidx.compose.foundation.image.Image]
 * 可能在 unpin / close 的同一帧或退场过程中仍持有该 Bitmap。过早 recycle
 * 会在绘制时出现 “trying to use a recycled bitmap”。预算只从本 store
 * 丢掉引用，真正的回收交给 GC；页已离开组合后，Image 不再引用即可被回收。
 */
class PdfBitmapStore(
    private val budget: PdfBitmapBudget = PdfBitmapBudget()
) {
    private val map = LinkedHashMap<String, Bitmap>()

    @Synchronized
    fun get(key: String): Bitmap? = map[key]?.takeIf { !it.isRecycled }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val evicted = budget.register(key, bitmap.byteCount.toLong())
        dropKeys(evicted.filter { it != key })
        map[key] = bitmap
    }

    @Synchronized
    fun touch(key: String) {
        budget.touch(key)
    }

    @Synchronized
    fun unpin(key: String) {
        budget.unpin(key)
        dropKeys(budget.evictIfNeeded())
    }

    @Synchronized
    fun close() {
        map.clear()
        // 不 recycle：离开阅读页时子组合可能仍短暂持有 Image 引用
    }

    private fun dropKeys(keys: List<String>) {
        for (k in keys) {
            map.remove(k)
        }
    }
}
