package com.vibecoding.reader.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 阅读页布局度量：正文安全区与底部状态浮层对齐。
 *
 * 正文底边 = 用户版心边距 + [statusOverlayContentHeight] + 系统导航 inset，
 * 避免竖滑末行 / 分页末行被 [AppBottomStatusBar] 挡住。
 */
object ReadingLayout {
    /**
     * 底部状态浮层内容高度（字号 + 上下 padding），不含系统导航条。
     * 与 [AppBottomStatusBar] 视觉保持一致。
     */
    val statusOverlayContentHeight: Dp = 32.dp

    /** 分页模式页脚（页码行）高度 */
    val pageFooterHeight: Dp = 22.dp
}

/**
 * 阅读正文底部安全 inset：状态浮层 + 系统导航/手势条。
 * 菜单 chrome 可覆盖状态浮层；正文始终为浮层留空。
 */
@Composable
fun rememberReadingBottomSafeInset(): Dp {
    val navBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return remember(navBottom) {
        navBottom + ReadingLayout.statusOverlayContentHeight
    }
}

@Composable
fun rememberReadingBottomSafeInsetPx(): Int {
    val inset = rememberReadingBottomSafeInset()
    val density = LocalDensity.current
    return with(density) { inset.roundToPx() }
}
