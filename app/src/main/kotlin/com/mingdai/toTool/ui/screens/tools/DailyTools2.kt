package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentValues
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.wallpaper.VideoWallpaperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.displayStyle
import com.mingdai.toTool.ui.components.tabular

private suspend fun PointerInputScope.onDrag(
    onPosition: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    detectDragGestures(
        onDrag = { change, _ ->
            change.consume()
            onPosition(change.position)
        },
        onDragEnd = onEnd,
    )
}

/* ------------------------------ 1. 分贝仪 ------------------------------ */

@Composable
fun DecibelTool(padding: PaddingValues) {
    val context = LocalContext.current
    var db by remember { mutableStateOf(0f) }
    var maxDb by remember { mutableStateOf(0f) }
    var minDb by remember { mutableStateOf(Float.MAX_VALUE) }
    var running by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) { running = true; pendingStart = false }
    }

    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            running = true
            micError = false
        } else {
            pendingStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stop() { running = false }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        micError = false
        maxDb = 0f
        minDb = Float.MAX_VALUE
        var rec: AudioRecord? = null
        try {
            // AudioRecord.read() 是阻塞调用：必须放到 IO 线程，原来在主线程里读会卡 UI。
            withContext(Dispatchers.IO) {
                val minBuf = AudioRecord.getMinBufferSize(
                    44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) throw IllegalStateException("bad buffer size")
                @SuppressLint("MissingPermission") // 已在 start() 里检查/申请了 RECORD_AUDIO
                val r = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    44100,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                )
                rec = r
                if (r.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord init failed")
                }
                val buf = ShortArray(minBuf / 2)
                r.startRecording()
                while (isActive) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("read error $n")
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    val peakN = peak / 32767f
                    val dbFs = if (peakN > 0.000001f) 20f * Math.log10(peakN.toDouble()).toFloat() else -90f
                    val reading = dbFs + 90f
                    db = reading.coerceIn(-10f, 130f)
                    if (reading > maxDb) maxDb = reading
                    if (reading < minDb) minDb = reading
                }
            }
        } catch (e: CancellationException) {
            // 点击“停止”会取消本协程，这是正常流程，不能当成麦克风故障（原来会被 runCatching 吞掉并报错）
            throw e
        } catch (e: Exception) {
            micError = true
            running = false
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { running = false }
    }

    val status: String = when {
        micError -> "麦克风初始化失败"
        !running -> "点击开始测音"
        else -> "正在测量"
    }

    val readingStr = if (running) "%.0f dB".format(db) else "--"
    val maxStr = if (running && maxDb > 0f) "%.0f dB".format(maxDb) else "--"
    val minStr = if (running && minDb < Float.MAX_VALUE) "%.0f dB".format(minDb) else "--"

    val level = if (running) ((db + 10f) / 140f).coerceIn(0f, 1f) else 0f

    ToolBody(padding) {
        Text(
            "实测麦克风环境音量，基于峰值电平估算 SPL。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = readingStr,
                    style = displayStyle(45.sp),
                    color = MiuixTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Text(
                        text = status,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12f.dp),
                ) {
                    val trackColor = MiuixTheme.colorScheme.secondaryContainer
                    val fillColor = MiuixTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRoundRect(color = trackColor)
                        val w = size.width * level
                        drawRoundRect(
                            color = fillColor,
                            size = size.copy(width = w),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "最小",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        "最大",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = minStr,
                        style = MiuixTheme.textStyles.body2.tabular(),
                    )
                    Text(
                        text = maxStr,
                        style = MiuixTheme.textStyles.body2.tabular(),
                    )
                }
            }
        }

        PrimaryButton(
            onClick = { if (running) stop() else { maxDb = 0f; minDb = Float.MAX_VALUE; start() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (running) "停止" else "开始测量")
        }
    }
}

/* ------------------------------ 2. 简易画板 ------------------------------ */

private data class Stroke(val color: Color, val width: Float, val points: List<Offset>)

private fun DrawScope.drawStroke(s: Stroke) {
    val pts = s.points
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        drawCircle(color = s.color, radius = s.width / 2f, center = pts[0])
        return
    }
    val path = Path()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) {
        val midX = (pts[i - 1].x + pts[i].x) / 2f
        val midY = (pts[i - 1].y + pts[i].y) / 2f
        path.quadraticBezierTo(pts[i - 1].x, pts[i - 1].y, midX, midY)
    }
    val last = pts.last()
    path.lineTo(last.x, last.y)
    drawPath(
        path,
        color = s.color,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = s.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun renderStrokesToBitmap(
    strokes: List<Stroke>, bgColor: Color, widthPx: Int, heightPx: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val androidCanvas = android.graphics.Canvas(bitmap)
    androidCanvas.drawColor(bgColor.toArgb())
    for (s in strokes) {
        val paint = Paint().apply {
            color = s.color.toArgb()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s.width
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val pts = s.points
        if (pts.isEmpty()) continue
        if (pts.size == 1) {
            androidCanvas.drawCircle(pts[0].x, pts[0].y, s.width / 2f, paint)
        } else {
            val androidPath = android.graphics.Path()
            androidPath.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val midX = (pts[i - 1].x + pts[i].x) / 2f
                val midY = (pts[i - 1].y + pts[i].y) / 2f
                androidPath.quadTo(pts[i - 1].x, pts[i - 1].y, midX, midY)
            }
            androidPath.lineTo(pts.last().x, pts.last().y)
            androidCanvas.drawPath(androidPath, paint)
        }
    }
    return bitmap
}

private suspend fun saveBitmapToGallery(
    context: Context, bitmap: Bitmap, fileName: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/toTool")
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            Uri.parse("content://media/external/images/media")
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching false
        val outputStream = resolver.openOutputStream(uri)
        if (outputStream == null) {
            resolver.delete(uri, null, null)
            return@runCatching false
        }
        outputStream.use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        true
    }.getOrDefault(false)
}

@Composable
fun WhiteboardTool(padding: PaddingValues) {
    val palette = listOf(
        Color.Black, Color.White, Color.Red, Color(0xFF689F38),
        Color.Blue, Color.Yellow, Color(0xFFFF5722), Color.Magenta,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var colorIdx by remember { mutableStateOf(0) }
    var brushSize by remember { mutableStateOf(8f) }
    var strokes by remember { mutableStateOf(listOf<Stroke>()) }
    var current by remember { mutableStateOf<Stroke?>(null) }
    var canvasW by remember { mutableStateOf(0) }
    var canvasH by remember { mutableStateOf(0) }

    ToolBody(padding) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Color(0xFFEEEEEE))
                .onSizeChanged { canvasW = it.width; canvasH = it.height }
                .pointerInput(Unit) {
                    onDrag(
                        onPosition = { p ->
                            val c = current ?: Stroke(palette[colorIdx], brushSize, emptyList()).also { current = it }
                            val updated = c.copy(points = c.points + p)
                            current = updated
                        },
                        onEnd = {
                            current?.let { strokes = strokes + it }
                            current = null
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach { drawStroke(it) }
                current?.let { drawStroke(it) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8f.dp),
        ) {
            palette.forEachIndexed { i, c ->
                val isSelected = i == colorIdx
                val ringColor = MiuixTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .size(if (isSelected) 40f.dp else 32f.dp)
                        .clickable { colorIdx = i },
                ) {
                    val colorSize = 32f.dp.toPx()
                    drawCircle(
                        color = c,
                        radius = colorSize / 2f,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                    if (isSelected) {
                        drawCircle(
                            color = ringColor,
                            radius = size.minDimension / 2f - 2f.dp.toPx(),
                            center = Offset(size.width / 2f, size.height / 2f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 3f.dp.toPx(),
                            ),
                        )
                    }
                }
            }
        }

        Text(
            "笔刷粗细：${brushSize.toInt()} px",
            style = MiuixTheme.textStyles.body2,
        )
        Slider(
            value = brushSize,
            onValueChange = { brushSize = it },
            valueRange = 2f..24f,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8f.dp),
        ) {
            SecondaryButton(
                onClick = { strokes = if (strokes.isEmpty()) emptyList() else strokes.dropLast(1) },
                modifier = Modifier.weight(1f),
            ) { Text("撤销") }
            SecondaryButton(
                onClick = { strokes = emptyList(); current = null },
                modifier = Modifier.weight(1f),
            ) { Text("清屏") }
            SecondaryButton(
                onClick = {
                    if (canvasW == 0 || canvasH == 0) return@SecondaryButton
                    val allStrokes = current?.let { strokes + it } ?: strokes
                    if (allStrokes.isEmpty()) return@SecondaryButton
                    val bmp = renderStrokesToBitmap(allStrokes, Color(0xFFEEEEEE), canvasW, canvasH)
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
                    scope.launch {
                        val ok = saveBitmapToGallery(context, bmp, "白板_$ts.png")
                        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("导出图片") }
        }
    }
}

/* ------------------------------ 简易画板（全屏竖屏） ------------------------------ */

@Composable
fun WhiteboardFullscreen(navController: NavHostController) {
    BackHandler { navController.popBackStack() }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val palette = listOf(
        Color.Black, Color.White, Color.Red, Color(0xFF689F38),
        Color.Blue, Color.Yellow, Color(0xFFFF5722), Color.Magenta,
    )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var colorIdx by remember { mutableStateOf(0) }
    var brushSize by remember { mutableStateOf(8f) }
    var strokes by remember { mutableStateOf(listOf<Stroke>()) }
    var current by remember { mutableStateOf<Stroke?>(null) }
    var canvasW by remember { mutableStateOf(0) }
    var canvasH by remember { mutableStateOf(0) }
    var showToolbar by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        // Full-screen canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasW = it.width; canvasH = it.height }
                .pointerInput(Unit) {
                    onDrag(
                        onPosition = { p ->
                            val c = current ?: Stroke(palette[colorIdx], brushSize, emptyList()).also { current = it }
                            current = c.copy(points = c.points + p)
                        },
                        onEnd = {
                            current?.let { strokes = strokes + it }
                            current = null
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach { drawStroke(it) }
                current?.let { drawStroke(it) }
            }
        }

        // Settings toggle button (top-right, always visible)
        IconButton(
            onClick = { showToolbar = !showToolbar },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "工具栏",
            )
        }

        // Toolbar (bottom sheet-style)
        if (showToolbar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.95f), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Color palette
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        palette.forEachIndexed { i, c ->
                            val isSelected = i == colorIdx
                            val ringColor = MiuixTheme.colorScheme.primary
                            Canvas(
                                modifier = Modifier
                                    .size(if (isSelected) 38.dp else 32.dp)
                                    .clickable { colorIdx = i },
                            ) {
                                val radius = size.minDimension / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(color = c, radius = radius, center = center)
                                if (isSelected) {
                                    drawCircle(
                                        color = ringColor,
                                        radius = radius - 3.dp.toPx(),
                                        center = center,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Brush size slider
                    Text("笔刷：${brushSize.toInt()} px", style = MiuixTheme.textStyles.footnote1)
                    Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 2f..24f)
                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            onClick = { strokes = if (strokes.isEmpty()) emptyList() else strokes.dropLast(1) },
                            modifier = Modifier.weight(1f),
                        ) { Text("撤销") }
                        SecondaryButton(
                            onClick = { strokes = emptyList(); current = null },
                            modifier = Modifier.weight(1f),
                        ) { Text("清屏") }
                        SecondaryButton(
                            onClick = {
                                if (canvasW == 0 || canvasH == 0) return@SecondaryButton
                                val allStrokes = current?.let { strokes + it } ?: strokes
                                if (allStrokes.isEmpty()) return@SecondaryButton
                                val bmp = renderStrokesToBitmap(allStrokes, Color(0xFFEEEEEE), canvasW, canvasH)
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(System.currentTimeMillis())
                                scope.launch {
                                    val ok = saveBitmapToGallery(context, bmp, "白板_$ts.png")
                                    Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("导出") }
                    }
                }
            }
        }
    }
}

/* ------------------------------ 3. 时间屏幕 ------------------------------ */

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateFmt = SimpleDateFormat("yyyy 年 MM 月 dd 日", Locale.getDefault())
private val weekFmt = SimpleDateFormat("EEEE", Locale.SIMPLIFIED_CHINESE) // → 星期一

private val ClockBgColors = listOf(
    Color.Black,
    Color(0xFF1A1A2E),
    Color(0xFF2D2D2D),
    Color(0xFF1B4332),
    Color(0xFF3D0000),
    Color(0xFF1A237E),
    Color(0xFFE0E0E0),
    Color.White,
)

@Composable
fun ClockScreenFullscreen(navController: NavHostController) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var bgIdx by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val bgColor = ClockBgColors[bgIdx]

    BackHandler { navController.popBackStack() }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = view.context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500L)
        }
    }
    val timeStr = timeFmt.format(now)
    val dateStr = dateFmt.format(now)
    val weekStr = weekFmt.format(now)

    val isDarkBg = (0.299f * bgColor.red + 0.587f * bgColor.green + 0.114f * bgColor.blue) < 0.35f
    val primaryText = if (isDarkBg) Color.White else Color.Black
    val secondaryText = if (isDarkBg) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24f.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = timeStr,
                style = displayStyle(57.sp),
                color = primaryText,
            )
            Spacer(Modifier.height(16f.dp))
            Text(text = dateStr, style = MiuixTheme.textStyles.body1, color = primaryText)
            Spacer(Modifier.height(4f.dp))
            Text(text = weekStr, style = MiuixTheme.textStyles.body2, color = secondaryText)
        }
        IconButton(
            onClick = { showSettings = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8f.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "设置",
                tint = if (isDarkBg) Color.White else Color.Black,
            )
        }
        if (showSettings) {
            AppDialog(
                show = showSettings,
                title = "背景颜色",
                onDismiss = { showSettings = false },
                confirmText = "完成",
                dismissText = null,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10f.dp)) {
                    ClockBgColors.forEachIndexed { i, c ->
                        Box(
                            modifier = Modifier
                                .size(36f.dp)
                                .background(c, shape = CircleShape)
                                .border(
                                    width = if (i == bgIdx) 3f.dp else 0.dp,
                                    color = MiuixTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                                .clickable { bgIdx = i },
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ 4. 比赛记分牌 ------------------------------ */

@Composable
fun ScoreboardTool(padding: PaddingValues) {
    var home by remember { mutableStateOf(0) }
    var away by remember { mutableStateOf(0) }

    ToolBody(padding) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ScoreColumn("主队", home, { home = it })
            Text(
                ":",
                style = displayStyle(45.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            ScoreColumn("客队", away, { away = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8f.dp),
        ) {
            SecondaryButton(
                onClick = { home = 0; away = 0 },
                modifier = Modifier.weight(1f),
            ) { Text("清零") }
        }
    }
}

@Composable
private fun ScoreColumn(label: String, score: Int, onSet: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.primary,
        )
        Text(
            score.toString(),
            style = displayStyle(57.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4f.dp)) {
            PrimaryButton(onClick = { onSet(score - 1) }) { Text("-") }
            PrimaryButton(onClick = { onSet(score + 1) }) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4f.dp)) {
            SecondaryButton(onClick = { onSet(score - 10) }) { Text("-10") }
            SecondaryButton(onClick = { onSet(score + 10) }) { Text("+10") }
        }
    }
}

/* ------------------------------ 5. SOS 手电筒 ------------------------------ */

private fun findSOSFlashCameraId(cm: CameraManager): String? = runCatching {
    cm.cameraIdList.firstOrNull { id ->
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
}.getOrNull()

@Composable
fun SosFlashTool(padding: PaddingValues) {
    val context = LocalContext.current
    val cameraManager: CameraManager = remember {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    val cameraId = remember { findSOSFlashCameraId(cameraManager) }
    var active by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && cameraId != null) active = true
    }

    fun toggle(desired: Boolean) {
        val id = cameraId
        if (id == null) return
        if (desired) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                active = true
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            active = false
        }
    }

    LaunchedEffect(active, cameraId) {
        if (!active) {
            cameraId?.let { id -> runCatching { cameraManager.setTorchMode(id, false) } }
            return@LaunchedEffect
        }
        val id = cameraId ?: return@LaunchedEffect
        while (active) {
            // SOS: ... --- ...
            repeat(3) {
                runCatching { cameraManager.setTorchMode(id, true) }
                delay(150)
                runCatching { cameraManager.setTorchMode(id, false) }
                delay(150)
            }
            delay(200)
            repeat(3) {
                runCatching { cameraManager.setTorchMode(id, true) }
                delay(400)
                runCatching { cameraManager.setTorchMode(id, false) }
                delay(150)
            }
            delay(200)
            repeat(3) {
                runCatching { cameraManager.setTorchMode(id, true) }
                delay(150)
                runCatching { cameraManager.setTorchMode(id, false) }
                delay(150)
            }
            delay(1400)
        }
        runCatching { cameraManager.setTorchMode(id, false) }
    }

    DisposableEffect(cameraId) {
        onDispose {
            active = false
            cameraId?.let { id -> runCatching { cameraManager.setTorchMode(id, false) } }
        }
    }

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(180f.dp)
                    .background(
                        color = if (active) Color(0xFFFF1744) else Color(0xFF333333),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (active) "SOS" else "关",
                    color = Color.White,
                    fontSize = if (active) 44f.sp else 32f.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24f.dp))
            PrimaryButton(
                onClick = { toggle(!active) },
                enabled = cameraId != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32f.dp),
            ) {
                Text(if (active) "停止" else "开始 SOS 闪烁")
            }
            Spacer(Modifier.height(12f.dp))
            Text(
                text = when {
                    cameraId == null -> "设备没有可用的闪光灯"
                    active -> "紧急求救模式已开启"
                    else -> "紧急求救模式已关闭"
                },
                color = if (active) Color(0xFFFF8A80) else Color(0xFFAAAAAA),
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

/* ------------------------------ 6. 桌面视频壁纸 ------------------------------ */

@Composable
fun VideoWallpaperTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedName by remember { mutableStateOf<String?>(null) }
    var pickedSize by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    // 流式复制：原来 readBytes() 会把整个视频读进内存（几百 MB 的视频直接 OOM），而且没有关闭输入流
                    val dest = File(context.cacheDir, VideoWallpaperService.VIDEO_FILE)
                    if (dest.exists()) dest.delete()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("openInputStream returned null")
                    val name = context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx) else null
                        } else null
                    } ?: "video"
                    pickedName = name
                    pickedSize = ImageOps.sizeLabel(dest.length())
                    true
                }.getOrDefault(false)
            }
            loading = false
            if (!ok) {
                Toast.makeText(context, "读取视频失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Guide user through the system live-wallpaper picker; we cannot
            // set the wallpaper component programmatically (the API is restricted).
            val comp = ComponentName(context, VideoWallpaperService::class.java)
            val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp)
            }
            val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            runCatching { context.startActivity(direct) }
                .onFailure { runCatching { context.startActivity(chooser) } }
        }
    }

    ToolBody(padding) {
        Text(
            "选择视频后系统会弹出壁纸确认页，点击\"设为壁纸\"即可生效；" +
                "若直接设置失败则会打开动态壁纸选择页，从列表中选 \"toTool 视频壁纸\"。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        PrimaryButton(
            onClick = { videoPicker.launch("video/*") },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (loading) "处理中…" else "选择视频并设为壁纸")
        }
        if (pickedName != null) {
            Text(
                "已加载：$pickedName（$pickedSize）",
                style = MiuixTheme.textStyles.body2,
            )
        }
        SecondaryButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent("android.settings.WALLPAPER_SETTINGS"))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开系统壁纸设置") }
        Text(
            "提示：视频存储在应用私有缓存，系统释放缓存后需要重新选择。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.error,
        )
    }
}
