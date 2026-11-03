package com.mingdai.toTool.ui.screens.tools

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.mingdai.toTool.ui.components.SelectChip
import kotlin.math.abs

/* ---------------------------- shared image plumbing ---------------------------- */

@Composable
private fun BitmapPreview(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    checker: Boolean = false,
) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (checker) Color.Transparent
                else MiuixTheme.colorScheme.secondaryContainer,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checker) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = 20.dp.toPx()
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var col = row
                    while (x < size.width) {
                        if (col % 2 == 0) {
                            drawRect(Color(0xFFD6D6D6), Offset(x, y), Size(cell, cell))
                        }
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            }
        }
        when {
            image == null -> Text(
                "尚未选择图片",
                modifier = Modifier.align(Alignment.Center),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            else -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 返回一个“打开系统相册选图”的函数；选好并解码成功后回调 [onPicked]。 */
@Composable
private fun rememberImagePicker(onPicked: (Bitmap) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ImageOps.uriToBitmap(context, uri) }
                if (bmp == null) {
                    Toast.makeText(context, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(bmp)
                }
            }
        }
    }
    return { launcher.launch("image/*") }
}

/** 已经选过图片之后，页面顶部那个“重新选择”的小按钮。 */
@Composable
private fun ImagePicker(onPicked: (Bitmap?) -> Unit) {
    val pick = rememberImagePicker { onPicked(it) }
    PrimaryButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AddAPhoto, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("选择图片")
    }
}

/**
 * 还没选图片时的空状态：整块内容（图标、说明、按钮）在屏幕正中央，
 * 而不是挤在页面顶部。图标和按钮都能点。
 */
@Composable
private fun ImageEmptyState(padding: PaddingValues, hint: String, onPicked: (Bitmap) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val pick = rememberImagePicker(onPicked)
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
                .background(colors.primary.copy(alpha = 0.12f))
                .clickable(onClick = pick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AddAPhoto,
                contentDescription = "选择图片",
                tint = colors.primary,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("选择一张图片", style = MiuixTheme.textStyles.title4, color = colors.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(onClick = pick, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("从相册选择")
        }
    }
}

@Composable
private fun CompareToggle(compare: Boolean, onCompare: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(onClick = { onCompare(true) }, enabled = compare) {
            Text("效果")
        }
        SecondaryButton(onClick = { onCompare(false) }, enabled = !compare) {
            Text("原图")
        }
    }
}

@Composable
private fun SaveButton(
    result: Bitmap?,
    busy: Boolean,
    encoded: ByteArray? = null,
    suffix: String = "png",
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    PrimaryButton(
        onClick = {
            if (result != null || encoded != null) {
                val stamp = System.currentTimeMillis() / 1000L
                val name = "toTool_$stamp.$suffix"
                val uri = if (encoded != null) {
                    ImageOps.saveBytesToGallery(context, encoded, "image/$suffix", name)
                } else {
                    ImageOps.savePngToGallery(context, result!!, name)
                }
                if (uri != null) {
                    Toast.makeText(context, "已保存到 相册/toTool/$name", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            }
        },
        enabled = (result != null || encoded != null) && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Save, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("保存到相册")
    }
}

/**
 * Scaffolding shared by the single-step image filters: pick a photo, show the
 * controls, re-run [transform] whenever [key] changes, and offer save.
 * Bitmap work happens off the UI thread.
 */
@Composable
private fun TransformTool(
    padding: PaddingValues,
    key: Any,
    emptyHint: String,
    transform: (Bitmap) -> Bitmap,
    checker: Boolean = false,
    controls: @Composable () -> Unit,
) {
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }

    LaunchedEffect(src, key) {
        val s = src ?: return@LaunchedEffect
        busy = true
        result = withContext(Dispatchers.Default) { runCatching { transform(s) }.getOrNull() }
        busy = false
    }

    if (src == null) {
        ImageEmptyState(padding, emptyHint) { src = it; result = null }
        return
    }

    ToolBody(padding) {
        ImagePicker { src = it; result = null }

        controls()

        Box(modifier = Modifier.fillMaxWidth()) {
            BitmapPreview(
                bitmap = if (compare) src else result,
                checker = checker,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            if (busy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = Color.White),
                        size = 28.dp,
                    )
                }
            }
        }
        CompareToggle(compare) { compare = it }
        SaveButton(result, busy) {}
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { it.toInt().toString() },
) {
    Column {
        Text(
            "${label}：${format(value)}",
            style = MiuixTheme.textStyles.body2,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

/* ------------------------------ 图片压缩 ------------------------------ */

@Composable
fun ImageCompressTool(padding: PaddingValues) {
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var out by remember { mutableStateOf<Pair<Bitmap, ByteArray>?>(null) }
    var quality by remember { mutableStateOf(70f) }
    var scale by remember { mutableStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var origBytes by remember { mutableStateOf(0L) }
    var newBytes by remember { mutableStateOf(0L) }

    LaunchedEffect(src) {
        val s = src ?: return@LaunchedEffect
        origBytes = withContext(Dispatchers.Default) { ImageOps.toPng(s).size.toLong() }
    }

    LaunchedEffect(src, quality, scale) {
        val s = src ?: return@LaunchedEffect
        busy = true
        val pair = withContext(Dispatchers.Default) {
            val resized = if (scale < 1f) ImageOps.resize(s, scale) else s
            resized to ImageOps.toJpeg(resized, quality.toInt())
        }
        out = pair.first to pair.second
        newBytes = pair.second.size.toLong()
        busy = false
    }

    if (src == null) {
        ImageEmptyState(padding, "降低质量或缩小尺寸，减小图片体积") { src = it; out = null }
        return
    }

    ToolBody(padding) {
        ImagePicker { src = it; out = null }
        SliderRow("JPEG 质量", quality, { quality = it }, 5f..95f)
        SliderRow(
            "尺寸缩放",
            scale,
            { scale = it },
            0.1f..1f,
            { String.format(Locale.US, "%.0f%%", it * 100f) },
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            BitmapPreview(
                bitmap = out?.first,
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = Color.White),
                        size = 28.dp,
                    )
                }
            }
        }
        SectionLabel("体积")
        Text(
            "原图  ${ImageOps.sizeLabel(origBytes)}",
            style = MiuixTheme.textStyles.body2,
        )
        Text(
            "压缩后  ${ImageOps.sizeLabel(newBytes)}" +
                (if (origBytes > 0)
                    "  （节省 ${((1 - newBytes.toFloat() / origBytes) * 100f).toInt()}%）"
                else ""),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
        )
        SaveButton(out?.first, busy, out?.second, "jpg") {}
    }
}

/* ------------------------------ 黑白滤镜 ------------------------------ */

@Composable
fun ImageGrayscaleTool(padding: PaddingValues) {
    TransformTool(
        padding = padding,
        key = Unit,
        emptyHint = "一键把照片转成黑白",
        transform = ImageOps::grayscale,
        controls = {},
    )
}

/* ------------------------------ 像素化 ------------------------------ */

@Composable
fun ImagePixelateTool(padding: PaddingValues) {
    var block by remember { mutableStateOf(16) }
    TransformTool(
        padding = padding,
        key = block,
        emptyHint = "把照片变成马赛克，块大小可调",
        transform = { ImageOps.pixelate(it, block) },
        controls = {
            SliderRow("像素块大小", block.toFloat(), { block = it.toInt() }, 2f..64f) {
                "${it.toInt()} px"
            }
        },
    )
}

/* ------------------------------ 水印 ------------------------------ */

/** 斜率的显示格式：最多两位小数，去掉多余的 0（0.50 → 0.5，1.00 → 1）。 */
private fun slopeLabel(k: Float): String {
    val text = String.format(Locale.US, "%.2f", k)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

private fun slopeToAngle(k: Float): Float = Math.toDegrees(Math.atan(k.toDouble())).toFloat()

private fun angleToSlope(deg: Float): Float = Math.tan(Math.toRadians(deg.toDouble())).toFloat()

/** 常用斜率：标签 → 斜率。 */
private val SlopePresets = listOf(
    "水平" to 0f,
    "30°" to 0.58f,
    "45°" to 1f,
    "60°" to 1.73f,
    "-30°" to -0.58f,
    "-45°" to -1f,
)

/**
 * 水印斜率输入：可以直接输入斜率 k，也可以拖滑杆按角度调（k = tan θ），或点常用值。
 * 约定与数学里一致：正值向右上倾斜，负值向右下倾斜，0 是水平。
 */
@Composable
private fun SlopeControl(
    slope: Float,
    slopeText: String,
    onTextChange: (String) -> Unit,
    onSlopeChange: (Float) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val valid = slopeText.trim().toFloatOrNull() != null
    val angle = slopeToAngle(slope)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("文字斜率 k（正值向右上，负值向右下，0 为水平）", style = MiuixTheme.textStyles.body2)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = slopeText,
                onValueChange = onTextChange,
                label = "斜率",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            // 数字键盘上不一定有负号，所以单独给一个“取反”
            SecondaryButton(onClick = { onSlopeChange(-slope) }) { Text("取反") }
        }
        Text(
            text = if (valid) "当前倾角约 ${String.format(Locale.US, "%.1f", angle)}°" else "请输入有效的数字",
            style = MiuixTheme.textStyles.footnote1,
            color = if (valid) colors.onSurfaceVariantSummary else colors.error,
        )
        Slider(
            value = angle.coerceIn(-85f, 85f),
            onValueChange = { onSlopeChange(angleToSlope(it)) },
            valueRange = -85f..85f,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SlopePresets.forEach { (label, k) ->
                SelectChip(
                    selected = abs(slope - k) < 0.005f,
                    onClick = { onSlopeChange(k) },
                    label = label,
                )
            }
        }
    }
}

@Composable
fun ImageWatermarkTool(padding: PaddingValues) {
    var text by remember { mutableStateOf("toTool") }
    var size by remember { mutableStateOf(28f) }
    var opacity by remember { mutableStateOf(0.45f) }
    var dx by remember { mutableStateOf(180f) }
    var dy by remember { mutableStateOf(90f) }
    var slope by remember { mutableStateOf(0.58f) }
    var slopeText by remember { mutableStateOf("0.58") }

    TransformTool(
        padding = padding,
        key = listOf(text, size, opacity, dx, dy, slope),
        emptyHint = "平铺你的文字水印，可指定斜率与透明度",
        transform = { ImageOps.watermark(it, text, size, opacity, dx, dy, slope) },
        controls = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "水印文字",
                modifier = Modifier.fillMaxWidth(),
            )
            SlopeControl(
                slope = slope,
                slopeText = slopeText,
                onTextChange = { input ->
                    slopeText = input
                    // 输入到一半（空、只有负号）时保留上一次有效的斜率
                    input.trim().toFloatOrNull()?.let { if (it.isFinite()) slope = it }
                },
                onSlopeChange = { k ->
                    val rounded = Math.round(k * 100f) / 100f
                    slope = rounded
                    slopeText = slopeLabel(rounded)
                },
            )
            SliderRow("字号", size, { size = it }, 10f..96f)
            SliderRow(
                "不透明度",
                opacity,
                { opacity = it },
                0.05f..1f,
                { String.format(Locale.US, "%.0f%%", it * 100f) },
            )
            SliderRow("水平间距", dx, { dx = it }, 60f..500f)
            SliderRow("垂直间距", dy, { dy = it }, 40f..300f)
        },
    )
}

/* ------------------------------ 毛玻璃 ------------------------------ */

@Composable
fun ImageBlurTool(padding: PaddingValues) {
    var radius by remember { mutableStateOf(12f) }
    var veil by remember { mutableStateOf(0.18f) }

    TransformTool(
        padding = padding,
        key = radius to veil,
        emptyHint = "高斯模糊加白色蒙层，生成毛玻璃效果图",
        transform = { ImageOps.frostedGlass(it, radius.toInt(), veil) },
        controls = {
            SliderRow("模糊强度", radius, { radius = it }, 1f..48f)
            SliderRow(
                "白色蒙层",
                veil,
                { veil = it },
                0f..0.8f,
                { String.format(Locale.US, "%.0f%%", it * 100f) },
            )
        },
    )
}

/* ------------------------------ 圆角矩形 ------------------------------ */

@Composable
fun ImageRoundRectTool(padding: PaddingValues) {
    var radius by remember { mutableStateOf(0.18f) }

    TransformTool(
        padding = padding,
        key = radius,
        emptyHint = "把照片四角裁成圆角，角外透明",
        transform = { b -> ImageOps.roundRect(b, minOf(b.width, b.height).toFloat() * radius) },
        checker = true,
        controls = {
            SliderRow(
                "圆角半径",
                radius,
                { radius = it },
                0f..0.5f,
                { String.format(Locale.US, "%.0f%%", it * 100f) },
            )
        },
    )
}
