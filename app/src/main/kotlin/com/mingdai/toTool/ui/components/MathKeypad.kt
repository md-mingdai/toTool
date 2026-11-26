package com.mingdai.toTool.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 计算器式数学键盘：函数键、数字键、运算符和底部操作键。
 * 只负责回调，插入位置和光标由调用方维护。
 *
 * @param onSubmit 底部「计算 / 完成」键；各工具按自己的语义处理。
 * @param submitLabel 底部主操作键的文字。
 * @param compact 紧凑模式，用于对话框等高度有限的位置。
 */
@Composable
fun MathKeypad(
    onInsert: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onMoveCaret: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
    submitLabel: String = "计算",
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val keyHeight = if (compact) 38.dp else 46.dp
    val rowGap = if (compact) 4.dp else 6.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        if (expanded) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "ln(" to "ln(",
                    "log(" to "log(",
                    "asin(" to "asin(",
                    "acos(" to "acos(",
                    "atan(" to "atan(",
                    "sinh(" to "sinh(",
                    "cosh(" to "cosh(",
                    "tanh(" to "tanh(",
                    "cbrt(" to "cbrt(",
                    "π" to "π",
                    "e" to "e",
                    "^" to "^",
                    "," to ",",
                ).forEach { (label, insert) ->
                    SelectChip(
                        selected = false,
                        onClick = { onInsert(insert) },
                        label = label,
                    )
                }
                SelectChip(
                    selected = false,
                    onClick = onClear,
                    label = "AC",
                )
            }
        }

        val rows = listOf(
            listOf(
                KeySpec("x²", KeyAction.Insert("^2"), KeyTone.Function),
                KeySpec("√", KeyAction.Insert("√("), KeyTone.Function),
                KeySpec("|x|", KeyAction.Insert("abs("), KeyTone.Function),
                KeySpec("(", KeyAction.Insert("("), KeyTone.Function),
                KeySpec(")", KeyAction.Insert(")"), KeyTone.Function),
                KeySpec("⌫", KeyAction.Backspace, KeyTone.Function, repeat = true),
            ),
            listOf(
                KeySpec("sin", KeyAction.Insert("sin("), KeyTone.Function),
                KeySpec("x°", KeyAction.Insert("°"), KeyTone.Function),
                KeySpec("7", KeyAction.Insert("7"), KeyTone.Digit),
                KeySpec("8", KeyAction.Insert("8"), KeyTone.Digit),
                KeySpec("9", KeyAction.Insert("9"), KeyTone.Digit),
                KeySpec("÷", KeyAction.Insert("÷"), KeyTone.Operator),
            ),
            listOf(
                KeySpec("cos", KeyAction.Insert("cos("), KeyTone.Function),
                KeySpec("30°", KeyAction.Insert("30°"), KeyTone.Function),
                KeySpec("4", KeyAction.Insert("4"), KeyTone.Digit),
                KeySpec("5", KeyAction.Insert("5"), KeyTone.Digit),
                KeySpec("6", KeyAction.Insert("6"), KeyTone.Digit),
                KeySpec("×", KeyAction.Insert("×"), KeyTone.Operator),
            ),
            listOf(
                KeySpec("tan", KeyAction.Insert("tan("), KeyTone.Function),
                KeySpec("45°", KeyAction.Insert("45°"), KeyTone.Function),
                KeySpec("1", KeyAction.Insert("1"), KeyTone.Digit),
                KeySpec("2", KeyAction.Insert("2"), KeyTone.Digit),
                KeySpec("3", KeyAction.Insert("3"), KeyTone.Digit),
                KeySpec("−", KeyAction.Insert("−"), KeyTone.Operator),
            ),
            listOf(
                KeySpec("cot", KeyAction.Insert("cot("), KeyTone.Function),
                KeySpec("60°", KeyAction.Insert("60°"), KeyTone.Function),
                KeySpec("0", KeyAction.Insert("0"), KeyTone.Digit),
                KeySpec(".", KeyAction.Insert("."), KeyTone.Digit),
                KeySpec("=", KeyAction.Insert("="), KeyTone.Operator),
                KeySpec("+", KeyAction.Insert("+"), KeyTone.Operator),
            ),
        )
        rows.forEach { row ->
            KeypadRow(
                keys = row,
                keyHeight = keyHeight,
                compact = compact,
                onAction = { action ->
                    when (action) {
                        is KeyAction.Insert -> onInsert(action.text)
                        KeyAction.Backspace -> onBackspace()
                        is KeyAction.Move -> onMoveCaret(action.delta)
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            MathKey(
                label = if (expanded) "收起" else "更多",
                tone = KeyTone.Utility,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { expanded = !expanded },
            )
            MathKey(
                label = "换行",
                tone = KeyTone.Utility,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { onInsert("\n") },
            )
            MathKey(
                label = "←",
                tone = KeyTone.Utility,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { onMoveCaret(-1) },
            )
            MathKey(
                label = "→",
                tone = KeyTone.Utility,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { onMoveCaret(1) },
            )
            MathKey(
                label = submitLabel,
                tone = KeyTone.Accent,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(2f),
                onClick = onSubmit,
            )
        }
    }
}

private sealed interface KeyAction {
    class Insert(val text: String) : KeyAction
    data object Backspace : KeyAction
    class Move(val delta: Int) : KeyAction
}

private enum class KeyTone { Function, Digit, Operator, Utility, Accent }

private class KeySpec(
    val label: String,
    val action: KeyAction,
    val tone: KeyTone,
    val repeat: Boolean = false,
)

@Composable
private fun KeypadRow(
    keys: List<KeySpec>,
    keyHeight: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    onAction: (KeyAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        keys.forEach { key ->
            MathKey(
                label = key.label,
                tone = key.tone,
                height = keyHeight,
                compact = compact,
                modifier = Modifier.weight(1f),
                repeatOnPress = key.repeat,
                onClick = { onAction(key.action) },
            )
        }
    }
}

@Composable
private fun MathKey(
    label: String,
    tone: KeyTone,
    height: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    modifier: Modifier = Modifier,
    repeatOnPress: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val background = when (tone) {
        KeyTone.Digit -> colors.primary.copy(alpha = 0.10f)
        KeyTone.Accent -> colors.primary
        KeyTone.Utility -> colors.secondaryContainer
        else -> colors.surfaceContainer
    }
    val content = when (tone) {
        KeyTone.Digit, KeyTone.Operator -> colors.primary
        KeyTone.Accent -> colors.onPrimary
        else -> colors.onSurface
    }
    val keyColors = CardDefaults.defaultColors(color = background, contentColor = content)
    val keyModifier = modifier.height(height)
    val style = if (compact || tone == KeyTone.Function) {
        MiuixTheme.textStyles.body2
    } else {
        MiuixTheme.textStyles.title4
    }

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
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, style = style, color = content)
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
                Text(text = label, style = style, color = content)
            }
        }
    }
}
