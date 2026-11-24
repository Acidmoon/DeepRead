package com.vibecoding.reader.domain.reader

/** 计算封面解码的 inSampleSize，纯逻辑便于单测。 */
object CoverDecode {
    const val DEFAULT_MAX_EDGE = 480

    fun inSampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int = DEFAULT_MAX_EDGE): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val edge = maxOf(srcWidth, srcHeight)
        while (edge / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }
}
