package com.vibecoding.reader.domain.reader

/**
 * 应用内亮度/护眼叠层：将用户调节量映射为半透明黑层 alpha。
 * 不修改系统亮度，避免权限与厂商差异。
 */
object ScreenDim {
    const val MIN = 0f
    const val MAX = 0.7f

    /** 合法变暗量 */
    fun clamp(level: Float): Float = level.coerceIn(MIN, MAX)

    /** 叠层 alpha（0 完全透明） */
    fun overlayAlpha(level: Float): Float = clamp(level)

    /** 滑条 0~100 显示值 */
    fun toPercent(level: Float): Int = ((1f - clamp(level)) * 100f).toInt().coerceIn(30, 100)

    /** 从「亮度百分比」反推 dim（100%=不暗，30%=最暗） */
    fun fromBrightnessPercent(percent: Int): Float {
        val p = percent.coerceIn(30, 100)
        // 100 -> 0, 30 -> 0.7
        return clamp((100 - p) / 70f * MAX)
    }
}
