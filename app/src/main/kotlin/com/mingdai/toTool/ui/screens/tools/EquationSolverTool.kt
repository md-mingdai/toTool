package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.math.AngleMode
import com.mingdai.toTool.math.EquationSolution
import com.mingdai.toTool.math.ExactEquationResult
import com.mingdai.toTool.math.ExactSolveException
import com.mingdai.toTool.math.MathParseException
import com.mingdai.toTool.math.NotPolynomialException
import com.mingdai.toTool.math.formatNumber
import com.mingdai.toTool.math.solveEquation
import com.mingdai.toTool.math.solvePolynomialEquation
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.MathKeypad
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val INITIAL_EQUATION = "x^2 - 5x + 6 = 0"

private val equationExamples = listOf(
    "x^2 = 4",
    "x^2 = 2",
    "x^2 + x + 1 = 0",
    "(x-1)(x+2) = 0",
    "1/(x-2) = 3",
    "sin(x) = 0.5",
)

private sealed interface SolveOutcome {
    data object Idle : SolveOutcome
    data class Exact(val result: ExactEquationResult) : SolveOutcome
    data class Numeric(val solution: EquationSolution) : SolveOutcome
    data class Error(val message: String) : SolveOutcome
}

@Composable
fun EquationSolverTool(padding: PaddingValues) {
    var input by remember {
        mutableStateOf(TextFieldValue(INITIAL_EQUATION, TextRange(INITIAL_EQUATION.length)))
    }
    var fromText by remember { mutableStateOf("-10") }
    var toText by remember { mutableStateOf("10") }
    var angleMode by remember { mutableStateOf(AngleMode.RADIAN) }
    var showNumericOptions by remember { mutableStateOf(false) }

    val outcome = remember(input.text, fromText, toText, angleMode) {
        solveSmart(input.text, fromText, toText, angleMode)
    }

    fun insert(text: String) {
        val start = minOf(input.selection.start, input.selection.end)
        val end = maxOf(input.selection.start, input.selection.end)
        val newText = input.text.replaceRange(start, end, text)
        input = TextFieldValue(newText, TextRange(start + text.length))
    }

    fun backspace() {
        val start = minOf(input.selection.start, input.selection.end)
        val end = maxOf(input.selection.start, input.selection.end)
        input = when {
            start != end -> TextFieldValue(
                input.text.removeRange(start, end),
                TextRange(start),
            )

            start > 0 -> TextFieldValue(
                input.text.removeRange(start - 1, start),
                TextRange(start - 1),
            )

            else -> input
        }
    }

    fun moveCaret(delta: Int) {
        val position = (input.selection.end + delta).coerceIn(0, input.text.length)
        input = input.copy(selection = TextRange(position))
    }

    ToolBody(padding) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "方程",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "点按公式可移动光标",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    readOnly = true,
                    textStyle = MiuixTheme.textStyles.title3.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.text.isEmpty()) {
                                Text(
                                    text = "例如 x^2 - 5x + 6 = 0",
                                    style = MiuixTheme.textStyles.title3,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
        }

        MathKeypad(
            onInsert = { insert(it) },
            onBackspace = { backspace() },
            onClear = { input = TextFieldValue("", TextRange(0)) },
            onMoveCaret = { moveCaret(it) },
        )

        when (val result = outcome) {
            SolveOutcome.Idle -> HintText("输入方程后会自动求精确解；含函数的方程使用数值扫描。")
            is SolveOutcome.Error -> Text(
                text = result.message,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
            )

            is SolveOutcome.Exact -> ExactResultView(result.result)
            is SolveOutcome.Numeric -> NumericResultView(result.solution)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showNumericOptions = !showNumericOptions },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("数值求解设置", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (showNumericOptions) Icons.Filled.ExpandLess
                    else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (showNumericOptions) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = "扫描下限",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = "扫描上限",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "角度单位",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                SelectChip(
                    selected = angleMode == AngleMode.RADIAN,
                    onClick = { angleMode = AngleMode.RADIAN },
                    label = "弧度",
                )
                SelectChip(
                    selected = angleMode == AngleMode.DEGREE,
                    onClick = { angleMode = AngleMode.DEGREE },
                    label = "角度",
                )
            }
            HintText("只影响 sin / cos / tan 等非多项式方程的数值扫描。")
        }

        SectionLabel("示例")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            equationExamples.forEach { example ->
                SelectChip(
                    selected = false,
                    onClick = {
                        input = TextFieldValue(example, TextRange(example.length))
                    },
                    label = example,
                )
            }
        }
    }
}

@Composable
private fun ExactResultView(result: ExactEquationResult) {
    ResultBox(result.standardForm, label = "标准形式", monospace = true)
    Text(
        text = "${result.kind} · ${result.description}",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.primary,
    )
    result.discriminant?.let { HintText(it) }
    result.domainNote?.let { HintText(it) }

    when {
        result.identity -> ResultBox("恒成立（定义域内），无穷多解", label = "结果")
        result.contradiction -> ResultBox("无解（常数不为零）", label = "结果")
        result.roots.isEmpty() -> ResultBox("无有效解（根都在定义域外）", label = "结果")
        else -> {
            result.roots.forEachIndexed { index, root ->
                val label = if (result.roots.size == 1) "x" else "x" + subscript(index + 1)
                val exact = root.format()
                val approximate = root.approximate()
                val text = if (exact == approximate) exact else "$exact\n≈ $approximate"
                ResultBox(text, label = label, monospace = true)
            }
        }
    }

    if (result.extraneous.isNotEmpty()) {
        HintText("增根（已舍去）：" + result.extraneous.joinToString("、") { it.format() })
    }
    HintText("精确解使用有理数运算化简；根号保留最简形式。")
}

@Composable
private fun NumericResultView(solution: EquationSolution) {
    when {
        solution.identity -> ResultBox("恒成立（范围内的所有取值都满足方程）", label = "结果")
        solution.contradiction -> ResultBox("无解（两边都是常数，且不相等）", label = "结果")
        solution.roots.isEmpty() -> {
            ResultBox("在给定范围内没有找到实根", label = "结果")
            HintText("可以展开「数值求解设置」扩大扫描范围，或检查方程是否写对。")
        }

        else -> {
            val variable = solution.variable ?: "x"
            val text = solution.roots.mapIndexed { index, root ->
                val name = if (solution.roots.size == 1) variable
                else variable + subscript(index + 1)
                "$name = ${formatNumber(root)}"
            }.joinToString("\n")
            ResultBox(
                text = text,
                label = "数值解（${solution.roots.size} 个实根）",
                monospace = true,
            )
            HintText(
                "非多项式方程在 [${formatNumber(solution.from)}, ${formatNumber(solution.to)}] 内数值扫描，" +
                    "只显示实根。"
            )
            if (solution.truncated) HintText("根太多，只显示前 ${solution.roots.size} 个。")
        }
    }
}

private fun solveSmart(
    text: String,
    fromText: String,
    toText: String,
    angleMode: AngleMode,
): SolveOutcome {
    if (text.isBlank()) return SolveOutcome.Idle
    try {
        return SolveOutcome.Exact(solvePolynomialEquation(text))
    } catch (e: NotPolynomialException) {
        // 含函数 / 根号 / π / e / 高次方程：退回数值扫描
    } catch (e: ExactSolveException) {
        return SolveOutcome.Error(e.message ?: "方程有误")
    }

    val from = fromText.trim().toDoubleOrNull()
    val to = toText.trim().toDoubleOrNull()
    if (from == null || to == null) return SolveOutcome.Error("扫描范围要填数字")
    return try {
        SolveOutcome.Numeric(solveEquation(text, from, to, angleMode))
    } catch (e: MathParseException) {
        SolveOutcome.Error(e.message ?: "表达式有误")
    } catch (e: Exception) {
        SolveOutcome.Error("表达式有误：${e.message ?: "无法解析"}")
    }
}

private const val SUBSCRIPT_DIGITS = "₀₁₂₃₄₅₆₇₈₉"

private fun subscript(number: Int): String =
    number.toString().map { SUBSCRIPT_DIGITS[it - '0'] }.joinToString("")
