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

/* ------------------------------ 提取 APK ------------------------------ */

/**
 * 一个可导出的已安装应用。
 *
 * [splits] 非空，说明它是用 AAB 发布的：安装时被拆成“基础包 + 若干配置 / 功能拆分包”。
 * 只导出基础包是装不回去的（缺架构库和资源，装不上或一打开就闪退），
 * 所以这类应用要把所有拆分包一起打成 .apks。
 */
private data class AppEntry(
    val label: String,
    val packageName: String,
    val source: String,
    val splits: List<String>,
    val icon: Bitmap?,
    val versionCode: Long,
) {
    val isBundle: Boolean get() = splits.isNotEmpty()
}

/** 一次导出的结果：成功时 [text] 是保存路径，失败时是原因；[splitCount] > 0 表示打成了 .apks。 */
private data class ApkExportResult(val ok: Boolean, val text: String, val splitCount: Int = 0)

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

/** 搜索：按空格拆成多个关键词，每个词都要命中应用名或包名（不区分大小写）。 */
private fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return apps
    return apps.filter { app ->
        val label = app.label.lowercase(Locale.ROOT)
        val pkg = app.packageName.lowercase(Locale.ROOT)
        tokens.all { label.contains(it) || pkg.contains(it) }
    }
}

/**
 * 把应用的安装包写进 [out]。
 * - 普通应用：原样拷贝那个 .apk；
 * - AAB 应用（[asBundle]）：打成 .apks（其实就是个 zip）。基础包固定叫 base.apk，
 *   拆分包沿用原文件名（split_config.arm64_v8a.apk 之类），本工具的“安装特殊应用”、
 *   SAI 等拆分包安装器都能直接装。APK 本来就压缩过，这里用“只存储”，
 *   几百 MB 的应用也不会卡在压缩上。
 */
private fun writeApkFiles(out: OutputStream, files: List<File>, asBundle: Boolean) {
    if (!asBundle) {
        files.first().inputStream().use { it.copyTo(out) }
        return
    }
    ZipOutputStream(out.buffered()).use { zip ->
        zip.setLevel(Deflater.NO_COMPRESSION)
        val used = HashSet<String>()
        files.forEachIndexed { index, file ->
            var entryName = if (index == 0) "base.apk" else file.name
            if (!used.add(entryName)) { // 理论上不会重名，保险起见
                entryName = "${index}_$entryName"
                used.add(entryName)
            }
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

/**
 * 把已安装应用的安装包导出到下载目录。顶部有固定的搜索框（应用名 / 包名）；
 * 普通应用导出为 .apk，AAB 应用（带拆分包）自动打成 .apks。
 */
@Composable
fun ApkExtractorTool(padding: PaddingValues) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ApkExportResult?>(null) }
    var lastVersionCode by remember { mutableStateOf(0L) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val shown = remember(apps, query) { filterApps(apps, query) }
    // 搜索词一变就回到列表顶部，免得停在之前滚到的位置、看不到第一条结果
    LaunchedEffect(query) { scroll.scrollTo(0) }

    LaunchedEffect(Unit) {
        loading = true
        apps = withContext(Dispatchers.Default) {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        label = pm.getApplicationLabel(ai).toString(),
                        packageName = ai.packageName,
                        source = ai.sourceDir?.takeIf { it.isNotBlank() }
                            ?: getApkPath(ai.packageName) ?: "",
                        // AAB 应用安装后，除了 base.apk 还有 split_config.*.apk 等拆分包
                        splits = ai.splitSourceDirs?.filter { it.isNotBlank() }.orEmpty(),
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

    suspend fun extract(entry: AppEntry) {
        lastVersionCode = entry.versionCode
        busyLabel = entry.label
        val files = (listOf(entry.source) + entry.splits).map { File(it) }
        val missing = files.firstOrNull { !it.exists() }
        if (missing != null) {
            busyLabel = null
            result = ApkExportResult(false, "找不到 APK 文件：${missing.path}")
            return
        }
        val asBundle = entry.isBundle
        val ext = if (asBundle) "apks" else "apk"
        // .apks 不是系统认识的类型：用 octet-stream，MediaStore 才不会在文件名后面再补一个 .zip
        val mime = if (asBundle) "application/octet-stream" else "application/vnd.android.package-archive"
        val safeLabel = entry.label.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "app" }
        val name = "${safeLabel}_v${entry.versionCode}.$ext"
        val target = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/toTool")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("insert failed")
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            writeApkFiles(out, files, asBundle)
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
                    FileOutputStream(f).use { writeApkFiles(it, files, asBundle) }
                    f.absolutePath
                }
            }.getOrNull()
        }
        busyLabel = null
        result = if (target != null) {
            ApkExportResult(true, target, entry.splits.size)
        } else {
            ApkExportResult(false, "导出失败：无法写入文件")
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        // 固定在顶部的搜索框：应用很多，翻到下面也能随时改关键词
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索应用名或包名",
            useLabelAsPlaceholder = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            singleLine = true,
            cornerRadius = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ToolBody(
            padding = PaddingValues(0.dp),
            modifier = Modifier.weight(1f),
            scrollState = scroll,
        ) {
            Text(
                "支持第三方与系统应用，导出到下载目录；AAB 应用（带拆分包）会自动打成 .apks。",
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
            } else if (shown.isEmpty()) {
                Text(
                    "没有找到与「${query.trim()}」匹配的应用",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                shown.forEach { entry ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.label,
                                style = MiuixTheme.textStyles.body2,
                                maxLines = 2,
                            )
                            Text(
                                entry.packageName,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.isBundle) {
                                Text(
                                    "AAB · 基础包 + ${entry.splits.size} 个拆分包 → .apks",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        SecondaryButton(
                            onClick = { scope.launch { extract(entry) } },
                            // 导出一个应用期间其它行先不可点：.apks 可能有几百 MB，同时导出会互相串状态
                            enabled = busyLabel == null,
                        ) {
                            if (busy) {
                                CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                            } else {
                                Text("导出")
                            }
                        }
                    }
                }
                val bundleCount = shown.count { it.isBundle }
                Text(
                    buildString {
                        if (shown.size == apps.size) append("共 ${apps.size} 个应用")
                        else append("匹配 ${shown.size} / ${apps.size} 个应用")
                        if (bundleCount > 0) append("，其中 $bundleCount 个是 AAB 应用（导出为 .apks）")
                        append("。导出前请确认备份用途合法。")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    val r = result
    AppDialog(
        show = r != null,
        title = if (r?.ok == true) "导出完成" else "导出失败",
        summary = when {
            r == null -> null
            !r.ok -> r.text
            r.splitCount > 0 ->
                "路径：${r.text}\n版本代码：$lastVersionCode\n" +
                    "这是 AAB 应用，已把基础包和 ${r.splitCount} 个拆分包打成 .apks，" +
                    "可以用「安装特殊应用」装回去。"
            else -> "路径：${r.text}\n版本代码：$lastVersionCode"
        },
        onDismiss = { result = null },
        confirmText = "好的",
        dismissText = null,
    )
}

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
