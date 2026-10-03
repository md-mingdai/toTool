package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField
import com.mingdai.toTool.ui.components.PrimaryButton
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.ui.components.displayStyle

/* ------------------------------- 19. 二维码生成 ----------------------------- */

private fun encodeQr(text: String): BitMatrix? {
    if (text.isBlank()) return null
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    return runCatching {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256, hints)
    }.getOrNull()
}

@Composable
fun QrTool(padding: PaddingValues) {
    var text by remember { mutableStateOf("https://github.com") }
    val matrix = remember(text) { encodeQr(text.trim()) }
    ToolBody(padding) {
        TextField(
            value = text,
            onValueChange = { text = it },
            label = "文本 / 链接",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        when {
            text.isBlank() -> Text("请输入文本以生成二维码")
            matrix == null -> Text(
                "无法生成二维码，文本可能过长",
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote1,
            )
            else -> Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
            ) {
                val w = matrix.width
                val h = matrix.height
                val cell = size.minDimension / maxOf(w, h)
                val offsetX = (size.width - w * cell) / 2f
                val offsetY = (size.height - h * cell) / 2f
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(offsetX + x * cell, offsetY + y * cell),
                                size = Size(cell + 0.5f, cell + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
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
    val min = totalSec / 60L
    return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs)
}

@Composable
fun StopwatchTool(padding: PaddingValues) {
    var running by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }
    var laps by remember { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(running) {
        if (running) {
            var start = SystemClock.elapsedRealtime() - elapsed
            while (running) {
                elapsed = SystemClock.elapsedRealtime() - start
                delay(10L)
            }
        }
    }

    ToolBody(padding) {
        Text(
            text = formatElapsed(elapsed),
            style = displayStyle(36.sp),
            color = MiuixTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(onClick = { running = !running }) {
                Icon(
                    imageVector = if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.size(4.dp))
                Text(if (running) "暂停" else "开始")
            }
            PrimaryButton(onClick = { running = false; elapsed = 0L; laps = emptyList() }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("重置")
            }
            PrimaryButton(onClick = { if (running) laps = laps + elapsed }) {
                Icon(Icons.Filled.Flag, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("分段")
            }
        }
        if (laps.isNotEmpty()) {
            Text("分段记录", style = MiuixTheme.textStyles.subtitle, color = MiuixTheme.colorScheme.primary)
            laps.reversed().forEachIndexed { i, lap ->
                Text(
                    "#${laps.size - i}  ${formatElapsed(lap)}",
                    style = MiuixTheme.textStyles.body2,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/* ------------------------------- 22. 手电筒 --------------------------------- */

private fun findFlashCameraId(cm: CameraManager): String? = runCatching {
    cm.cameraIdList.firstOrNull { id ->
        (cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) as? Boolean) == true
    }
}.getOrNull()

@Composable
fun FlashlightTool(padding: PaddingValues) {
    val context = LocalContext.current
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val cameraId = remember { findFlashCameraId(cameraManager) }
    var on by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && cameraId != null) {
            runCatching { cameraManager.setTorchMode(cameraId, true) }
            on = true
        }
    }

    // Always turn the torch off when leaving this screen.
    DisposableEffect(cameraId) {
        onDispose {
            cameraId?.let { runCatching { cameraManager.setTorchMode(it, false) } }
        }
    }

    fun toggle(desired: Boolean) {
        val id = cameraId
        if (id == null) return
        if (desired) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                runCatching { cameraManager.setTorchMode(id, true) }
                on = true
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            runCatching { cameraManager.setTorchMode(id, false) }
            on = false
        }
    }

    Box(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(
                checked = on,
                onCheckedChange = { toggle(it) },
                enabled = cameraId != null,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    cameraId == null -> "设备没有可用的闪光灯"
                    on -> "手电筒已开启"
                    else -> "手电筒已关闭"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
