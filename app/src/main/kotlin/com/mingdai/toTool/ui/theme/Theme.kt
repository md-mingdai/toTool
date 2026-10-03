package com.mingdai.toTool.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.mingdai.toTool.R
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * 用户选择的主题模式。
 *
 * - SYSTEM：Miuix 默认的 HyperOS 配色，亮/暗跟随系统（新装默认）
 * - CUSTOM：以自选颜色（HSV）为种子色生成 Miuix 配色
 * - LIGHT / DARK：Miuix 默认亮色 / 暗色
 * - DYNAMIC：跟随系统壁纸取色（Android 12+）
 */
enum class ThemeMode { SYSTEM, CUSTOM, LIGHT, DARK, DYNAMIC }

/** 设为 false 即可改用系统默认字体（Xiaomi 机型上为 MiSans）。 */
private const val USE_CUSTOM_FONT = true

/**
 * 应用字体：Onest（SIL Open Font License 1.1，可免费商用、随 App 分发，授权文本见 assets/licenses）。
 * res/font 里是从官方可变字体生成的 4 个静态字重：Regular 对应 wght 440（比标准 400 略粗，正文更扎实），
 * Medium 530 / SemiBold 630 / Bold 730，所以标题和按钮的粗体是真粗体，不再靠系统合成。
 * 字体里没有的字符（汉字、中文标点、℃ 等）会自动回退到系统字体，不会显示成方块。
 */
val AppFontFamily: FontFamily =
    if (USE_CUSTOM_FONT) FontFamily(
        Font(R.font.onest_regular, FontWeight.Normal),
        Font(R.font.onest_medium, FontWeight.Medium),
        Font(R.font.onest_semibold, FontWeight.SemiBold),
        Font(R.font.onest_bold, FontWeight.Bold),
    ) else FontFamily.Default

private fun buildTextStyles(): TextStyles {
    val base = defaultTextStyles()
    if (!USE_CUSTOM_FONT) return base
    val f = AppFontFamily
    return defaultTextStyles(
        main = base.main.copy(fontFamily = f),
        paragraph = base.paragraph.copy(fontFamily = f),
        body1 = base.body1.copy(fontFamily = f),
        body2 = base.body2.copy(fontFamily = f),
        button = base.button.copy(fontFamily = f),
        footnote1 = base.footnote1.copy(fontFamily = f),
        footnote2 = base.footnote2.copy(fontFamily = f),
        headline1 = base.headline1.copy(fontFamily = f),
        headline2 = base.headline2.copy(fontFamily = f),
        subtitle = base.subtitle.copy(fontFamily = f),
        title1 = base.title1.copy(fontFamily = f),
        title2 = base.title2.copy(fontFamily = f),
        title3 = base.title3.copy(fontFamily = f),
        title4 = base.title4.copy(fontFamily = f),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** App 主题：把用户的主题选择翻译成 Miuix 的 [ThemeController]。 */
@Composable
fun ToolyTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    customHue: Float = 0f,
    customSaturation: Float = 0.8f,
    customValue: Float = 0.55f,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    // ThemeController 的字段是构造时一次性赋值的，因此参数变化时需要重建。
    val controller = remember(mode, customHue, customSaturation, customValue) {
        when (mode) {
            ThemeMode.SYSTEM -> ThemeController(ColorSchemeMode.System)
            ThemeMode.LIGHT -> ThemeController(ColorSchemeMode.Light)
            ThemeMode.DARK -> ThemeController(ColorSchemeMode.Dark)
            ThemeMode.DYNAMIC -> ThemeController(ColorSchemeMode.MonetSystem)
            ThemeMode.CUSTOM -> ThemeController(
                colorSchemeMode = ColorSchemeMode.MonetSystem,
                keyColor = Color.hsv(
                    hue = customHue.coerceIn(0f, 360f),
                    saturation = customSaturation.coerceIn(0.1f, 1f),
                    value = customValue.coerceIn(0.2f, 1f),
                ),
            )
        }
    }
    val textStyles = remember { buildTextStyles() }

    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !isDark
            insets.isAppearanceLightNavigationBars = !isDark
        }
    }

    MiuixTheme(
        controller = controller,
        textStyles = textStyles,
        content = content,
    )
}
