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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.AppDialog

/* ------------------------------ 提取 APK ------------------------------ */

private data class AppEntry(val label: String, val source: String, val icon: Bitmap?, val versionCode: Long)

private fun getApkPath(packageName: String): String? =
    try {
        Runtime.getRuntime().exec(arrayOf("pm", "path", packageName))
            .inputStream.bufferedReader().useLines { lines ->
                lines.firstOrNull()?.substringAfter("package:")?.trim()
                    ?.takeIf { it.endsWith(".apk") }
            }
    } catch (e: Exception) { null }

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

/** Copy an installed app's .apk out of /data/app to the shared Downloads area. */
@Composable
fun ApkExtractorTool(padding: PaddingValues) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var extractResult by remember { mutableStateOf<String?>(null) }
    var lastVersionCode by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        loading = true
        apps = withContext(Dispatchers.Default) {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        label = pm.getApplicationLabel(ai).toString(),
                        source = ai.sourceDir?.takeIf { it.isNotBlank() }
                            ?: getApkPath(ai.packageName) ?: "",
                        icon = runCatching { drawableToBitmap(pm.getApplicationIcon(ai)) }.getOrNull(),
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            pi.longVersionCode
                        else @Suppress("DEPRECATION") pi.versionCode.toLong(),
                    )
                }
                .filter { it.source.isNotBlank() }
                .distinctBy { it.label }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        }
        loading = false
    }

    suspend fun extract(label: String, source: String, versionCode: Long) {
        lastVersionCode = versionCode
        busyLabel = label
        val src = File(source)
        if (!src.exists()) {
            busyLabel = null
            extractResult = "找不到 APK 文件：$source"
            return
        }
        val safeLabel = label.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "app" }
        val name = "${safeLabel}_v$versionCode.apk"
        val target = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/toTool")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("insert failed")
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        } ?: throw IllegalStateException("openOutputStream failed")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                    "${Environment.DIRECTORY_DOWNLOADS}/toTool/$name"
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: throw IllegalStateException("no external dir")
                    dir.mkdirs()
                    val f = File(dir, name)
                    src.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    f.absolutePath
                }
            }.getOrNull()
        }
        busyLabel = null
        extractResult = target
            ?: "导出失败：无法写入文件"
    }

    ToolBody(padding) {
        Text(
            "支持第三方与系统应用，导出到下载目录。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (apps.isEmpty()) {
            Text("未找到可导出的应用", style = MiuixTheme.textStyles.body2)
        } else {
            apps.forEach { entry ->
                val busy = busyLabel == entry.label
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val iconBm = entry.icon
                    if (iconBm != null) {
                        Image(
                            bitmap = iconBm.asImageBitmap(),
                            contentDescription = entry.label,
                            modifier = Modifier.size(32.dp).padding(end = 10.dp),
                        )
                    }
                    Text(
                        entry.label,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 2,
                    )
                    Spacer(Modifier.width(12.dp))
                    SecondaryButton(
                        onClick = { scope.launch { extract(entry.label, entry.source, entry.versionCode) } },
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        } else {
                            Text("导出")
                        }
                    }
                }
            }
            Text(
                "共 ${apps.size} 个应用，导出前请确认备份用途合法。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    val resultMsg = extractResult
    AppDialog(
        show = resultMsg != null,
        title = if (resultMsg?.endsWith(".apk") == true) "导出完成" else "导出失败",
        summary = when {
            resultMsg == null -> null
            resultMsg.endsWith(".apk") -> "路径：$resultMsg\n版本代码：$lastVersionCode"
            else -> resultMsg
        },
        onDismiss = { extractResult = null },
        confirmText = "好的",
        dismissText = null,
    )
}

/* ------------------------------ 振动器 ------------------------------ */

private val Patterns = listOf(
    "短振" to longArrayOf(0, 180),
    "长振" to longArrayOf(0, 900),
    "急促" to longArrayOf(0, 90, 120, 90, 120, 90),
    "心跳" to longArrayOf(0, 120, 220, 160, 500),
    "连续循环" to longArrayOf(0, 700, 300),
)

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

    fun stop() {
        runCatching { vibrator?.cancel() }
        running = false
    }

    fun start() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) return
        val (_, pattern) = Patterns[patternIdx]
        val ampInt = (amplitude * 255f).toInt().coerceIn(1, 255)
        val amps = IntArray(pattern.size) { idx -> if (idx % 2 == 0) 0 else ampInt }
        // 只有最后一个“连续循环”模式需要重复（repeat = 0 表示从头循环），其余只振一遍（-1）
        val loop = patternIdx == Patterns.lastIndex
        val effect = VibrationEffect.createWaveform(pattern, amps, if (loop) 0 else -1)
        v.vibrate(effect)
        running = true
    }

    // 单次模式振完后自动复位按钮；离开页面时停止振动
    LaunchedEffect(running, patternIdx) {
        if (running && patternIdx != Patterns.lastIndex) {
            delay(Patterns[patternIdx].second.sum() + 100L)
            running = false
        }
    }
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
        Patterns.forEachIndexed { i, (label, _) ->
            SecondaryButton(
                onClick = { patternIdx = i },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, modifier = Modifier.weight(1f))
                    if (i == patternIdx) {
                        Text("●", color = MiuixTheme.colorScheme.primary)
                    }
                }
            }
        }
        Column {
            Text(
                "强度：${(amplitude * 100).toInt()}%",
                style = MiuixTheme.textStyles.body2,
            )
            Slider(value = amplitude, onValueChange = { amplitude = it }, valueRange = 0f..1f)
        }
        PrimaryButton(
            onClick = { if (running) stop() else start() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "停止振动" else "开始振动")
        }
    }
}

/* ------------------------------ 提取当前壁纸 ------------------------------ */

@Composable
fun WallpaperExtractorTool(padding: PaddingValues) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var failReason by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
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
                    pfd.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
                }
            } catch (e: SecurityException) {
                failReason = "系统限制：读取壁纸需要“所有文件访问权限”（Android 13+ 起不再允许普通应用读取）"
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
