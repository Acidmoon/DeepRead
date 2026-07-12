package com.vibecoding.reader.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用底部状态浮层：左侧阅读进度，右侧电量 + 日期时间。
 * 无背景条，叠在内容之上；浅底黑字 / 深底白字。
 */
@Composable
fun AppBottomStatusBar(
    progressPercent: Float? = null,
    modifier: Modifier = Modifier,
    onBackground: Color? = null,
    contentColor: Color? = null,
    applyNavPadding: Boolean = true
) {
    val battery = rememberBatteryPercent()
    var nowText by remember { mutableStateOf(formatNow()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowText = formatNow()
            delay(15_000L)
        }
    }

    val fg = contentColor
        ?: onBackground?.let { contrastOn(it) }
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(8f)
            .then(if (applyNavPadding) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = progressPercent?.let {
                "进度 ${(it.coerceIn(0f, 1f) * 100).toInt()}%"
            } ?: "DeepRead",
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = buildString {
                if (battery >= 0) append("电量 $battery%  ")
                else append("电量 --  ")
                append(nowText)
            },
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 浅色背景用近黑字，深色背景用近白字。 */
fun contrastOn(background: Color): Color {
    return if (background.luminance() > 0.5f) {
        Color.Black.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
}

@Composable
private fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(readBatteryPercent(context)) }

    DisposableEffect(context) {
        // 先用 BatteryManager 读一次
        percent = readBatteryPercent(context)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val fromIntent = batteryFromIntent(intent)
                percent = if (fromIntent >= 0) fromIntent else readBatteryPercent(context)
            }
        }
        runCatching {
            registerBatteryReceiver(context, receiver, filter)
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return percent
}

private fun registerBatteryReceiver(
    context: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
    }
}

private fun readBatteryPercent(context: Context): Int {
    // 优先系统服务（比 sticky 广播更稳）
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    if (capacity in 0..100) return capacity

    val intent = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }.getOrNull()
    return if (intent != null) batteryFromIntent(intent) else -1
}

private fun batteryFromIntent(intent: Intent): Int {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return -1
    return ((level * 100f) / scale).toInt().coerceIn(0, 100)
}

private fun formatNow(): String {
    val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
    return fmt.format(Date())
}
