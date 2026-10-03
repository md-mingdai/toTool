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
import androidx.compose.foundation.layout.ColumnScope
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

@Composable
private fun ImagePicker(onPicked: (Bitmap?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
    PrimaryButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.AddAPhoto, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("选择图片")
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

    ToolBody(padding) {
        ImagePicker { src = it; result = null }

        if (src == null) {
            Text(
                "选择一张图片后，可调节参数并保存到相册",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            return@ToolBody
        }

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

    ToolBody(padding) {
        ImagePicker { src = it; out = null }
        if (src == null) {
            Text(
                "选择一张图片后，可降低质量或缩小尺寸以减小体积",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            return@ToolBody
        }
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
        transform = { ImageOps.pixelate(it, block) },
        controls = {
            SliderRow("像素块大小", block.toFloat(), { block = it.toInt() }, 2f..64f) {
                "${it.toInt()} px"
            }
        },
    )
}

/* ------------------------------ 水印 ------------------------------ */

@Composable
fun ImageWatermarkTool(padding: PaddingValues) {
    var text by remember { mutableStateOf("toTool") }
    var size by remember { mutableStateOf(28f) }
    var opacity by remember { mutableStateOf(0.45f) }
    var dx by remember { mutableStateOf(180f) }
    var dy by remember { mutableStateOf(90f) }

    TransformTool(
        padding = padding,
        key = Triple(text, size to opacity, dx to dy),
        transform = { ImageOps.watermark(it, text, size, opacity, dx, dy) },
        controls = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = "水印文字",
                modifier = Modifier.fillMaxWidth(),
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
