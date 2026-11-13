package com.mingdai.toTool.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Desmos 风格数学键盘：上方是可横向滚动的函数 / 符号，下方是 4×5 的数字与运算符。
 * 只负责回调，插入位置和光标由调用方维护。
 */
@Composable
fun MathKeypad(
    onInsert: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onMoveCaret: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("sin(", "cos(", "tan(", "ln(", "log(", "√(", "π", "e", "^", "^2", "AC", "←", "→")
                .forEach { key ->
                    SelectChip(
                        selected = false,
                        onClick = {
                            when (key) {
                                "AC" -> onClear()
                                "←" -> onMoveCaret(-1)
                                "→" -> onMoveCaret(1)
                                else -> onInsert(key)
                            }
                        },
                        label = key,
                    )
                }
        }

        KeypadRow(
            listOf("7", "8", "9", "÷", "⌫"),
            onDigit = onInsert,
            onBackspace = onBackspace,
            equal = null,
        )
        KeypadRow(
            listOf("4", "5", "6", "×", "("),
            onDigit = onInsert,
            onBackspace = onBackspace,
            equal = null,
        )
        KeypadRow(
            listOf("1", "2", "3", "−", ")"),
            onDigit = onInsert,
            onBackspace = onBackspace,
            equal = null,
        )
        KeypadRow(
            listOf("0", ".", "x", "+", "="),
            onDigit = onInsert,
            onBackspace = onBackspace,
            equal = onInsert,
        )
    }
}

@Composable
private fun KeypadRow(
    labels: List<String>,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    equal: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            val accent = label == "="
            KeypadKey(
                label = label,
                modifier = Modifier.weight(1f),
                accent = accent,
                highlight = label in setOf("+", "−", "×", "÷"),
                repeatOnPress = label == "⌫",
                onClick = {
                    when {
                        label == "⌫" -> onBackspace()
                        label == "=" && equal != null -> equal("=")
                        else -> onDigit(label)
                    }
                },
            )
        }
    }
}

@Composable
private fun KeypadKey(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    highlight: Boolean = false,
    repeatOnPress: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val textColor = when {
        accent -> colors.onPrimary
        highlight -> colors.primary
        else -> colors.onSurface
    }
    val keyModifier = modifier.height(46.dp)
    val keyColors = CardDefaults.defaultColors(
        color = if (accent) colors.primary else colors.surfaceContainer,
        contentColor = textColor,
    )
    if (repeatOnPress) {
        Card(
            modifier = keyModifier,
            insideMargin = PaddingValues(0.dp),
            colors = keyColors,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                coroutineScope {
                                    var repeated = false
                                    val repeatJob = launch {
                                        delay(420)
                                        repeated = true
                                        while (isActive) {
                                            onClick()
                                            delay(65)
                                        }
                                    }
                                    val released = tryAwaitRelease()
                                    repeatJob.cancel()
                                    if (released && !repeated) onClick()
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.title4,
                    color = textColor,
                )
            }
        }
    } else {
        Card(
            modifier = keyModifier,
            insideMargin = PaddingValues(0.dp),
            colors = keyColors,
            onClick = onClick,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.title4,
                    color = textColor,
                )
            }
        }
    }
}
