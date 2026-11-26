@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.mingdai.toTool.ui.screens.tools

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.editor.LatexEditor
import com.hrm.latex.renderer.editor.rememberEditorState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LatexThemeColors
import com.mingdai.toTool.math.AngleMode
import com.mingdai.toTool.math.MathExpression
import com.mingdai.toTool.math.formatNumber
import com.mingdai.toTool.math.latexToMathInput
import com.mingdai.toTool.math.normalizeLatexFunctionInput
import com.mingdai.toTool.math.sanitizeLatexSource
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.MathKeypad
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 曲线配色：亮色和暗色主题下都能看清，按添加顺序循环使用。 */
private val plotColors = listOf(
    Color(0xFF3B82F6),
    Color(0xFFEF4444),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFF8B5CF6),
    Color(0xFF06B6D4),
    Color(0xFFEC4899),
    Color(0xFF84CC16),
)

private const val FUNCTION_NAME_CHARS = "fghijklmnopqrstuvwxyz"

private data class PlotFunction(
    val id: Int,
    val name: String,
    val text: String,
    val expression: MathExpression,
    val color: Color,
    val visible: Boolean,
)

/** 默认视图：x 方向 12 个单位宽，y 方向按画布比例自动换算。 */
private const val DEFAULT_X_SPAN = 12.0
private const val MIN_X_SPAN = 1e-6
private const val MAX_X_SPAN = 1e9

@Composable
fun FunctionPlotTool(padding: PaddingValues) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val listLatexConfig = remember(colors.onSurface) {
        LatexConfig(
            fontSize = 16.sp,
            theme = LatexTheme.auto(
                light = LatexThemeColors(color = colors.onSurface, backgroundColor = Color.Transparent),
                dark = LatexThemeColors(color = colors.onSurface, backgroundColor = Color.Transparent),
            ),
        )
    }
    val editorLatexConfig = remember(colors.onSurface) {
        LatexConfig(
            fontSize = 20.sp,
            theme = LatexTheme.auto(
                light = LatexThemeColors(color = colors.onSurface, backgroundColor = Color.Transparent),
                dark = LatexThemeColors(color = colors.onSurface, backgroundColor = Color.Transparent),
            ),
        )
    }

    val functions = remember {
        val source = "\\sin(x)"
        mutableStateListOf<PlotFunction>().apply {
            runCatching { MathExpression.parse(latexToMathInput(source)) }.getOrNull()?.let { expression ->
                add(
                    PlotFunction(
                        id = 0,
                        name = "f",
                        text = source,
                        expression = expression,
                        color = plotColors[0],
                        visible = true,
                    )
                )
            }
        }
    }
    var nextId by remember { mutableIntStateOf(1) }
    var angleMode by remember { mutableStateOf(AngleMode.RADIAN) }

    var centerX by remember { mutableStateOf(0.0) }
    var centerY by remember { mutableStateOf(0.0) }
    var xSpan by remember { mutableStateOf(DEFAULT_X_SPAN) }
    var traceX by remember { mutableStateOf<Double?>(null) }

    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    val editorState = rememberEditorState(initialText = "")
    val editorText = editorState.text
    val cleanedEditorText = remember(editorText) { normalizeLatexFunctionInput(editorText) }
    val convertedEditorText = remember(cleanedEditorText) {
        MathExpression.normalizeFunctionInput(latexToMathInput(cleanedEditorText)).trim()
    }

    val parsedEditor = remember(convertedEditorText, angleMode) {
        if (convertedEditorText.isBlank()) null
        else runCatching { MathExpression.parse(convertedEditorText, angleMode) }.getOrNull()
    }
    val parseEditorError = remember(convertedEditorText, angleMode) {
        if (convertedEditorText.isBlank()) null
        else runCatching { MathExpression.parse(convertedEditorText, angleMode) }.exceptionOrNull()?.message
    }

    fun openEditor(function: PlotFunction?) {
        editingId = function?.id
        editorState.updateText(function?.text ?: "")
        editorError = null
        editorOpen = true
    }

    fun insertEditor(text: String) {
        if (editorState.selection != null) editorState.delete()
        editorState.insert(text)
        editorError = null
    }

    fun pasteEditor() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
        if (clip.isNullOrBlank()) {
            Toast.makeText(context, "剪贴板里没有文本", Toast.LENGTH_SHORT).show()
        } else {
            insertEditor(sanitizeLatexSource(clip))
        }
    }

    fun confirmEditor() {
        val expression = parsedEditor
        when {
            cleanedEditorText.isEmpty() -> editorError = "请输入表达式"
            expression == null -> editorError = parseEditorError ?: "表达式有误"
            expression.variables.any { it != "x" } -> editorError = "只支持变量 x"
            else -> {
                val id = editingId
                if (id == null) {
                    val usedNames = functions.mapTo(mutableSetOf()) { it.name }
                    val name = FUNCTION_NAME_CHARS.map { it.toString() }
                        .firstOrNull { it !in usedNames } ?: "f${functions.size + 1}"
                    functions.add(
                        PlotFunction(
                            id = nextId,
                            name = name,
                            text = cleanedEditorText,
                            expression = expression,
                            color = plotColors[functions.size % plotColors.size],
                            visible = true,
                        )
                    )
                    nextId += 1
                } else {
                    val index = functions.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        functions[index] = functions[index].copy(
                            text = cleanedEditorText,
                            expression = expression,
                        )
                    }
                }
                editorError = null
                editorOpen = false
            }
        }
    }

    fun changeAngle(mode: AngleMode) {
        if (mode == angleMode) return
        angleMode = mode
        for (index in functions.indices) {
            val function = functions[index]
            val parsed = runCatching {
                MathExpression.parse(
                    MathExpression.normalizeFunctionInput(latexToMathInput(function.text)),
                    mode,
                )
            }.getOrNull()
            if (parsed != null) functions[index] = function.copy(expression = parsed)
        }
    }

    fun resetView() {
        centerX = 0.0
        centerY = 0.0
        xSpan = DEFAULT_X_SPAN
        traceX = null
    }

    fun zoomBy(factor: Double) {
        xSpan = (xSpan * factor).coerceIn(MIN_X_SPAN, MAX_X_SPAN)
    }

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MiuixTheme.textStyles.footnote2.copy(
        fontSize = 10.sp,
        color = colors.onSurfaceVariantSummary,
    )
    val traceText = traceX?.let { xValue ->
        buildString {
            append("x = ").append(formatNumber(xValue))
            functions.filter { it.visible }.forEach { function ->
                val value = runCatching { function.expression.eval(xValue) }.getOrDefault(Double.NaN)
                append('\n').append(function.name).append("(x) = ")
                append(if (value.isFinite()) formatNumber(value) else "无定义")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainer),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val width = size.width.toDouble().coerceAtLeast(1.0)
                            val height = size.height.toDouble().coerceAtLeast(1.0)
                            val oldSpan = xSpan
                            val newSpan = (oldSpan / zoom.coerceAtLeast(0.01f))
                                .coerceIn(MIN_X_SPAN, MAX_X_SPAN)
                            val oldYSpan = oldSpan * height / width
                            val focusX = (centerX - oldSpan / 2.0) + centroid.x / width * oldSpan
                            val focusY = (centerY + oldYSpan / 2.0) - centroid.y / height * oldYSpan
                            val ratio = newSpan / oldSpan

                            centerX = (focusX + (centerX - focusX) * ratio - pan.x / width * newSpan)
                                .coerceIn(-1e8, 1e8)
                            centerY = (focusY + (centerY - focusY) * ratio +
                                pan.y / height * (newSpan * height / width))
                                .coerceIn(-1e8, 1e8)
                            xSpan = newSpan
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val width = size.width.toDouble().coerceAtLeast(1.0)
                            traceX = centerX + (offset.x / width - 0.5) * xSpan
                        }
                    },
            ) {
                drawPlot(
                    centerX = centerX,
                    centerY = centerY,
                    xSpan = xSpan,
                    functions = functions,
                    traceX = traceX,
                    foreground = colors.onSurface,
                    labelStyle = labelStyle,
                    textMeasurer = textMeasurer,
                )
            }

            if (functions.isEmpty()) {
                Text(
                    text = "点下方「添加函数」开始绘图",
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }

            if (traceText != null) {
                Card(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = CardDefaults.defaultColors(
                        color = colors.surfaceContainer.copy(alpha = 0.94f),
                    ),
                ) {
                    Text(
                        text = traceText,
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurface,
                        maxLines = 9,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Card(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(
                    color = colors.surfaceContainer.copy(alpha = 0.94f),
                ),
            ) {
                Column {
                    IconButton(onClick = { zoomBy(0.6) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "放大",
                            tint = colors.primary,
                        )
                    }
                    IconButton(onClick = { zoomBy(1.6) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = "缩小",
                            tint = colors.primary,
                        )
                    }
                    IconButton(onClick = { resetView() }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "重置视图",
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip(
                selected = angleMode == AngleMode.RADIAN,
                onClick = { changeAngle(AngleMode.RADIAN) },
                label = "弧度",
            )
            SelectChip(
                selected = angleMode == AngleMode.DEGREE,
                onClick = { changeAngle(AngleMode.DEGREE) },
                label = "角度",
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "双指缩放 · 拖动平移 · 点按取值",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            functions.forEach { function ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                    onClick = { openEditor(function) },
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(function.color)
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .alpha(if (function.visible) 1f else 0.55f),
                        ) {
                            LatexAutoWrap(
                                latex = "${function.name}(x) = ${function.text}",
                                modifier = Modifier.fillMaxWidth(),
                                config = listLatexConfig,
                            )
                        }
                        IconButton(
                            onClick = {
                                val index = functions.indexOfFirst { it.id == function.id }
                                if (index >= 0) {
                                    functions[index] =
                                        functions[index].copy(visible = !functions[index].visible)
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (function.visible) Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff,
                                contentDescription = if (function.visible) "隐藏" else "显示",
                                tint = if (function.visible) colors.primary
                                else colors.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                functions.removeAll { it.id == function.id }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = colors.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            SecondaryButton(
                onClick = { openEditor(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text("添加函数", style = MiuixTheme.textStyles.button)
            }
        }
    }

    AppDialog(
        show = editorOpen,
        title = if (editingId == null) "添加函数" else "编辑函数",
        onDismiss = { editorOpen = false },
        confirmText = "确定",
        onConfirm = { confirmEditor() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "函数表达式",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "可粘贴 LaTeX",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                IconButton(
                    onClick = { pasteEditor() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentPaste,
                        contentDescription = "粘贴 LaTeX",
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .heightIn(min = 48.dp),
            ) {
                LatexEditor(
                    editorState = editorState,
                    modifier = Modifier.fillMaxWidth(),
                    config = editorLatexConfig,
                    cursorColor = MiuixTheme.colorScheme.primary,
                    selectionColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.25f),
                    showSourceText = false,
                )
                if (editorText.isBlank()) {
                    Text(
                        text = "例如 \\sin(x)",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "支持 LaTeX 和普通表达式；分数、根号、上下标和常见函数会自动渲染并参与计算。" +
                    "三角函数按上方选择的角度单位计算。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            val error = editorError
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            MathKeypad(
                onInsert = { insertEditor(it) },
                onBackspace = {
                    editorState.backspace()
                    editorError = null
                },
                onClear = {
                    editorState.updateText("")
                    editorError = null
                },
                onMoveCaret = { editorState.moveCursorTo(editorState.cursorOffset + it) },
                onSubmit = { confirmEditor() },
                submitLabel = "完成",
                compact = true,
            )
        }
    }
}

/* ------------------------------- 绘制 ------------------------------- */

private fun DrawScope.drawPlot(
    centerX: Double,
    centerY: Double,
    xSpan: Double,
    functions: List<PlotFunction>,
    traceX: Double?,
    foreground: Color,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
) {
    val canvasWidth = size.width
    val canvasHeight = size.height
    if (canvasWidth <= 1f || canvasHeight <= 1f || xSpan <= 0.0) return

    val ySpan = xSpan * canvasHeight / canvasWidth
    val xMin = centerX - xSpan / 2.0
    val xMax = centerX + xSpan / 2.0
    val yMin = centerY - ySpan / 2.0
    val yMax = centerY + ySpan / 2.0

    fun xToPx(x: Double): Float = ((x - xMin) / xSpan * canvasWidth).toFloat()
    fun yToPx(y: Double): Float = ((yMax - y) / ySpan * canvasHeight).toFloat()

    val xStep = niceStep(xSpan, 7)
    val yStep = niceStep(ySpan, 8)

    // 细网格
    drawGridLines(
        min = xMin, max = xMax, step = xStep / 5.0,
        vertical = true, sizePx = canvasWidth, canvasHeight = canvasHeight,
        color = foreground.copy(alpha = 0.055f), skipEvery = 5,
    )
    drawGridLines(
        min = yMin, max = yMax, step = yStep / 5.0,
        vertical = false, sizePx = canvasHeight, canvasHeight = canvasHeight,
        color = foreground.copy(alpha = 0.055f), skipEvery = 5,
    )

    // 主网格
    drawGridLines(
        min = xMin, max = xMax, step = xStep,
        vertical = true, sizePx = canvasWidth, canvasHeight = canvasHeight,
        color = foreground.copy(alpha = 0.14f), skipEvery = 0,
    )
    drawGridLines(
        min = yMin, max = yMax, step = yStep,
        vertical = false, sizePx = canvasHeight, canvasHeight = canvasHeight,
        color = foreground.copy(alpha = 0.14f), skipEvery = 0,
    )

    // 坐标轴
    val axisWidth = 1.2.dp.toPx()
    val axisVisibleX = xMin <= 0.0 && xMax >= 0.0
    val axisVisibleY = yMin <= 0.0 && yMax >= 0.0
    if (axisVisibleX) {
        val x = xToPx(0.0)
        drawLine(
            color = foreground.copy(alpha = 0.55f),
            start = Offset(x, 0f),
            end = Offset(x, canvasHeight),
            strokeWidth = axisWidth,
        )
    }
    if (axisVisibleY) {
        val y = yToPx(0.0)
        drawLine(
            color = foreground.copy(alpha = 0.55f),
            start = Offset(0f, y),
            end = Offset(canvasWidth, y),
            strokeWidth = axisWidth,
        )
    }

    // 刻度值
    val gap = 4.dp.toPx()
    drawXAxisLabels(
        xMin = xMin, xMax = xMax, step = xStep, axisVisible = axisVisibleY,
        axisYPx = if (axisVisibleY) yToPx(0.0) else canvasHeight,
        canvasWidth = canvasWidth, canvasHeight = canvasHeight,
        gap = gap, labelStyle = labelStyle,
        textMeasurer = textMeasurer, xToPx = { xToPx(it) },
    )
    drawYAxisLabels(
        yMin = yMin, yMax = yMax, step = yStep, axisVisible = axisVisibleX,
        axisXPx = if (axisVisibleX) xToPx(0.0) else 0f,
        canvasWidth = canvasWidth, canvasHeight = canvasHeight,
        gap = gap, labelStyle = labelStyle,
        textMeasurer = textMeasurer, yToPx = { yToPx(it) },
    )
    if (axisVisibleX && axisVisibleY) {
        val origin = textMeasurer.measure("0", labelStyle)
        drawText(
            textLayoutResult = origin,
            topLeft = Offset(
                (xToPx(0.0) + gap).coerceIn(gap, canvasWidth - origin.size.width - gap),
                (yToPx(0.0) + gap).coerceIn(gap, canvasHeight - origin.size.height - gap),
            ),
        )
    }

    // 函数曲线
    val strokeWidth = 2.5.dp.toPx()
    val sampleCount = (canvasWidth * 2f).toInt().coerceIn(400, 2400)
    functions.forEach { function ->
        if (!function.visible) return@forEach
        val path = Path()
        var hasPoint = false
        var previousY = 0.0
        for (index in 0..sampleCount) {
            val x = xMin + xSpan * index / sampleCount
            val y = function.expression.eval(x)
            val drawable = y.isFinite() && abs(y - centerY) <= ySpan * 3.0
            if (!drawable) {
                hasPoint = false
                continue
            }
            if (hasPoint && isAsymptoteJump(previousY, y, ySpan)) {
                hasPoint = false
            }
            val px = xToPx(x)
            val py = yToPx(y)
            if (hasPoint) path.lineTo(px, py) else path.moveTo(px, py)
            hasPoint = true
            previousY = y
        }
        drawPath(
            path = path,
            color = function.color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    // 点按取值时的参考线
    val traceValue = traceX
    if (traceValue != null && traceValue >= xMin && traceValue <= xMax) {
        val x = xToPx(traceValue)
        drawLine(
            color = foreground.copy(alpha = 0.45f),
            start = Offset(x, 0f),
            end = Offset(x, canvasHeight),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
            ),
        )
        functions.forEach { function ->
            if (!function.visible) return@forEach
            val y = function.expression.eval(traceValue)
            if (y.isFinite() && y >= yMin && y <= yMax) {
                drawCircle(
                    color = function.color,
                    radius = 4.dp.toPx(),
                    center = Offset(x, yToPx(y)),
                )
            }
        }
    }
}

private fun DrawScope.drawGridLines(
    min: Double,
    max: Double,
    step: Double,
    vertical: Boolean,
    sizePx: Float,
    canvasHeight: Float,
    color: Color,
    skipEvery: Int,
) {
    if (!step.isFinite() || step <= 0.0) return
    var index = ceil(min / step).toLong()
    val last = floor(max / step).toLong()
    if (last - index > 10000) return
    val stroke = 1.dp.toPx()
    while (index <= last) {
        if (skipEvery <= 0 || index % skipEvery != 0L) {
            if (vertical) {
                val x = ((index * step - min) / (max - min) * sizePx).toFloat()
                drawLine(color, Offset(x, 0f), Offset(x, canvasHeight), stroke)
            } else {
                val y = ((max - index * step) / (max - min) * sizePx).toFloat()
                drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
            }
        }
        index++
    }
}

private fun DrawScope.drawXAxisLabels(
    xMin: Double,
    xMax: Double,
    step: Double,
    axisVisible: Boolean,
    axisYPx: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    gap: Float,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    xToPx: (Double) -> Float,
) {
    if (!step.isFinite() || step <= 0.0) return
    var index = ceil(xMin / step).toLong()
    val last = floor(xMax / step).toLong()
    if (last - index > 1000) return
    while (index <= last) {
        val value = index * step
        if (abs(value) < step * 1e-6) {
            index++
            continue
        }
        val layout = textMeasurer.measure(formatTick(value, step), labelStyle)
        val x = (xToPx(value) - layout.size.width / 2f)
            .coerceIn(gap, canvasWidth - layout.size.width - gap)
        val y = if (axisVisible) {
            (axisYPx + gap).coerceIn(gap, canvasHeight - layout.size.height - gap)
        } else {
            canvasHeight - layout.size.height - gap
        }
        drawText(layout, topLeft = Offset(x, y))
        index++
    }
}

private fun DrawScope.drawYAxisLabels(
    yMin: Double,
    yMax: Double,
    step: Double,
    axisVisible: Boolean,
    axisXPx: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    gap: Float,
    labelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    yToPx: (Double) -> Float,
) {
    if (!step.isFinite() || step <= 0.0) return
    var index = ceil(yMin / step).toLong()
    val last = floor(yMax / step).toLong()
    if (last - index > 1000) return
    while (index <= last) {
        val value = index * step
        if (abs(value) < step * 1e-6) {
            index++
            continue
        }
        val layout = textMeasurer.measure(formatTick(value, step), labelStyle)
        val x = if (axisVisible) {
            if (axisXPx > canvasWidth / 2f) {
                (axisXPx - layout.size.width - gap).coerceIn(gap, canvasWidth - layout.size.width - gap)
            } else {
                (axisXPx + gap).coerceIn(gap, canvasWidth - layout.size.width - gap)
            }
        } else {
            gap
        }
        val y = (yToPx(value) - layout.size.height / 2f)
            .coerceIn(gap, canvasHeight - layout.size.height - gap)
        drawText(layout, topLeft = Offset(x, y))
        index++
    }
}

/** 曲线在相邻采样点之间“从正无穷跳到负无穷”时视为渐近线，不要把两点连起来。 */
private fun isAsymptoteJump(previousY: Double, currentY: Double, ySpan: Double): Boolean {
    val differentSign = (previousY > 0.0 && currentY < 0.0) || (previousY < 0.0 && currentY > 0.0)
    return differentSign && abs(previousY - currentY) > ySpan * 4.0
}

/** 网格步长取 1 / 2 / 5 × 10^n，保证大约 [target] 条主网格线。 */
private fun niceStep(span: Double, target: Int): Double {
    val raw = span / target
    if (!raw.isFinite() || raw <= 0.0) return 1.0
    val magnitude = 10.0.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val multiplier = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return multiplier * magnitude
}

/** 坐标刻度：按步长决定小数位，避免 0.30000000004 这种显示。 */
private fun formatTick(value: Double, step: Double): String {
    val magnitude = abs(value)
    if (magnitude < step * 1e-6) return "0"
    if (magnitude >= 1e6 || magnitude < 1e-4) {
        return String.format(Locale.US, "%.1e", value)
    }
    val decimals = max(0, ceil(-log10(step)).toInt())
    var text = String.format(Locale.US, "%.${decimals}f", value)
    if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}
