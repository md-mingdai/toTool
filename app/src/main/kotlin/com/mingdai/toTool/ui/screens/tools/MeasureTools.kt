package com.mingdai.toTool.ui.screens.tools

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ------------------------------- 尺子 ------------------------------- */

@Composable
fun RulerTool(padding: PaddingValues) {
    val context = LocalContext.current
    // densityDpi 只是系统归并后的“逻辑密度”（160/320/420/480…），不是真实像素密度；
    // 量尺子必须用物理 xdpi，否则刻度会偏差 5%~20%。
    val dm = context.resources.displayMetrics
    val dpi = dm.xdpi.takeIf { it > 50f } ?: dm.densityDpi.toFloat()
    val textMeasurer = rememberTextMeasurer()
    val ink = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    val accent = MiuixTheme.colorScheme.primary.toArgb()

    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    var widthPx by remember { mutableStateOf(0) }
    var screenWidthCmInput by remember { mutableStateOf("") }

    val pxPerMm: Float = run {
        val cm = screenWidthCmInput.toFloatOrNull()
        if (cm != null && cm > 0f && widthPx > 0) widthPx / (cm * 10f)
        else dpi / 25.4f
    }

    ToolBody(padding) {
        Text(
            "屏幕宽度为基准的毫米刻度，可直接贴近纸张测量。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextField(
            value = screenWidthCmInput,
            onValueChange = { screenWidthCmInput = it },
            label = "屏幕实际宽度 cm（留空按 dpi）",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(MiuixTheme.colorScheme.secondaryContainer)
                .onSizeChanged { widthPx = it.width },
        ) {
            if (widthPx > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val baseY = size.height - 24f
                    drawLine(
                        color = Color(ink),
                        start = Offset(0f, baseY),
                        end = Offset(size.width, baseY),
                        strokeWidth = 3f,
                    )
                    var x = 0f
                    var mm = 0
                    while (x <= size.width) {
                        val isCm = mm % 10 == 0
                        val tickH = when {
                            mm % 50 == 0 -> 42f
                            isCm -> 32f
                            mm % 5 == 0 -> 20f
                            else -> 12f
                        }
                        drawLine(
                            color = if (isCm) Color(accent) else Color(ink),
                            start = Offset(x, baseY),
                            end = Offset(x, baseY - tickH),
                            strokeWidth = if (isCm) 3f else 2f,
                        )
                        if (isCm) {
                            val label = (mm / 10).toString()
                            val labelStyle = TextStyle(
                                fontSize = 13.sp,
                                color = Color(accent),
                                fontWeight = FontWeight.Bold,
                            )
                            val layout = textMeasurer.measure(text = label, style = labelStyle)
                            drawText(
                                textMeasurer,
                                label,
                                topLeft = Offset(
                                    x - layout.size.width / 2f,
                                    baseY - tickH - layout.size.height,
                                ),
                                style = labelStyle,
                            )
                        }
                        x += pxPerMm
                        mm++
                        if (mm > 500) break
                    }
                    drawText(
                        textMeasurer,
                        "cm",
                        topLeft = Offset(6f, baseY - 52f),
                        style = TextStyle(fontSize = 11.sp, color = Color(ink)),
                    )
                }
            }
        }
        SectionLabel("屏幕参数")
        Text(
            if (widthPx > 0)
                String.format(
                    Locale.US,
                    "屏幕宽度 ≈ %.2f cm（%d px，%d dpi）",
                    widthPx / pxPerMm / 10f,
                    widthPx,
                    dpi.toInt(),
                )
            else "测量中…",
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/* ------------------------------- 量角器 ------------------------------- */

@Composable
fun ProtractorTool(padding: PaddingValues) {
    var angle by remember { mutableStateOf(45f) }
    val textMeasurer = rememberTextMeasurer()

    ToolBody(padding) {
        val ink = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
        val primary = MiuixTheme.colorScheme.primary.toArgb()

        Box(
            modifier = Modifier.fillMaxWidth().height(330.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height - 30f
                val r = minOf(size.width / 2f - 26f, cy - 16f)
                if (r < 50f) return@Canvas

                drawArc(
                    color = Color(ink),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = 3f),
                )
                drawLine(
                    color = Color(ink),
                    start = Offset(cx - r - 12f, cy),
                    end = Offset(cx + r + 12f, cy),
                    strokeWidth = 3f,
                )
                for (deg in 0..180 step 5) {
                    val isMajor = deg % 30 == 0
                    val inner = if (isMajor) r - 26f else if (deg % 10 == 0) r - 18f else r - 11f
                    val rad = Math.toRadians((90 - deg).toDouble())
                    val dx = -Math.sin(rad).toFloat()
                    val dy = -Math.cos(rad).toFloat()
                    drawLine(
                        color = Color(ink),
                        start = Offset(cx + dx * inner, cy + dy * inner),
                        end = Offset(cx + dx * r, cy + dy * r),
                        strokeWidth = if (isMajor) 3f else 2f,
                    )
                    if (isMajor) {
                        val lr = r - 46f
                        val labelStyle = TextStyle(fontSize = 12.sp, color = Color(ink))
                        val layout = textMeasurer.measure(deg.toString(), style = labelStyle)
                        val labelTopLeft = Offset(
                            (cx + dx * lr - layout.size.width / 2f)
                                .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)),
                            (cy + dy * lr - layout.size.height / 2f)
                                .coerceIn(0f, (size.height - layout.size.height).coerceAtLeast(0f)),
                        )
                        drawText(
                            textMeasurer,
                            deg.toString(),
                            topLeft = labelTopLeft,
                            style = labelStyle,
                        )
                    }
                }
                val rad = Math.toRadians((angle - 90).toDouble())
                val end = Offset(
                    cx + Math.sin(rad).toFloat() * r,
                    cy - Math.cos(rad).toFloat() * r,
                )
                drawLine(
                    color = Color(primary),
                    start = Offset(cx, cy),
                    end = end,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
                drawArc(
                    color = Color(primary),
                    startAngle = 180f,
                    sweepAngle = angle,
                    useCenter = false,
                    topLeft = Offset(cx - 56f, cy - 56f),
                    size = Size(112f, 112f),
                    style = Stroke(width = 4f),
                )
                drawCircle(color = Color(primary), center = Offset(cx, cy), radius = 7f)
            }
        }
        Text(
            "角度：${String.format(Locale.US, "%.0f°", angle)}",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.primary,
        )
        Slider(value = angle, onValueChange = { angle = it }, valueRange = 0f..180f)
        SectionLabel("说明")
        Text(
            "拖动滑块移动蓝色指针，圆弧表示当前角度；外圈刻度从左侧 0° 到右侧 180°。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/* ------------------------------- 屏幕坏点检测 ------------------------------- */

private val PixelCheckModes = listOf(
    Color.Red,
    Color.Green,
    Color.Blue,
    Color.Black,
    Color.White,
    Color(0xFF808080),
)

@Composable
fun PixelCheckFullscreen(navController: NavHostController) {
    var mode by remember { mutableStateOf(0) }

    BackHandler { navController.popBackStack() }

    val isGrid = mode == PixelCheckModes.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isGrid) Color.White else PixelCheckModes[mode])
            .pointerInput(Unit) {
                detectTapGestures { mode = (mode + 1) % (PixelCheckModes.size + 1) }
            },
    ) {
        if (isGrid) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = 24f
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var col = row
                    while (x < size.width) {
                        if ((col + row) % 2 == 0) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x, y),
                                size = Size(cell, cell),
                            )
                        }
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            }
        }
    }
}
