package com.vibecoding.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.vibecoding.reader.domain.model.DefaultThemePresets
import com.vibecoding.reader.domain.model.PageTurnMode
import com.vibecoding.reader.domain.model.ReadingSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsPanel(
    settings: ReadingSettings,
    onChange: (ReadingSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        Text("背景", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DefaultThemePresets.forEach { preset ->
                val selected = settings.backgroundColor == preset.backgroundColor &&
                    settings.textColor == preset.textColor
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(preset.backgroundColor))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else Color.Gray.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable {
                            onChange(
                                settings.copy(
                                    backgroundColor = preset.backgroundColor,
                                    textColor = preset.textColor
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {}
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            DefaultThemePresets.firstOrNull {
                it.backgroundColor == settings.backgroundColor
            }?.name ?: "自定义",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(20.dp))
        Text("字号 ${settings.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = settings.fontSizeSp,
            onValueChange = { onChange(settings.copy(fontSizeSp = it)) },
            valueRange = 14f..32f,
            steps = 17
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "行距 ${"%.1f".format(settings.lineSpacingMultiplier)}",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(
            value = settings.lineSpacingMultiplier,
            onValueChange = { onChange(settings.copy(lineSpacingMultiplier = it)) },
            valueRange = 1.2f..2.4f,
            steps = 11
        )

        Spacer(Modifier.height(12.dp))
        Text("翻页模式", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PageTurnMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.pageTurnMode == mode,
                    onClick = { onChange(settings.copy(pageTurnMode = mode)) },
                    label = {
                        Text(
                            when (mode) {
                                PageTurnMode.TAP -> "点按翻页"
                                PageTurnMode.SLIDE -> "左右滑动"
                                PageTurnMode.BOTH -> "点按+左右滑"
                                PageTurnMode.VERTICAL -> "上下滚动"
                            }
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(settings.backgroundColor))
                .padding(16.dp)
        ) {
            Text(
                "预览：山一程，水一程，身向榆关那畔行。",
                color = Color(settings.textColor),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = TextUnit(settings.fontSizeSp, TextUnitType.Sp),
                    lineHeight = TextUnit(
                        settings.fontSizeSp * settings.lineSpacingMultiplier,
                        TextUnitType.Sp
                    )
                )
            )
        }
    }
}
