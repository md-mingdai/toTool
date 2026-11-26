package com.mingdai.toTool.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexThemeColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 数学公式的 LaTeX 预览卡片；字体由 latex-fonts-katex 随 APK 打包。 */
@Composable
fun LatexFormulaCard(
    latex: String?,
    modifier: Modifier = Modifier,
    title: String = "LaTeX 预览",
    fontSize: TextUnit = 20.sp,
) {
    if (latex.isNullOrBlank()) return
    val color = MiuixTheme.colorScheme.onSurface
    val config = remember(color, fontSize) {
        LatexConfig(
            fontSize = fontSize,
            theme = LatexTheme.auto(
                light = LatexThemeColors(color = color, backgroundColor = Color.Transparent),
                dark = LatexThemeColors(color = color, backgroundColor = Color.Transparent),
            ),
        )
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                title,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
            )
            LatexAutoWrap(
                latex = latex,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                config = config,
            )
        }
    }
}
