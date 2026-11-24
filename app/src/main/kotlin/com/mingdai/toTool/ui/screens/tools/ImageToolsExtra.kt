package com.mingdai.toTool.ui.screens.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.media.baseNameOf
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.media.queryFileSize
import com.mingdai.toTool.media.saveImageToGallery
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.ColorWheelPicker
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.copyText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ─────────────────────────── 图片工具共享组件 ─────────────────────────── */

@Composable
private fun rememberBitmapPicker(
    maxSide: Int = 1600,
    onPicked: (Bitmap) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { ImageOps.uriToBitmap(context, uri, maxSide) }
                if (bitmap == null) {
                    Toast.makeText(context, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(bitmap)
                }
            }
        }
    }
    return { launcher.launch("image/*") }
}

@Composable
private fun PickImageButton(onClick: () -> Unit) {
    PrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AddAPhoto, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("选择图片")
    }
}

@Composable
private fun EmptyImageState(padding: PaddingValues, hint: String, onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AddAPhoto,
                contentDescription = "选择图片",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("选择一张图片", style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("从相册选择")
        }
    }
}

@Composable
private fun SavePngButton(
    bitmap: Bitmap?,
    busy: Boolean,
    prefix: String,
    onSaved: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    PrimaryButton(
        onClick = {
            val source = bitmap ?: return@PrimaryButton
            if (saving) return@PrimaryButton
            scope.launch {
                saving = true
                val name = "toTool_${prefix}_${System.currentTimeMillis() / 1000L}.png"
                val uri = withContext(Dispatchers.IO) {
                    runCatching { ImageOps.savePngToGallery(context, source, name) }.getOrNull()
                }
                saving = false
                if (uri != null) {
                    Toast.makeText(context, "已保存到 相册/toTool/$name", Toast.LENGTH_SHORT).show()
                    onSaved(name)
                } else {
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        },
        enabled = bitmap != null && !busy && !saving,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Save, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (saving) "正在保存…" else "保存到相册")
    }
}

private fun hexColor(color: Int): String =
    String.format(Locale.US, "#%06X", color and 0xFFFFFF)

/* ─────────────────────────── 纯色图制作 ─────────────────────────── */

@Composable
fun SolidColorImageTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var widthText by rememberSaveable { mutableStateOf("1080") }
    var heightText by rememberSaveable { mutableStateOf("1080") }
    var hue by rememberSaveable { mutableStateOf(210f) }
    var saturation by rememberSaveable { mutableStateOf(0.72f) }
    var value by rememberSaveable { mutableStateOf(0.82f) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val maxPixels = remember(context) { MemoryBudget.maxPixels(context, 4.0) }
    val valid = width != null && height != null &&
        width in 1..8192 && height in 1..8192 &&
        width.toLong() * height.toLong() <= maxPixels

    ToolBody(padding) {
        SectionLabel("尺寸")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = widthText,
                onValueChange = { widthText = it.filter { ch -> ch.isDigit() }.take(4) },
                label = "宽度",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextField(
                value = heightText,
                onValueChange = { heightText = it.filter { ch -> ch.isDigit() }.take(4) },
                label = "高度",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(argb)),
        )
        Text(
            "${hexColor(argb)} · ${width ?: "?"} × ${height ?: "?"}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        ColorWheelPicker(
            hue = hue,
            saturation = saturation,
            value = value,
            onHueChange = { hue = it },
            onSaturationChange = { saturation = it },
            onValueChange = { value = it },
        )
        if (!valid) {
            HintText("宽高需为 1–8192，且总像素不超过当前设备能安全创建的范围。")
        }
        PrimaryButton(
            onClick = {
                val w = width ?: return@PrimaryButton
                val h = height ?: return@PrimaryButton
                if (!valid || busy) return@PrimaryButton
                scope.launch {
                    busy = true
                    notice = null
                    error = null
                    val name = "toTool_solid_${System.currentTimeMillis() / 1000L}.png"
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(bitmap).drawColor(argb)
                                ImageOps.savePngToGallery(context, bitmap, name)
                            } finally {
                                bitmap.recycle()
                            }
                        }.getOrNull()
                    }
                    busy = false
                    if (result != null) {
                        notice = "已保存到 相册/toTool/$name"
                    } else {
                        error = "生成或保存失败，请检查尺寸与存储空间。"
                    }
                }
            },
            enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在生成…" else "生成并保存到相册") }
        notice?.let {
            MessageCard(
                title = null,
                message = it,
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        error?.let {
            MessageCard(
                title = "生成失败",
                message = it,
                container = MiuixTheme.colorScheme.errorContainer,
                content = MiuixTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/* ─────────────────────────── 九宫格切图 ─────────────────────────── */

@Composable
fun ImageGrid9Tool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberBitmapPicker(2400) { bitmap = it; notice = null; error = null }

    if (bitmap == null) {
        EmptyImageState(padding, "按 3×3 等分图片，并分别保存到相册", pick)
        return
    }
    val source = bitmap!!
    ToolBody(padding) {
        PickImageButton(pick)
        val gridImage = remember(source) { source.asImageBitmap() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(source.width.toFloat() / source.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer),
        ) {
            Image(
                bitmap = gridImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            ComposeCanvas(Modifier.fillMaxSize()) {
                val shadow = 4.dp.toPx()
                val line = 2.dp.toPx()
                for (index in 1..2) {
                    val x = size.width * index / 3f
                    val y = size.height * index / 3f
                    drawLine(Color.Black.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), shadow)
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), line)
                    drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), shadow)
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y), line)
                }
            }
        }
        HintText("按 3×3 等分；无法整除的像素会并入最后一格。输出保存到「相册/toTool」。")
        PrimaryButton(
            onClick = {
                if (busy) return@PrimaryButton
                scope.launch {
                    busy = true
                    notice = null
                    error = null
                    val stamp = System.currentTimeMillis() / 1000L
                    val saved = withContext(Dispatchers.IO) {
                        try {
                            var count = 0
                            for (row in 0 until 3) {
                                for (column in 0 until 3) {
                                    val left = column * source.width / 3
                                    val right = (column + 1) * source.width / 3
                                    val top = row * source.height / 3
                                    val bottom = (row + 1) * source.height / 3
                                    if (right <= left || bottom <= top) continue
                                    val cell = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                                    try {
                                        val name = "toTool_grid_${stamp}_${row * 3 + column + 1}.png"
                                        if (ImageOps.savePngToGallery(context, cell, name) != null) count++
                                    } finally {
                                        if (cell !== source) cell.recycle()
                                    }
                                }
                            }
                            count
                        } catch (e: Exception) {
                            0
                        }
                    }
                    busy = false
                    if (saved > 0) {
                        notice = "已保存 $saved 张九宫格图片到 相册/toTool。"
                    } else {
                        error = "切图保存失败。"
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在切图…" else "切成九宫格并保存") }
        notice?.let {
            MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
        }
        error?.let {
            MessageCard("切图失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }
}

/* ─────────────────────────── 图片文字化 ─────────────────────────── */

private const val DEFAULT_ASCII_GLYPHS = " .:-=+*#%@"

@Composable
fun ImageAsciiTool(padding: PaddingValues) {
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var cellSize by rememberSaveable { mutableStateOf(10f) }
    var glyphText by rememberSaveable { mutableStateOf(DEFAULT_ASCII_GLYPHS) }
    var backgroundArgb by rememberSaveable { mutableStateOf(AndroidColor.BLACK) }
    var showColorDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberBitmapPicker(1600) { source = it; error = null }

    val glyphs = remember(glyphText) { asciiGlyphs(glyphText) }
    val background = remember(backgroundArgb) { Color(backgroundArgb) }

    LaunchedEffect(source, cellSize, glyphs, backgroundArgb) {
        val input = source ?: return@LaunchedEffect
        busy = true
        error = null
        delay(100)
        result = withContext(Dispatchers.Default) {
            runCatching {
                renderAsciiArt(input, cellSize.roundToInt(), glyphs, backgroundArgb)
            }.getOrNull()
        }
        busy = false
        if (result == null) error = "生成文字化图片失败。"
    }

    if (source == null) {
        EmptyImageState(padding, "用不同密度和颜色的 ASCII 字符重新组成图片", pick)
        return
    }
    ToolBody(padding) {
        PickImageButton(pick)
        TextField(
            value = glyphText,
            onValueChange = { glyphText = it.filter(::isPrintableAscii).take(64) },
            label = "使用的字符（仅标准 ASCII）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HintText("只保留 ASCII 32–126 的可打印字符；顺序按从暗到亮使用，默认 \"$DEFAULT_ASCII_GLYPHS\"。")
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "背景颜色",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(background)
                        .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { showColorDialog = true },
                )
            }
        }
        Text("字符大小：${cellSize.roundToInt()} px", style = MiuixTheme.textStyles.body2)
        Slider(
            value = cellSize,
            onValueChange = { cellSize = it },
            valueRange = 4f..24f,
        )
        BitmapPreviewCard(
            bitmap = result ?: source,
            modifier = Modifier.fillMaxWidth().height(280.dp),
            placeholder = "正在生成…",
            busy = busy,
        )
        HintText("每个字符格取原图中心像素的颜色与亮度；字符越密表示原图越亮。")
        SavePngButton(result, busy, "ascii")
        error?.let {
            MessageCard("生成失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }

    if (showColorDialog) {
        AsciiBackgroundColorDialog(
            initial = background,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                backgroundArgb = it.toArgb()
                showColorDialog = false
            },
        )
    }
}

@Composable
private fun AsciiBackgroundColorDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val hsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember(initial) { mutableStateOf(hsv[0]) }
    var sat by remember(initial) { mutableStateOf(hsv[1]) }
    var value by remember(initial) { mutableStateOf(hsv[2]) }
    AppDialog(
        show = true,
        title = "背景颜色",
        onDismiss = onDismiss,
        confirmText = "使用这个颜色",
        onConfirm = { onConfirm(Color.hsv(hue, sat, value)) },
    ) {
        ColorWheelPicker(
            hue = hue,
            saturation = sat,
            value = value,
            onHueChange = { hue = it },
            onSaturationChange = { sat = it },
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun isPrintableAscii(ch: Char): Boolean = ch.code in 32..126

private fun asciiGlyphs(text: String): String {
    val filtered = text.filter(::isPrintableAscii)
    return if (filtered.any { it != ' ' }) filtered else DEFAULT_ASCII_GLYPHS
}

private fun renderAsciiArt(
    source: Bitmap,
    requestedCell: Int,
    glyphs: String,
    background: Int,
): Bitmap {
    val cell = requestedCell.coerceIn(4, 32)
    val columns = (source.width / cell).coerceAtLeast(1)
    val rows = (source.height / cell).coerceAtLeast(1)
    val out = Bitmap.createBitmap(columns * cell, rows * cell, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(background)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        textSize = cell * 0.95f
    }
    val metrics = paint.fontMetrics
    val baselineOffset = -(metrics.ascent + metrics.descent) / 2f
    val glyphList = glyphs.ifBlank { DEFAULT_ASCII_GLYPHS }
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val x = (column * cell + cell / 2).coerceIn(0, source.width - 1)
            val y = (row * cell + cell / 2).coerceIn(0, source.height - 1)
            val pixel = source.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 24) continue
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            val luminance = (0.299f * red + 0.587f * green + 0.114f * blue).toInt().coerceIn(0, 255)
            val glyph = glyphList[luminance * (glyphList.length - 1) / 255]
            if (glyph == ' ') continue
            paint.color = (0xFF shl 24) or
                (max(red, 56) shl 16) or
                (max(green, 56) shl 8) or
                max(blue, 56)
            canvas.drawText(
                glyph.toString(),
                column * cell + cell / 2f,
                row * cell + cell / 2f + baselineOffset,
                paint,
            )
        }
    }
    return out
}

/* ─────────────────────────── 图片转素描 ─────────────────────────── */

@Composable
fun ImageSketchTool(padding: PaddingValues) {
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var strength by rememberSaveable { mutableStateOf(2f) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberBitmapPicker(1600) { source = it; error = null }

    LaunchedEffect(source, strength) {
        val input = source ?: return@LaunchedEffect
        busy = true
        error = null
        delay(120)
        result = withContext(Dispatchers.Default) {
            runCatching { renderSketch(input, strength) }.getOrNull()
        }
        busy = false
        if (result == null) error = "生成素描失败。"
    }

    if (source == null) {
        EmptyImageState(padding, "把照片转成铅笔素描效果", pick)
        return
    }
    ToolBody(padding) {
        PickImageButton(pick)
        Text("强度：${strength.roundToInt()}", style = MiuixTheme.textStyles.body2)
        Slider(
            value = strength,
            onValueChange = { strength = it },
            valueRange = 1f..4f,
        )
        BitmapPreviewCard(
            bitmap = result ?: source,
            modifier = Modifier.fillMaxWidth().height(280.dp),
            placeholder = "正在处理…",
            busy = busy,
        )
        HintText("灰度反相 + 模糊 + 颜色减淡的经典铅笔素描算法；强度越高线条越粗。")
        SavePngButton(result, busy, "sketch")
        error?.let {
            MessageCard("生成失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun renderSketch(source: Bitmap, strength: Float): Bitmap {
    val width = source.width
    val height = source.height
    val gray = ImageOps.grayscale(source)
    val grayPixels = IntArray(width * height)
    gray.getPixels(grayPixels, 0, width, 0, 0, width, height)
    val inverted = IntArray(width * height)
    for (index in inverted.indices) {
        val value = 255 - (grayPixels[index] and 0xFF)
        inverted[index] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }
    val invertedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    invertedBitmap.setPixels(inverted, 0, width, 0, 0, width, height)
    val radius = (min(width, height) / 240f * strength).roundToInt().coerceIn(1, 40)
    val blurred = ImageOps.stackBlur(invertedBitmap, radius)
    val blurPixels = IntArray(width * height)
    blurred.getPixels(blurPixels, 0, width, 0, 0, width, height)
    val outputPixels = IntArray(width * height)
    for (index in outputPixels.indices) {
        val grayValue = grayPixels[index] and 0xFF
        val blurValue = blurPixels[index] and 0xFF
        val dodge = if (blurValue >= 255) 255 else (grayValue * 255 / (255 - blurValue)).coerceIn(0, 255)
        outputPixels[index] = (0xFF shl 24) or (dodge shl 16) or (dodge shl 8) or dodge
    }
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.setPixels(outputPixels, 0, width, 0, 0, width, height)
    gray.recycle()
    invertedBitmap.recycle()
    blurred.recycle()
    return output
}

/* ─────────────────────────── 图片取色 ─────────────────────────── */

@Composable
fun ImageColorPickerTool(padding: PaddingValues) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedColor by remember { mutableStateOf(AndroidColor.BLACK) }
    var selectedPoint by remember { mutableStateOf<Offset?>(null) }
    var palette by remember { mutableStateOf<List<Int>>(emptyList()) }
    val pick = rememberBitmapPicker(1600) { bitmap = it; selectedPoint = Offset(0.5f, 0.5f) }

    LaunchedEffect(bitmap) {
        val source = bitmap ?: return@LaunchedEffect
        selectedColor = source.getPixel(source.width / 2, source.height / 2)
        palette = withContext(Dispatchers.Default) {
            runCatching { extractPalette(source) }.getOrDefault(emptyList())
        }
    }

    if (bitmap == null) {
        EmptyImageState(padding, "点击图片任意位置读取像素颜色", pick)
        return
    }
    val source = bitmap!!
    ToolBody(padding) {
        PickImageButton(pick)
        val pickerImage = remember(source) { source.asImageBitmap() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(source.width.toFloat() / source.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(12.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .pointerInput(source) {
                    detectTapGestures { offset ->
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val nx = (offset.x / size.width).coerceIn(0f, 1f)
                        val ny = (offset.y / size.height).coerceIn(0f, 1f)
                        val x = (nx * (source.width - 1)).roundToInt()
                        val y = (ny * (source.height - 1)).roundToInt()
                        selectedColor = source.getPixel(x, y)
                        selectedPoint = Offset(nx, ny)
                    }
                },
        ) {
            Image(
                bitmap = pickerImage,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            ComposeCanvas(Modifier.fillMaxSize()) {
                selectedPoint?.let { point ->
                    val center = Offset(point.x * size.width, point.y * size.height)
                    drawCircle(Color.White, 9.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, 13.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                }
            }
        }
        HintText("点击画面取色；下方主色也会随图片自动提取。")
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(selectedColor))
                        .border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(hexColor(selectedColor), style = MiuixTheme.textStyles.title4)
                    val red = (selectedColor shr 16) and 0xFF
                    val green = (selectedColor shr 8) and 0xFF
                    val blue = selectedColor and 0xFF
                    Text(
                        "RGB($red, $green, $blue)",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                IconButton(onClick = { copyText(context, hexColor(selectedColor), "颜色") }) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制颜色",
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (palette.isNotEmpty()) {
            SectionLabel("主色")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                palette.forEach { color ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            selectedColor = color
                            selectedPoint = null
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(1.dp, MiuixTheme.colorScheme.dividerLine, CircleShape),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            hexColor(color),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

private fun extractPalette(source: Bitmap, maxColors: Int = 8): List<Int> {
    val width = source.width
    val height = source.height
    val step = max(1, min(width, height) / 160)
    val counts = HashMap<Int, Int>()
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = source.getPixel(x, y)
            if (((pixel ushr 24) and 0xFF) >= 16) {
                val key = (((pixel shr 16) and 0xF0) shl 4) or
                    ((pixel shr 8) and 0xF0) or
                    ((pixel and 0xF0) ushr 4)
                counts[key] = (counts[key] ?: 0) + 1
            }
            x += step
        }
        y += step
    }
    return counts.entries
        .sortedByDescending { it.value }
        .take(maxColors)
        .map { (key, _) ->
            val red = ((key ushr 8) and 0xF) * 17
            val green = ((key ushr 4) and 0xF) * 17
            val blue = (key and 0xF) * 17
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
}

/* ─────────────────────────── 图片转 Base64 ─────────────────────────── */

@Composable
fun ImageBase64Tool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(-1L) }
    var mime by remember { mutableStateOf<String?>(null) }
    var base64 by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var mode by rememberSaveable { mutableStateOf(Base64Mode.ENCODE) }
    var decodeInput by remember { mutableStateOf("") }
    var decoded by remember { mutableStateOf<DecodedBase64Image?>(null) }
    var decodeBusy by remember { mutableStateOf(false) }
    var decodeError by remember { mutableStateOf<String?>(null) }
    var decodeNotice by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) {
            uri = picked
            name = queryFileName(context, picked)
            size = queryFileSize(context, picked)
            mime = runCatching { context.contentResolver.getType(picked) }.getOrNull()
            base64 = null
            error = null
            notice = null
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        val text = base64
        if (target != null && text != null) {
            scope.launch {
                busy = true
                val success = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(target)
                            ?: throw IOException("无法写入文件")
                        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }.isSuccess
                }
                busy = false
                if (success) {
                    notice = "Base64 文本已导出。"
                } else {
                    error = "导出 Base64 文本失败。"
                }
            }
        }
    }

    LaunchedEffect(uri) {
        val source = uri ?: return@LaunchedEffect
        busy = true
        error = null
        val type = mime
        val encoded = withContext(Dispatchers.IO) {
            runCatching {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                context.contentResolver.openInputStream(source)?.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 32L * 1024 * 1024) {
                            throw IOException("图片超过 32 MB，无法安全转换为 Base64")
                        }
                        output.write(buffer, 0, read)
                    }
                } ?: throw IOException("无法读取图片")
                val raw = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                if (!type.isNullOrBlank()) "data:$type;base64,$raw" else raw
            }
        }
        busy = false
        base64 = encoded.getOrNull()
        error = encoded.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    fun decode() {
        val text = decodeInput
        if (text.isBlank() || decodeBusy) return
        decodeBusy = true
        decodeError = null
        decodeNotice = null
        decoded = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { decodeBase64Image(text) } }
            decodeBusy = false
            result.onSuccess { decoded = it }
                .onFailure { decodeError = it.message ?: it.javaClass.simpleName }
        }
    }

    fun saveDecoded() {
        val image = decoded ?: return
        if (decodeBusy) return
        decodeBusy = true
        decodeError = null
        decodeNotice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = "base64_${System.currentTimeMillis()}.${image.extension}"
                    val (where, bytes) = saveImageToGallery(context, fileName, image.mime) { out ->
                        out.write(image.bytes)
                    }
                    "$where(${humanSize(bytes)})"
                }
            }
            decodeBusy = false
            result.onSuccess { decodeNotice = "已保存到 $it" }
                .onFailure { decodeError = it.message ?: "保存失败" }
        }
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip(
                selected = mode == Base64Mode.ENCODE,
                onClick = { mode = Base64Mode.ENCODE },
                label = "图片转 Base64",
                modifier = Modifier.weight(1f),
            )
            SelectChip(
                selected = mode == Base64Mode.DECODE,
                onClick = { mode = Base64Mode.DECODE },
                label = "Base64 转图片",
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (mode == Base64Mode.ENCODE && uri == null) {
                EmptyImageState(
                    PaddingValues(0.dp),
                    "把图片文件转换成 Base64 文本",
                    { picker.launch("image/*") },
                )
            } else if (mode == Base64Mode.ENCODE) {
                ToolBody(PaddingValues(0.dp)) {
        PickImageButton { picker.launch("image/*") }
        Text(
            "${name ?: "图片"} · ${if (size > 0) humanSize(size) else "未知大小"} · ${mime ?: "未知类型"}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (busy && base64 == null && error == null) {
            HintText("正在读取并编码…")
        }
        base64?.let { text ->
            Text(
                "Base64 字符数：${text.length}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            ResultBox(text = text, label = "Base64", monospace = true, maxLines = 10)
            SecondaryButton(
                onClick = {
                    val suggested = baseNameOf(name ?: "image") + "_base64.txt"
                    exportPicker.launch(suggested)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出为文本文件") }
        }
        HintText("输出带 data:<MIME>;base64, 标记；直接编码原图字节，不重新压缩。较大的图片建议导出文本。")
        notice?.let {
            MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
        }
        error?.let {
            MessageCard("Base64 转换失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
        }
                }
            } else {
                ToolBody(PaddingValues(0.dp)) {
                    TextField(
                        value = decodeInput,
                        onValueChange = {
                            decodeInput = it
                            decoded = null
                            decodeError = null
                            decodeNotice = null
                        },
                        label = "粘贴 Base64 文本",
                        minLines = 6,
                        maxLines = 10,
                        enabled = !decodeBusy,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (decodeInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    decodeInput = ""
                                    decoded = null
                                    decodeError = null
                                    decodeNotice = null
                                }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "清空 Base64",
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                    )
                    HintText("支持 data:image/png;base64, 等 MIME 标记；标记会保留，并用于确定保存格式。")
                    PrimaryButton(
                        onClick = ::decode,
                        enabled = decodeInput.isNotBlank() && !decodeBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (decodeBusy) "正在处理..." else "解码预览") }
                    if (decodeBusy) {
                        LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                    }
                    decoded?.let { image ->
                        Text(
                            "${image.mime} · ${humanSize(image.bytes.size.toLong())} · ${image.width}×${image.height}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        BitmapPreviewCard(
                            bitmap = image.preview,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                        PrimaryButton(
                            onClick = ::saveDecoded,
                            enabled = !decodeBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("保存到相册") }
                    }
                    decodeNotice?.let {
                        MessageCard(null, it, MiuixTheme.colorScheme.secondaryContainer, MiuixTheme.colorScheme.onSecondaryContainer)
                    }
                    decodeError?.let {
                        MessageCard("Base64 解码失败", it, MiuixTheme.colorScheme.errorContainer, MiuixTheme.colorScheme.onErrorContainer)
                    }
                    HintText("解码结果会写入「相册/toTool」；MIME 标记决定保存格式。")
                }
            }
        }
    }
}

private enum class Base64Mode { ENCODE, DECODE }

private class DecodedBase64Image(
    val bytes: ByteArray,
    val mime: String,
    val extension: String,
    val width: Int,
    val height: Int,
    val preview: Bitmap,
)

private val BASE64_DATA_URI = Regex("""^data:([^;,]*)((?:;[^,]*)*),""", RegexOption.IGNORE_CASE)

/** 解码 Base64 / data URI;MIME 标记只用于解析,不会改写用户输入的文本。 */
private fun decodeBase64Image(input: String): DecodedBase64Image {
    val trimmed = input.trim()
    if (trimmed.isBlank()) throw IOException("请先粘贴 Base64 文本")
    if (trimmed.length > 64 * 1024 * 1024) throw IOException("Base64 文本过大")
    val marker = BASE64_DATA_URI.find(trimmed)
    val markerMime = marker?.groupValues?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.contains('/') }
    val payload = if (marker != null) trimmed.substring(marker.range.last + 1) else trimmed
    val clean = payload.filterNot { it.isWhitespace() }
    if (clean.isBlank()) throw IOException("Base64 内容为空")
    val bytes = decodeBase64Bytes(clean)
    if (bytes.isEmpty()) throw IOException("Base64 内容为空")
    val mime = (markerMime ?: sniffImageMime(bytes))?.lowercase(Locale.ROOT)
        ?: throw IOException("无法识别图片格式;请确认 Base64 是图片数据")
    if (!mime.startsWith("image/")) {
        throw IOException("MIME 标记不是图片类型:$mime")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException("Base64 数据不是可解码的图片")
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = base64SampleSize(bounds.outWidth, bounds.outHeight, 1600)
    }
    val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: throw IOException("图片解码失败")
    return DecodedBase64Image(
        bytes = bytes,
        mime = mime,
        extension = base64Extension(mime),
        width = bounds.outWidth,
        height = bounds.outHeight,
        preview = preview,
    )
}

private fun decodeBase64Bytes(clean: String): ByteArray {
    val padded = if (clean.length % 4 == 0) clean else clean + "=".repeat(4 - clean.length % 4)
    val attempts = listOf(Base64.DEFAULT, Base64.URL_SAFE)
    var last: Exception? = null
    for (flags in attempts) {
        try {
            val decoded = Base64.decode(padded, flags)
            if (decoded.isNotEmpty()) return decoded
        } catch (e: Exception) {
            last = e
        }
    }
    throw IOException("不是有效的 Base64 文本", last)
}

private fun sniffImageMime(bytes: ByteArray): String? {
    if (bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    ) return "image/png"
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    ) return "image/jpeg"
    if (bytes.size >= 6 && bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray())) return "image/gif"
    if (bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
    ) return "image/webp"
    if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return "image/bmp"
    return null
}

private fun base64Extension(mime: String): String = when (mime) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp", "image/x-ms-bmp" -> "bmp"
    "image/heic", "image/heif" -> "heic"
    else -> mime.substringAfter('/', "img")
        .substringBefore('+')
        .filter { it.isLetterOrDigit() }
        .ifBlank { "img" }
}

private fun base64SampleSize(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (max(width, height) / sample > maxSide) sample *= 2
    return sample
}
