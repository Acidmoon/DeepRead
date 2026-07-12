package com.vibecoding.reader.domain.reader

import com.vibecoding.reader.domain.model.PageTurnMode

/**
 * 阅读手势统一规则（代码即文档；所有阅读器共用）。
 *
 * ## 点按
 * | 操作 | 行为 |
 * |------|------|
 * | **单击中热区** | 唤起 / 收起菜单 |
 * | 单击左 / 右热区 | 无操作 |
 * | **双击任意位置** | 开启 / 关闭自动翻页 |
 *
 * ## 水平滑动（仅 [PageTurnMode.SLIDE] / [PageTurnMode.BOTH]）
 * | 操作 | 行为 |
 * |------|------|
 * | 右滑超过阈值 | 上一页 |
 * | 左滑超过阈值 | 下一页 |
 *
 * ## 竖滑模式 [PageTurnMode.VERTICAL]
 * | 操作 | 行为 |
 * |------|------|
 * | 上下滚动 | 连续浏览正文 |
 * | 单击中热区 | 菜单 |
 * | 双击 | 自动翻页开关 |
 *
 * ## 热区（先写死）
 * - 中：`[LEFT_ZONE_FRACTION, RIGHT_ZONE_START_FRACTION]`，默认中间 1/3
 */
object ReadingGestures {

    /** 中热区左边界（宽度占比） */
    const val LEFT_ZONE_FRACTION: Float = 1f / 3f

    /** 中热区右边界（宽度占比） */
    const val RIGHT_ZONE_START_FRACTION: Float = 2f / 3f

    /** 水平滑动翻页阈值（像素） */
    const val SLIDE_THRESHOLD_PX: Float = 80f

    enum class TapAction {
        /** 唤起或收起阅读菜单（仅中热区） */
        TOGGLE_CHROME,
        /** 左右区：忽略 */
        NONE
    }

    /**
     * 根据点击横坐标解析动作：仅中间唤菜单。
     */
    fun resolveTap(x: Float, width: Float): TapAction {
        if (width <= 0f) return TapAction.NONE
        val ratio = (x / width).coerceIn(0f, 1f)
        return if (ratio >= LEFT_ZONE_FRACTION && ratio <= RIGHT_ZONE_START_FRACTION) {
            TapAction.TOGGLE_CHROME
        } else {
            TapAction.NONE
        }
    }

    /** 是否允许水平滑动翻页 */
    fun allowsSlidePageTurn(mode: PageTurnMode): Boolean = when (mode) {
        PageTurnMode.SLIDE, PageTurnMode.BOTH -> true
        PageTurnMode.TAP, PageTurnMode.VERTICAL -> false
    }

    /**
     * 水平拖动结束后的翻页方向。
     * @return PREV / NEXT 用字符串区分；null 未达阈值
     */
    fun resolveHorizontalDrag(dragAccum: Float, thresholdPx: Float = SLIDE_THRESHOLD_PX): SlideTurn? {
        return when {
            dragAccum > thresholdPx -> SlideTurn.PREV
            dragAccum < -thresholdPx -> SlideTurn.NEXT
            else -> null
        }
    }

    enum class SlideTurn { PREV, NEXT }
}
