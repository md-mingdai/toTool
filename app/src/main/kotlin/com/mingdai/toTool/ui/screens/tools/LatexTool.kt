package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.math.sanitizeLatexSource
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.LatexFormulaCard
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import top.yukonga.miuix.kmp.basic.TextField

private class LatexExample(val label: String, val latex: String)

private val latexExamples = listOf(
    LatexExample("二次方程", "\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"),
    LatexExample("积分", "\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}"),
    LatexExample("矩阵", "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"),
    LatexExample("级数", "\\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6}"),
    LatexExample("极限", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1"),
)

@Composable
fun LatexTool(padding: PaddingValues) {
    var input by rememberSaveable {
        mutableStateOf("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
    }
    val rendered = sanitizeLatexSource(input)

    ToolBody(padding) {
        SectionLabel("LaTeX 源码")
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "公式",
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        HintText(
            "支持直接粘贴 LaTeX 数学模式源码；\$...\$、\\(...\\)、\\[...\\] 会自动去掉。" +
                "渲染结果随输入实时更新。",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            latexExamples.forEach { example ->
                SelectChip(
                    selected = false,
                    onClick = { input = example.latex },
                    label = example.label,
                )
            }
        }

        SectionLabel("渲染结果")
        if (rendered.isBlank()) {
            HintText("输入 LaTeX 后会自动渲染。")
        } else {
            LatexFormulaCard(
                latex = rendered,
                title = "公式预览",
                fontSize = 24.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
