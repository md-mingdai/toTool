package com.mingdai.toTool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit

/** 收藏星标的高亮色（Miuix 配色里没有独立的 tertiary，这里固定用琥珀色）。 */
val FavoriteStarColor = Color(0xFFFFB300)

/** 提示 / 未对准等“警示”状态用的橙色。 */
val WarnColor = Color(0xFFFF9800)

/** 大号数字（秒表、罗盘读数、记分牌等）：以 Miuix 的 title1 为基础放大字号。 */
@Composable
@ReadOnlyComposable
fun displayStyle(size: TextUnit): TextStyle =
    MiuixTheme.textStyles.title1.copy(fontSize = size, lineHeight = size * 1.15f)

/** 主按钮：主题色填充（对应原来的 Material `Button`）。 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
        content = content,
    )
}

/** 次级按钮：灰色填充（对应原来的 Material `OutlinedButton`）。 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

/** 胶囊选择块（对应原来的 Material `FilterChip`）：选中时用主题色填充。 */
@Composable
fun SelectChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    val container = if (selected) colors.primary else colors.secondaryContainer
    val content = if (selected) colors.onPrimary else colors.onSecondaryContainer
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) content else content.copy(alpha = 0.5f),
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 复选框：保留 `checked / onCheckedChange` 的调用习惯（Miuix 的 Checkbox 使用 ToggleableState）。 */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Checkbox(
        state = ToggleableState(checked),
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * Miuix 风格对话框（对应原来的 `AlertDialog`）。
 * 使用 [WindowDialog]（独立窗口），不依赖 Scaffold 的 popupHost，
 * 因此在全屏工具页（没有 Scaffold）里也能正常显示。
 */
@Composable
fun AppDialog(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    confirmText: String? = "确定",
    onConfirm: () -> Unit = onDismiss,
    dismissText: String? = "取消",
    content: @Composable () -> Unit = {},
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column {
            content()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (dismissText != null) {
                    TextButton(
                        text = dismissText,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (confirmText != null) {
                    TextButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/**
 * 日期选择对话框：年 / 月 / 日三列滚轮（Miuix 没有现成的 DatePicker）。
 * 使用 [LocalDate] 直接选择，不涉及时区换算（Material DatePicker 用 UTC 毫秒，东八区会差一天）。
 */
@Composable
fun AppDatePickerDialog(
    show: Boolean,
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    title: String = "选择日期",
) {
    var year by remember(show, initial) { mutableIntStateOf(initial.year) }
    var month by remember(show, initial) { mutableIntStateOf(initial.monthValue) }
    var day by remember(show, initial) { mutableIntStateOf(initial.dayOfMonth) }
    val maxDay = YearMonth.of(year, month).lengthOfMonth()
    val safeDay = day.coerceIn(1, maxDay)

    AppDialog(
        show = show,
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(LocalDate.of(year, month, safeDay)) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = year,
                onValueChange = { year = it },
                range = 1900..2100,
                label = { "${it}年" },
                textStyle = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1.3f),
            )
            NumberPicker(
                value = month,
                onValueChange = { month = it },
                range = 1..12,
                label = { "${it}月" },
                wrapAround = true,
                textStyle = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = safeDay,
                onValueChange = { day = it },
                range = 1..maxDay,
                label = { "${it}日" },
                wrapAround = true,
                textStyle = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 居中的灰色说明文字。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
