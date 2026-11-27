package com.mingdai.toTool.ui.screens.tools

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.AppDialog
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import com.mingdai.toTool.ui.components.HintText
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/* ------------------------------ 振动器 ------------------------------ */

/**
 * 一个振动模式。[timings] 的格式是 [延迟, 振, 停, 振, 停, …]（毫秒）。
 *
 * 所有模式都会一直循环到用户手动点“停止”，所以每个模式的末尾都留了一段“停”，
 * 让相邻两轮之间有间隔；否则以“振”结尾的模式一循环就会连成一条不停的长振。
 */
private class VibPattern(val name: String, val desc: String, val timings: LongArray)

private val Patterns = listOf(
    VibPattern("短振", "振 0.18 秒，停 0.42 秒", longArrayOf(0, 180, 420)),
    VibPattern("长振", "振 0.9 秒，停 0.4 秒", longArrayOf(0, 900, 400)),
    VibPattern("急促", "连振 3 下，停 0.5 秒", longArrayOf(0, 90, 120, 90, 120, 90, 520)),
    VibPattern("心跳", "咚—咚，停 0.6 秒", longArrayOf(0, 120, 220, 160, 600)),
)

@Composable
private fun PatternRow(pattern: VibPattern, selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (selected) colors.primary.copy(alpha = 0.12f) else colors.surfaceContainer,
            contentColor = colors.onSurface,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pattern.name,
                    style = MiuixTheme.textStyles.headline2,
                    color = if (selected) colors.primary else colors.onSurface,
                )
                Text(
                    pattern.desc,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, if (selected) colors.primary else colors.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(modifier = Modifier.size(12.dp).background(colors.primary, CircleShape))
                }
            }
        }
    }
}

@Composable
fun VibratorTool(padding: PaddingValues) {
    val context = LocalContext.current
    var patternIdx by remember { mutableStateOf(0) }
    var amplitude by remember { mutableStateOf(0.8f) }
    var running by remember { mutableStateOf(false) }

    val vibrator: Vibrator? = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }
    val hasMotor = vibrator?.hasVibrator() ?: false
    val hasAmplitudeControl = vibrator?.hasAmplitudeControl() ?: false

    fun stop() {
        runCatching { vibrator?.cancel() }
        running = false
    }

    fun start() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) return
        val timings = Patterns[patternIdx].timings
        val ampInt = (amplitude * 255f).toInt().coerceIn(1, 255)
        // 偶数位是“停”（强度 0），奇数位是“振”
        val amps = IntArray(timings.size) { idx -> if (idx % 2 == 0) 0 else ampInt }
        // repeat = 0：从头开始无限循环，直到调用 cancel()。所有模式都循环，不再有单次模式。
        v.vibrate(VibrationEffect.createWaveform(timings, amps, 0))
        running = true
    }

    // 振动过程中切换模式或拖动强度：稍等片刻（拖动时不要每一帧都重启）后，用新参数接着振
    LaunchedEffect(patternIdx, amplitude) {
        if (running) {
            delay(150)
            start()
        }
    }
    // 离开页面时一定要停下，否则会一直振到天荒地老
    DisposableEffect(Unit) { onDispose { runCatching { vibrator?.cancel() } } }

    ToolBody(padding) {
        if (!hasMotor) {
            Text(
                "设备没有振动马达",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            return@ToolBody
        }
        SectionLabel("振动模式")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Patterns.forEachIndexed { i, pattern ->
                PatternRow(pattern, selected = i == patternIdx, onClick = { patternIdx = i })
            }
        }
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                "强度：${(amplitude * 100).toInt()}%",
                style = MiuixTheme.textStyles.body2,
            )
            Slider(
                value = amplitude,
                onValueChange = { amplitude = it },
                valueRange = 0.05f..1f,
                enabled = hasAmplitudeControl,
            )
            if (!hasAmplitudeControl) {
                Text(
                    "此设备的马达不支持强度调节",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        PrimaryButton(
            onClick = { if (running) stop() else start() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "停止振动" else "开始振动")
        }
        HintText(if (running) "正在循环振动，点击「停止振动」结束" else "所有模式都会循环振动，直到你手动停止")
    }
}

/* ------------------------------ 提取当前壁纸 ------------------------------ */

/**
 * 能否读取真实壁纸：
 * - Android 12 及以下：READ_EXTERNAL_STORAGE（普通运行时权限）
 * - Android 13+：只有“所有文件访问权限”(MANAGE_EXTERNAL_STORAGE) 能读到真实壁纸，
 *   READ_MEDIA_IMAGES 不算数；没有它时系统返回默认壁纸，或直接抛 SecurityException。
 */
private fun hasWallpaperAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

@Composable
fun WallpaperExtractorTool(padding: PaddingValues) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var failReason by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(hasWallpaperAccess(context)) }

    // Android 12 及以下：弹出系统的存储权限对话框
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = hasWallpaperAccess(context) }

    // Android 13+：跳到“所有文件访问权限”设置页；用户开完开关返回时，这里会重新检查并自动读取
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasWallpaperAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            try {
                context.startActivity(appPage)
            } catch (e: Exception) {
                // 个别 ROM 没有按应用的页面，退回到“所有文件访问权限”总列表
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(granted, tick) {
        if (!granted) {
            bitmap = null
            failReason = null
            return@LaunchedEffect
        }
        busy = true
        failReason = null
        bitmap = withContext(Dispatchers.IO) {
            try {
                val wm = android.app.WallpaperManager.getInstance(context)
                // Static image wallpapers expose a ParcelFileDescriptor here;
                // live wallpapers return null (no static frame to extract).
                val pfd = wm.getWallpaperFile(android.app.WallpaperManager.FLAG_SYSTEM)
                if (pfd == null) {
                    failReason = "当前是动态壁纸或系统未提供静态画面"
                    null
                } else {
                    pfd.use { p ->
                        // 先只读尺寸，按内存预算算出降采样倍数（全景壁纸可能有上千万像素），再真正解码
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFileDescriptor(p.fileDescriptor, null, bounds)
                        val maxPixels = MemoryBudget.maxPixels(context, 5.5) // 解码 + 编码 PNG
                        var sample = 1
                        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFileDescriptor(p.fileDescriptor, null, opts)
                    }
                }
            } catch (e: SecurityException) {
                failReason = "系统拒绝了读取，请确认权限已开启后点「重新读取」"
                null
            } catch (e: Exception) {
                failReason = e.message
                null
            }
        }
        busy = false
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    ToolBody(padding) {
        Text(
            "读取系统当前静态壁纸并导出为 PNG；动态壁纸不包含可提取的静态画面。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        if (!granted) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        "Android 13 及以上需要授予「所有文件访问权限」才能读取真实壁纸（没有它时系统只会给出默认壁纸）。点下面的按钮，在设置页打开开关后返回即可。"
                    else
                        "读取壁纸需要存储权限，点下面的按钮授权。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            PrimaryButton(
                onClick = { requestAccess() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("授予权限")
            }
            return@ToolBody
        }

        if (busy) {
            Box(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (image == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "无法读取当前壁纸" + (failReason?.let { "：$it" } ?: "，请先在系统设置中设置壁纸"),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
            }
            Text(
                "${bitmap?.width} × ${bitmap?.height} px",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        PrimaryButton(
            onClick = {
                val b = bitmap
                if (b != null) {
                    val name = "wallpaper_${System.currentTimeMillis() / 1000L}.png"
                    val uri = ImageOps.savePngToGallery(context, b, name)
                    if (uri != null) {
                        Toast.makeText(context, "已保存到 相册/toTool/$name", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            enabled = bitmap != null && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存当前壁纸")
        }
        SecondaryButton(
            onClick = { tick++ },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新读取")
        }
    }
}
