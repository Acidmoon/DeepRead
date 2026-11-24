package com.vibecoding.reader.ui.reader.pdf

import android.graphics.Bitmap
import com.vibecoding.reader.domain.reader.PdfBitmapBudget

/**
 * 持有 PDF 页位图并按预算淘汰。unpin 后若超出预算则 recycle。
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
        recycleKeys(evicted.filter { it != key })
        map[key] = bitmap
    }

    @Synchronized
    fun touch(key: String) {
        budget.touch(key)
    }

    @Synchronized
    fun unpin(key: String) {
        budget.unpin(key)
        recycleKeys(budget.evictIfNeeded())
    }

    @Synchronized
    fun close() {
        map.values.forEach { bmp -> runCatching { if (!bmp.isRecycled) bmp.recycle() } }
        map.clear()
    }

    private fun recycleKeys(keys: List<String>) {
        for (k in keys) {
            val bmp = map.remove(k) ?: continue
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
        }
    }
}
