package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.ToolBody
import kotlinx.coroutines.delay
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import com.mingdai.toTool.ui.components.PrimaryButton
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.ui.components.displayStyle
import com.mingdai.toTool.ui.components.tabular
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.rememberHaptics
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import top.yukonga.miuix.kmp.basic.Card
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.RectF as AndroidRectF
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mingdai.toTool.R
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.ColorWheelPicker
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.WarnColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator

/* ------------------------------- 19. 二维码生成 ----------------------------- */

private enum class QrIconMode(val label: String) {
    NONE("无"),
    APP("应用图标"),
    TEXT("文字"),
    IMAGE("图片"),
}

/**
 * 编码文本。带中央图标时必须用最高的容错级别 H（可容忍约 30% 的码字损坏），
 * 否则图标盖掉的那一块会让二维码扫不出来。
 */
private fun encodeQr(text: String, highEc: Boolean): BitMatrix? {
    if (text.isBlank()) return null
    val hints = mapOf(
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to (if (highEc) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M),
    )
    // 宽高传 0：得到“最小尺寸”的矩阵（每个格子正好 1 像素），缩放交给 renderQr 按整数倍放大，边缘才锐利
    return try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    } catch (e: Exception) {
        null
    }
}

private fun colorChannel(argb: Int, shift: Int): Double {
    val c = ((argb shr shift) and 0xFF) / 255.0
    return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
}

/** WCAG 相对亮度。 */
private fun relativeLuminance(argb: Int): Double =
    0.2126 * colorChannel(argb, 16) + 0.7152 * colorChannel(argb, 8) + 0.0722 * colorChannel(argb, 0)

/** WCAG 对比度（1 ~ 21）。 */
private fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** 把矩阵画成位图：每个格子取整数像素，整张图 = 格子数 × 格子边长，边缘不会糊。 */
private fun renderQr(matrix: BitMatrix, fg: Int, bg: Int, icon: Bitmap?, iconScale: Float, targetPx: Int): Bitmap {
    val n = matrix.width
    val cell = maxOf(1, targetPx / n)
    val size = cell * n
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    canvas.drawColor(bg)
    val paint = AndroidPaint().apply {
        color = fg
        isAntiAlias = false
    }
    for (y in 0 until n) {
        for (x in 0 until n) {
            if (matrix.get(x, y)) {
                canvas.drawRect(
                    (x * cell).toFloat(),
                    (y * cell).toFloat(),
                    ((x + 1) * cell).toFloat(),
                    ((y + 1) * cell).toFloat(),
                    paint,
                )
            }
        }
    }
    if (icon != null) {
        val box = size * iconScale
        val left = (size - box) / 2f
        val pad = cell.toFloat() // 图标四周留一圈 1 个格子宽的底板，图标和码点之间不粘在一起
        val corner = box * 0.18f
        val plate = AndroidRectF(left - pad, left - pad, left + box + pad, left + box + pad)
        val platePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = bg }
        canvas.drawRoundRect(plate, corner + pad, corner + pad, platePaint)
        val dst = AndroidRectF(left, left, left + box, left + box)
        canvas.save()
        val clip = AndroidPath().apply { addRoundRect(dst, corner, corner, AndroidPath.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(icon, null, dst, AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }
    return bmp
}

private fun appIconBitmap(context: Context, sizePx: Int = 256): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bmp
}

private fun textIconBitmap(text: String, color: Int, sizePx: Int = 256): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textAlign = AndroidPaint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = sizePx * (if (text.length > 1) 0.5f else 0.72f)
    }
    // 垂直居中：基线 = 中心 − (ascent + descent) / 2
    val baseline = sizePx / 2f - (paint.ascent() + paint.descent()) / 2f
    canvas.drawText(text, sizePx / 2f, baseline, paint)
    return bmp
}

/** 相册图片 → 居中裁成正方形，缩到 256px，当作中央图标用。 */
private fun squareIcon(src: Bitmap, sizePx: Int = 256): Bitmap {
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    return Bitmap.createScaledBitmap(square, sizePx, sizePx, true)
}

private fun buildQrIcon(context: Context, mode: QrIconMode, text: String, image: Bitmap?, fg: Int): Bitmap? =
    when (mode) {
        QrIconMode.NONE -> null
        QrIconMode.APP -> appIconBitmap(context)
        QrIconMode.TEXT -> text.trim().takeIf { it.isNotEmpty() }?.let { textIconBitmap(it, fg) }
        QrIconMode.IMAGE -> image
    }

private val QrForegroundPresets = listOf(
    Color(0xFF000000), Color(0xFF1A237E), Color(0xFF0D47A1), Color(0xFF1B5E20),
    Color(0xFFB71C1C), Color(0xFF4A148C), Color(0xFFBF360C),
)
private val QrBackgroundPresets = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF8E1), Color(0xFFECEFF1), Color(0xFFE3F2FD),
    Color(0xFFE8F5E9), Color(0xFFFCE4EC), Color(0xFFF3E5F5),
)

@Composable
private fun QrSwatchRow(
    label: String,
    presets: List<Color>,
    selected: Color,
    onSelect: (Color) -> Unit,
    onCustom: () -> Unit,
) {
    val theme = MiuixTheme.colorScheme
    val isCustom = presets.none { it.toArgb() == selected.toArgb() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MiuixTheme.textStyles.body2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            presets.forEach { c ->
                val on = c.toArgb() == selected.toArgb()
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (on) 3.dp else 1.dp, if (on) theme.primary else theme.outline, CircleShape)
                        .clickable { onSelect(c) },
                )
            }
            // 自定义：彩虹圆环，选中自定义色时显示高亮边框，并在圆里填上当前色
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759),
                                Color(0xFF00C7BE), Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF3B30),
                            ),
                        ),
                    )
                    .border(if (isCustom) 3.dp else 1.dp, if (isCustom) theme.primary else theme.outline, CircleShape)
                    .clickable(onClick = onCustom),
                contentAlignment = Alignment.Center,
            ) {
                if (isCustom) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(selected).border(1.dp, Color.White, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun QrColorDialog(
    show: Boolean,
    title: String,
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
        show = show,
        title = title,
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

@Composable
fun QrTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme

    // text 是输入框里正在编辑的内容；generated 只有点“生成”时才更新。
    // 编码 / 渲染都只依赖 generated，所以打字不会触发任何重新生成（之前每敲一个字都重算，很卡）。
    var text by remember { mutableStateOf("https://github.com") }
    var generated by remember { mutableStateOf("https://github.com") }

    var fg by remember { mutableStateOf(Color.Black) }
    var bg by remember { mutableStateOf(Color.White) }
    var pickingColor by remember { mutableStateOf<Int?>(null) } // 0 = 前景，1 = 背景

    var iconMode by remember { mutableStateOf(QrIconMode.NONE) }
    var iconText by remember { mutableStateOf("码") }
    var iconImage by remember { mutableStateOf<Bitmap?>(null) }
    var iconScale by remember { mutableStateOf(0.22f) }

    var matrix by remember { mutableStateOf<BitMatrix?>(null) }
    var encodeFailed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }

    val highEc = iconMode != QrIconMode.NONE
    LaunchedEffect(generated, highEc) {
        val m = withContext(Dispatchers.Default) { encodeQr(generated, highEc) }
        matrix = m
        encodeFailed = m == null && generated.isNotBlank()
        if (m == null) preview = null
    }
    LaunchedEffect(matrix, fg, bg, iconMode, iconText, iconImage, iconScale) {
        val m = matrix ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            val icon = buildQrIcon(context, iconMode, iconText, iconImage, fg.toArgb())
            renderQr(m, fg.toArgb(), bg.toArgb(), icon, iconScale, 1080)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    ImageOps.uriToBitmap(context, uri, maxSide = 512)?.let { squareIcon(it) }
                }
                if (bmp == null) {
                    Toast.makeText(context, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                } else {
                    iconImage = bmp
                    iconMode = QrIconMode.IMAGE
                }
            }
        }
    }

    fun save() {
        val m = matrix ?: return
        if (saving) return
        saving = true
        scope.launch {
            val savedName = try {
                withContext(Dispatchers.Default) {
                    val icon = buildQrIcon(context, iconMode, iconText, iconImage, fg.toArgb())
                    val bmp = renderQr(m, fg.toArgb(), bg.toArgb(), icon, iconScale, 1280)
                    val name = "toTool_qr_${System.currentTimeMillis() / 1000L}.png"
                    // 写入失败时 ImageOps 会自己弹出带原因的 Toast，这里只处理成功的情况
                    ImageOps.savePngToGallery(context, bmp, name)?.let { name }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                null
            } finally {
                saving = false
            }
            if (savedName != null) {
                Toast.makeText(context, "已保存到 图片/toTool/$savedName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val ratio = contrastRatio(fg.toArgb(), bg.toArgb())
    val inverted = relativeLuminance(fg.toArgb()) > relativeLuminance(bg.toArgb())

    ToolBody(padding) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "文本 / 链接",
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        PrimaryButton(
            onClick = { generated = text.trim() },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("生成二维码")
        }

        val shown = preview
        when {
            generated.isBlank() -> HintText("输入内容后，点击「生成二维码」")
            encodeFailed -> Text(
                "无法生成二维码，文本可能过长（带图标时最多约 1200 个字符）",
                color = colors.error,
                style = MiuixTheme.textStyles.footnote1,
            )
            shown == null -> Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "二维码",
                            modifier = Modifier
                                .widthIn(max = 380.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                if (text.trim() != generated) {
                    HintText("输入框的内容已修改，点击「生成二维码」后才会更新")
                }
            }
        }

        SectionLabel("颜色")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                QrSwatchRow("前景色（码点）", QrForegroundPresets, fg, { fg = it }, { pickingColor = 0 })
                QrSwatchRow("背景色", QrBackgroundPresets, bg, { bg = it }, { pickingColor = 1 })
                if (ratio < 3.0 || inverted) {
                    Text(
                        if (inverted) "前景色比背景色更浅（反色码），不少扫码器无法识别，建议换成深色码点 + 浅色背景。"
                        else "前景色与背景色对比度偏低（${String.format(Locale.US, "%.1f", ratio)}:1），可能扫不出来。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = WarnColor,
                    )
                }
            }
        }

        SectionLabel("中央图标")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QrIconMode.entries.forEach { mode ->
                        SelectChip(
                            selected = iconMode == mode,
                            onClick = {
                                if (mode == QrIconMode.IMAGE && iconImage == null) {
                                    imagePicker.launch("image/*")
                                } else {
                                    iconMode = mode
                                }
                            },
                            label = mode.label,
                        )
                    }
                }
                when (iconMode) {
                    QrIconMode.TEXT -> TextField(
                        value = iconText,
                        onValueChange = { iconText = it.take(2) },
                        label = "图标文字（最多 2 个字）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    QrIconMode.IMAGE -> SecondaryButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (iconImage == null) "从相册选择图片" else "更换图片")
                    }
                    else -> {}
                }
                if (iconMode != QrIconMode.NONE) {
                    Column {
                        Text("图标大小：${(iconScale * 100).toInt()}%", style = MiuixTheme.textStyles.body2)
                        Slider(
                            value = iconScale,
                            onValueChange = { iconScale = it },
                            valueRange = 0.12f..0.28f,
                        )
                    }
                    Text(
                        "带图标时已自动启用最高容错级别（H）。图标越大越难扫，建议不超过 25%。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        PrimaryButton(
            onClick = { save() },
            enabled = preview != null && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("导出到相册（高清 PNG）")
            }
        }
    }

    QrColorDialog(
        show = pickingColor != null,
        title = if (pickingColor == 0) "自定义前景色" else "自定义背景色",
        initial = if (pickingColor == 0) fg else bg,
        onDismiss = { pickingColor = null },
        onConfirm = { picked ->
            if (pickingColor == 0) fg = picked else bg = picked
            pickingColor = null
        },
    )
}

/* ------------------------------- 20. 颜色选择器 ----------------------------- */

private fun rgbToHsl(r: Int, g: Int, b: Int): String {
    val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
    val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
    val l = (max + min) / 2f
    val h: Float
    val s: Float
    if (d == 0f) { h = 0f; s = 0f } else {
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            rf -> (gf - bf) / d + if (gf < bf) 6f else 0f
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
    }
    val hh = (h * 60f).toInt()
    val ss = (s * 100f).toInt()
    val ll = (l * 100f).toInt()
    return "hsl($hh, $ss%, $ll%)"
}

@Composable
private fun ColorSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Text("${label}: ${value.toInt()}", style = MiuixTheme.textStyles.body2)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..255f,
        )
    }
}

@Composable
fun ColorTool(padding: PaddingValues) {
    var r by remember { mutableStateOf(0f) }
    var g by remember { mutableStateOf(105f) }
    var b by remember { mutableStateOf(180f) }
    val color = Color(r / 255f, g / 255f, b / 255f)
    val hex = String.format(Locale.US, "#%02X%02X%02X", r.toInt(), g.toInt(), b.toInt())
    val rgb = "rgb(${r.toInt()}, ${g.toInt()}, ${b.toInt()})"
    val hsl = rgbToHsl(r.toInt(), g.toInt(), b.toInt())
    ToolBody(padding) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color),
        )
        ColorSlider("红 R", r) { r = it }
        ColorSlider("绿 G", g) { g = it }
        ColorSlider("蓝 B", b) { b = it }
        ResultBox(hex, label = "HEX", monospace = true)
        ResultBox(rgb, label = "RGB")
        ResultBox(hsl, label = "HSL")
    }
}

/* ------------------------------- 21. 秒表 ----------------------------------- */

private fun formatElapsed(ms: Long): String {
    val totalCs = ms / 10L
    val cs = totalCs % 100L
    val totalSec = totalCs / 100L
    val sec = totalSec % 60L
    val totalMin = totalSec / 60L
    val min = totalMin % 60L
    val hour = totalMin / 60L
    return if (hour > 0L) {
        String.format(Locale.US, "%d:%02d:%02d.%02d", hour, min, sec, cs)
    } else {
        String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs)
    }
}

/** 数字单独成一个函数：每一帧只重组它，下面的按钮和分段列表不用跟着刷新。 */
@Composable
private fun StopwatchDigits(elapsed: () -> Long) {
    Text(
        text = formatElapsed(elapsed()),
        style = displayStyle(54.sp),
        color = MiuixTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 分段记录：每圈用时 + 累计时间；记录满 3 圈后，最快的标绿、最慢的标红。最新的一圈在最上面。 */
@Composable
private fun LapList(laps: List<Long>) {
    val colors = MiuixTheme.colorScheme
    // laps 存的是累计时间，每圈用时 = 与上一圈累计时间的差
    val splits = laps.mapIndexed { i, total -> total - (laps.getOrNull(i - 1) ?: 0L) }
    val best = if (laps.size >= 3) splits.min() else null
    val worst = if (laps.size >= 3) splits.max() else null
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("圈", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.width(44.dp))
                Text("本圈用时", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f))
                Text("累计", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            }
            for (i in laps.indices.reversed()) {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
                val tint = when {
                    best != null && splits[i] == best -> LapBestColor
                    worst != null && splits[i] == worst -> LapWorstColor
                    else -> colors.onSurface
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#${i + 1}", style = MiuixTheme.textStyles.body2.tabular(),
                        color = colors.onSurfaceVariantSummary, modifier = Modifier.width(44.dp))
                    Text(formatElapsed(splits[i]), style = MiuixTheme.textStyles.body1.tabular(),
                        color = tint, modifier = Modifier.weight(1f))
                    Text(formatElapsed(laps[i]), style = MiuixTheme.textStyles.body2.tabular(),
                        color = colors.onSurfaceVariantSummary)
                }
            }
        }
    }
}

private val LapBestColor = Color(0xFF2EAD4F)
private val LapWorstColor = Color(0xFFE5484D)

@Composable
fun StopwatchTool(padding: PaddingValues) {
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var laps by remember { mutableStateOf(listOf<Long>()) }
    // 只关心“有没有计过时”，不要让按钮区域每一帧都跟着 elapsed 重组
    val hasTime by remember { derivedStateOf { elapsed > 0L } }

    LaunchedEffect(running) {
        if (running) {
            val start = SystemClock.elapsedRealtime() - elapsed
            while (running) {
                withFrameMillis { elapsed = SystemClock.elapsedRealtime() - start }
            }
        }
    }
    KeepScreenOn(running)

    // fillMaxSize + verticalScroll + Arrangement.Center：内容少时整体垂直居中；
    // 每多一条分段记录，内容变高，整体就往上抬一点；超出屏幕后再滚动。
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StopwatchDigits(elapsed = { elapsed })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                SecondaryButton(
                    onClick = { running = false; elapsed = 0L; laps = emptyList() },
                    enabled = hasTime,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("重置")
                }
                PrimaryButton(
                    onClick = { running = !running },
                    modifier = Modifier.weight(1.3f),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(if (running) "暂停" else if (hasTime) "继续" else "开始")
                }
                SecondaryButton(
                    onClick = { if (running) laps = laps + elapsed },
                    enabled = running,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Flag, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("分段")
                }
            }

            if (laps.isNotEmpty()) LapList(laps)
        }
    }
}

/* ------------------------------- 22. 手电筒（常亮 + SOS） -------------------- */

private fun findFlashCameraId(cm: CameraManager): String? = runCatching {
    cm.cameraIdList.firstOrNull { id ->
        (cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as? Boolean) == true
    }
}.getOrNull()

private enum class TorchMode(val label: String) {
    STEADY("常亮"),
    SOS("SOS"),
}

/** 国际莫尔斯 SOS：· · ·  — — —  · · ·，以“点 = 1 个单位”计时。 */
private val SosPattern = intArrayOf(1, 1, 1, 3, 3, 3, 1, 1, 1)

/** 一个单位的时长（毫秒）：点 160ms、划 480ms。 */
private const val SosUnitMs = 160L

/** 第 [index] 个信号结束后的停顿（单位数）：同一个字母内 1，字母之间 3，整组 SOS 结束后 7。 */
private fun sosGapUnits(index: Int): Int = when (index) {
    2, 5 -> 3
    SosPattern.lastIndex -> 7
    else -> 1
}

@Composable
fun FlashlightTool(padding: PaddingValues) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val haptics = rememberHaptics()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val cameraId = remember { findFlashCameraId(cameraManager) }
    var mode by remember { mutableStateOf(TorchMode.STEADY) }
    // active：用户是否打开了手电；lit：灯此刻是不是亮着（SOS 闪烁时会不断变化）
    var active by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }

    fun torch(on: Boolean) {
        val id = cameraId ?: return
        runCatching { cameraManager.setTorchMode(id, on) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && cameraId != null) active = true
    }

    // 驱动闪光灯。用 collectLatest：active 或 mode 一变，先等上一轮收尾（关灯）结束，再开始新的一轮，
    // 不会出现“旧协程的收尾把新协程刚打开的灯又关掉”。
    LaunchedEffect(cameraId) {
        if (cameraId == null) return@LaunchedEffect
        snapshotFlow { active to mode }.collectLatest { (isActive, currentMode) ->
            if (!isActive) {
                torch(false)
                lit = false
                return@collectLatest
            }
            try {
                if (currentMode == TorchMode.STEADY) {
                    torch(true)
                    lit = true
                    awaitCancellation()
                } else {
                    while (true) {
                        for (i in SosPattern.indices) {
                            torch(true)
                            lit = true
                            delay(SosPattern[i] * SosUnitMs)
                            torch(false)
                            lit = false
                            delay(sosGapUnits(i) * SosUnitMs)
                        }
                    }
                }
            } finally {
                torch(false)
                lit = false
            }
        }
    }

    // 离开页面时一定要把灯关掉
    DisposableEffect(cameraId) {
        onDispose { torch(false) }
    }

    fun toggle() {
        if (cameraId == null) return
        haptics.confirm()
        if (active) {
            active = false
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) active = true else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val circleColor by animateColorAsState(
        targetValue = when {
            !active -> colors.secondaryContainer
            mode == TorchMode.STEADY -> TorchWarm
            lit -> TorchSosRed
            else -> TorchSosDim
        },
        animationSpec = tween(durationMillis = 90),
        label = "torchCircle",
    )

    Column(
        modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TorchMode.entries.forEach { m ->
                SelectChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = m.label,
                    enabled = cameraId != null,
                )
            }
        }
        Spacer(Modifier.height(40.dp))

        // 外圈光晕 + 内圈大按钮
        Box(
            modifier = Modifier
                .size(244.dp)
                .background(circleColor.copy(alpha = if (active) 0.22f else 0f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(196.dp)
                    .clip(CircleShape)
                    .background(circleColor)
                    .clickable(enabled = cameraId != null) { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (mode == TorchMode.STEADY) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = if (active) "关闭手电筒" else "打开手电筒",
                        tint = if (active) Color(0xFF3D2B00) else colors.onSurfaceVariantSummary,
                        modifier = Modifier.size(76.dp),
                    )
                } else {
                    Text(
                        text = "SOS",
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) Color.White else colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = when {
                cameraId == null -> "设备没有可用的闪光灯"
                !active && mode == TorchMode.STEADY -> "轻点圆形按钮打开手电筒"
                !active -> "轻点圆形按钮开始 SOS 闪烁"
                mode == TorchMode.STEADY -> "手电筒已常亮，轻点关闭"
                else -> "SOS 求救闪烁中，轻点停止"
            },
            style = MiuixTheme.textStyles.body2,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

private val TorchWarm = Color(0xFFFFC21A)
private val TorchSosRed = Color(0xFFFF2D3F)
private val TorchSosDim = Color(0xFF6B1F2B)
