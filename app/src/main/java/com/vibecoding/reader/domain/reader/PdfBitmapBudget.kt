package com.vibecoding.reader.domain.reader

/**
 * PDF 页位图的字节预算：登记/释放，并在超出上限时给出应淘汰的 LRU 项。
 * 不直接 recycle，由 UI 层处理，避免在组合仍持有时释放。
 */
class PdfBitmapBudget(
    val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    data class Slot(
        val key: String,
        val byteCount: Long,
        val lastUsed: Long,
        val retained: Boolean
    )

    private val slots = LinkedHashMap<String, Slot>()

    fun currentBytes(): Long = slots.values.sumOf { it.byteCount }

    fun size(): Int = slots.size

    /**
     * @return 需要淘汰的 key（已不再 retained 的 LRU），调用方应释放对应位图
     */
    fun register(key: String, byteCount: Long, now: Long = System.nanoTime()): List<String> {
        slots.remove(key)
        slots[key] = Slot(key, byteCount.coerceAtLeast(0L), now, retained = true)
        return evictIfNeeded()
    }

    fun touch(key: String, now: Long = System.nanoTime()) {
        val current = slots[key] ?: return
        slots.remove(key)
        slots[key] = current.copy(lastUsed = now, retained = true)
    }

    /** 组合离开时取消保留，但不立刻淘汰（可能还在预算内作预取缓存）。 */
    fun unpin(key: String) {
        val current = slots[key] ?: return
        slots[key] = current.copy(retained = false)
    }

    fun release(key: String): Boolean = slots.remove(key) != null

    fun evictIfNeeded(): List<String> {
        if (currentBytes() <= maxBytes) return emptyList()
        val evicted = ArrayList<String>()
        val unpinned = slots.values.filter { !it.retained }.sortedBy { it.lastUsed }
        for (slot in unpinned) {
            if (currentBytes() <= maxBytes) break
            slots.remove(slot.key)
            evicted += slot.key
        }
        return evicted
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
    }
}
