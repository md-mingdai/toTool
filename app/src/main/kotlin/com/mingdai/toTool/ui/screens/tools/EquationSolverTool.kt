package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.math.AngleMode
import com.mingdai.toTool.math.EquationSolution
import com.mingdai.toTool.math.MathParseException
import com.mingdai.toTool.math.formatNumber
import com.mingdai.toTool.math.solveEquation
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val equationExamples = listOf(
    "2x + 3 = 7",
    "x^2 - 5x + 6 = 0",
    "sin(x) = 0.5",
    "x^3 - 2x + 1 = 0",
    "1/x = 2",
)

private val quickKeys = listOf("x", "^", "(", ")", "+", "−", "×", "÷", "sin(", "cos(", "tan(", "ln(", "√(", "π")

private sealed interface SolveState {
    data class Error(val message: String) : SolveState
    data class Done(val solution: EquationSolution) : SolveState
}

@Composable
fun EquationSolverTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("x^2 - 5x + 6 = 0") }
    var fromText by remember { mutableStateOf("-10") }
    var toText by remember { mutableStateOf("10") }
    var angleMode by remember { mutableStateOf(AngleMode.RADIAN) }

    val state = remember(input, fromText, toText, angleMode) {
        try {
            val from = fromText.trim().toDoubleOrNull()
            val to = toText.trim().toDoubleOrNull()
            if (from == null || to == null) {
                SolveState.Error("范围要填数字")
            } else {
                SolveState.Done(solveEquation(input, from, to, angleMode))
            }
        } catch (e: MathParseException) {
            SolveState.Error(e.message ?: "表达式有误")
        } catch (e: Exception) {
            SolveState.Error("表达式有误：${e.message ?: "无法解析"}")
        }
    }

    fun append(text: String) {
        input += text
    }

    fun backspace() {
        input = input.dropLast(1)
    }

    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "方程（含一个未知数，可不写 = 0）",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickKeys.forEach { key ->
                SelectChip(selected = false, onClick = { append(key) }, label = key)
            }
            SelectChip(selected = false, onClick = { backspace() }, label = "⌫")
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = fromText,
                onValueChange = { fromText = it },
                label = "范围下限",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = toText,
                onValueChange = { toText = it },
                label = "范围上限",
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

        SectionLabel("示例")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            equationExamples.forEach { example ->
                SelectChip(
                    selected = false,
                    onClick = { input = example },
                    label = example,
                )
            }
        }

        when (val result = state) {
            is SolveState.Error -> {
                Text(
                    text = result.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
                HintText("支持 + − × ÷ ^ 与 sin、cos、tan、ln、log、sqrt、abs 等函数；π、e 可直接使用。")
            }

            is SolveState.Done -> {
                val solution = result.solution
                when {
                    solution.identity -> ResultBox("恒成立（范围内的所有取值都满足方程）", label = "结果")
                    solution.contradiction -> ResultBox("无解（两边都是常数，且不相等）", label = "结果")
                    solution.roots.isEmpty() -> {
                        ResultBox("在给定范围内没有找到实根", label = "结果")
                        HintText("可以扩大扫描范围再试，或检查方程是否写对。")
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
                            label = "解（${solution.roots.size} 个实根）",
                            monospace = true,
                        )
                        HintText(
                            "在 [${formatNumber(solution.from)}, ${formatNumber(solution.to)}] 内数值扫描，" +
                                "保留约 10 位小数。"
                        )
                        if (solution.truncated) {
                            HintText("根太多，只显示前 ${solution.roots.size} 个。")
                        }
                    }
                }
            }
        }
    }
}

private const val SUBSCRIPT_DIGITS = "₀₁₂₃₄₅₆₇₈₉"

private fun subscript(number: Int): String =
    number.toString().map { SUBSCRIPT_DIGITS[it - '0'] }.joinToString("")
