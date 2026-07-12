package com.vibecoding.reader.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 隐藏系统顶部状态栏（平板状态栏），保留下滑临时唤出。
 * 在 Activity 层 + 每次重组/ON_RESUME 重复应用，避免 OEM 或导航切换后被恢复。
 */
@Composable
fun HideSystemStatusBar(
    hideStatusBar: Boolean = true,
    hideNavigationBar: Boolean = false
) {
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context.findActivity()
    val window = activity?.window

    fun apply(controller: WindowInsetsControllerCompat) {
        WindowCompat.setDecorFitsSystemWindows(window ?: return, false)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideNavigationBar) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            // 不主动 show 导航栏，避免打断手势导航用户偏好
        }
    }

    // 每次重组都再应用一次（例如从设置页返回后）
    SideEffect {
        if (window == null) return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        apply(controller)
    }

    DisposableEffect(hideStatusBar, hideNavigationBar, view, window, lifecycleOwner) {
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            apply(controller)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                    apply(controller)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // 不在 dispose 时强制 show 状态栏：全应用沉浸，避免页面切换时闪一下
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
}

/** Compose 的 LocalView.context 常是 ContextThemeWrapper，不能直接 as Activity。 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Window.applyImmersiveStatusBarHidden() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    val controller = WindowCompat.getInsetsController(this, decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.statusBars())
}
