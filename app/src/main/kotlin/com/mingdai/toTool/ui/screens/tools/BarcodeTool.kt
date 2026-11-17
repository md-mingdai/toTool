package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Patterns
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.barcode.BarcodeRenderer
import com.mingdai.toTool.barcode.BarcodeScanner
import com.mingdai.toTool.barcode.BarcodeType
import com.mingdai.toTool.barcode.ScanResult
import com.mingdai.toTool.barcode.ScanEngine
import com.mingdai.toTool.barcode.ScanSnapshot
import com.mingdai.toTool.barcode.TRACK_FADE_NS
import com.mingdai.toTool.barcode.TrackSnap
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val barcodeTabs = listOf("二维码处理", "条码生成", "扫描识别")

@Composable
fun BarcodeTool(padding: PaddingValues) {
    var tab by remember { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            barcodeTabs.forEachIndexed { index, label ->
                SelectChip(
                    selected = tab == index,
                    onClick = { tab = index },
                    label = label,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                ) {
                    QrProcessPane()
                }

                1 -> BarcodeGeneratorPane()
                else -> BarcodeScannerPane()
            }
        }
    }
}

/* ------------------------------ 条码生成 ------------------------------ */

@Composable
private fun BarcodeGeneratorPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(BarcodeType.QR) }
    var content by remember { mutableStateOf(BarcodeType.QR.sample) }
    var option by remember { mutableStateOf(BarcodeType.QR.defaultOption) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(type, content, option) {
        error = null
        if (content.isBlank()) {
            preview = null
            return@LaunchedEffect
        }
        delay(120)
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = BarcodeRenderer.encode(type, content, option)
                BarcodeRenderer.render(
                    matrix,
                    BarcodeRenderer.quietZone(type),
                    1200,
                    oneDimensional = !type.isTwoD,
                )
            }
        }
        result.onSuccess {
            preview = it
            error = null
        }.onFailure {
            preview = null
            error = it.message ?: "无法生成，请检查内容格式"
        }
    }

    fun save() {
        val bitmap = preview ?: return
        if (saving) return
        saving = true
        scope.launch {
            val name = "toTool_barcode_${System.currentTimeMillis() / 1000L}.png"
            val uri = withContext(Dispatchers.IO) {
                ImageOps.savePngToGallery(context, bitmap, name)
            }
            saving = false
            if (uri != null) {
                Toast.makeText(context, "已保存到 图片/toTool/$name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BarcodeTypeSelector(
            selected = type,
            onSelect = { candidate ->
                val oldSample = type.sample
                type = candidate
                option = candidate.defaultOption
                if (content.isBlank() || content == oldSample) content = candidate.sample
            },
        )

        TextField(
            value = content,
            onValueChange = { content = it },
            label = "内容",
            minLines = 2,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )

        if (type.options.isNotEmpty()) {
            Text(
                text = "纠错级别",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                type.options.forEachIndexed { index, label ->
                    SelectChip(
                        selected = option == index,
                        onClick = { option = index },
                        label = label,
                    )
                }
            }
        }

        HintText(type.note)

        val shown = preview
        when {
            error != null -> Text(
                text = error!!,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.error,
            )

            shown != null -> Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = type.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }

            else -> HintText("输入内容后会自动生成预览")
        }

        PrimaryButton(
            onClick = { save() },
            enabled = shown != null && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            } else {
                Text("保存到相册")
            }
        }
    }
}

@Composable
private fun BarcodeTypeSelector(
    selected: BarcodeType,
    onSelect: (BarcodeType) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { show = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "条码类型",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(text = selected.label, style = MiuixTheme.textStyles.body1)
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = "选择类型",
                tint = colors.onSurfaceVariantSummary,
            )
        }
    }

    AppDialog(
        show = show,
        title = "选择条码类型",
        onDismiss = { show = false },
        confirmText = null,
        dismissText = "取消",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BarcodeType.entries.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(type)
                            show = false
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = type.label,
                        style = MiuixTheme.textStyles.body1,
                        color = if (type == selected) colors.primary else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (type == selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ 扫描识别 ------------------------------ */

@Composable
private fun BarcodeScannerPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0 = 摄像头，1 = 相册
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var galleryResult by remember { mutableStateOf<ScanResult?>(null) }
    var selectedResult by remember { mutableStateOf<ScanResult?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanning = true
            status = null
            val bitmap = withContext(Dispatchers.IO) {
                ImageOps.uriToBitmap(context, uri, maxSide = 1600)
            }
            if (bitmap == null) {
                scanning = false
                status = "无法读取所选图片"
                return@launch
            }
            var scan = withContext(Dispatchers.Default) { BarcodeScanner.decode(bitmap) }
            if (scan == null) {
                val cropped = withContext(Dispatchers.Default) { cropCenterSquare(bitmap, 0.8f) }
                scan = withContext(Dispatchers.Default) { BarcodeScanner.decode(cropped) }
            }
            galleryResult = scan
            status = if (scan == null) "没有识别到条码，试试更清晰或裁剪后的图片" else null
            scanning = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(
                selected = mode == 0,
                onClick = { mode = 0 },
                label = "摄像头",
            )
            SelectChip(
                selected = mode == 1,
                onClick = { mode = 1 },
                label = "相册",
            )
        }
        Spacer(Modifier.height(8.dp))

        if (mode == 0) {
            CameraScanner(
                onView = { selectedResult = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            HintText("码会被方框标出并跟着移动；解出内容后点「查看」打开结果，扫码会持续进行。")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从相册选择图片")
                }
                if (scanning) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                status?.let { HintText(it) }
                galleryResult?.let { result ->
                    ResultBox(
                        text = result.text,
                        label = "识别结果 · ${result.formatLabel}",
                        monospace = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            onClick = { selectedResult = result },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看")
                        }
                        SecondaryButton(
                            onClick = {
                                galleryResult = null
                                status = null
                                picker.launch("image/*")
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("重新选择")
                        }
                    }
                }
                HintText("支持 QR、汉信码、Data Matrix、PDF 417、Code 49 与常见条形码。")
            }
        }
    }

    ScanResultDialog(result = selectedResult, onDismiss = { selectedResult = null })
}
/* ------------------------------ 相机预览 ------------------------------ */

@Composable
private fun CameraScanner(
    onView: (ScanResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(
                    text = "实时扫描需要相机权限",
                    style = MiuixTheme.textStyles.body2,
                    textAlign = TextAlign.Center,
                )
                PrimaryButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
                HintText("也可以切到「相册」上传图片识别")
            }
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var hasFound by remember { mutableStateOf(false) }
    var frameTick by remember { mutableStateOf(0L) }
    val sensorDegrees = remember { AtomicInteger(-1) }
    // 一次相机绑定对应一个引擎：重试时换新的，旧的由 onDispose 停掉
    val engine = remember(lifecycleOwner, retryKey) { ScanEngine() }
    val animator = remember { OverlayAnimator() }
    val textMeasurer = rememberTextMeasurer()
    val chipStyle = MiuixTheme.textStyles.footnote1
    val onViewState = rememberUpdatedState(onView)

    DisposableEffect(lifecycleOwner, retryKey) {
        camera = null
        torchOn = false
        cameraReady = false
        cameraError = null
        sensorDegrees.set(-1)
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val analysisExecutor = engine.start()
        val future = runCatching { ProcessCameraProvider.getInstance(context) }.getOrNull()
        if (future == null) {
            cameraError = "相机初始化失败"
        } else {
            future.addListener({
                if (!disposed) {
                    val provider = runCatching { future.get() }.getOrNull()
                    if (provider == null) {
                        cameraError = "相机启动失败"
                    } else {
                        boundProvider = provider
                        // 预览和分析统一成 4:3，保证 FILL_CENTER 下框与码严格对齐
                        val preview = Preview.Builder()
                            .setTargetResolution(android.util.Size(960, 720))
                            .build()
                            .apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }
                        // 直接要 YUV：只取 Y 平面当灰度，不再让 CameraX 先转一份 RGBA 再转 Bitmap
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(android.util.Size(960, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            engine.analyze(image) { frame ->
                                frameRotation(previewView, sensorDegrees.get(), frame)
                            }
                        }
                        try {
                            provider.unbindAll()
                            val bound = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                            camera = bound
                            sensorDegrees.set(bound.cameraInfo.sensorRotationDegrees)
                            cameraReady = true
                        } catch (e: Exception) {
                            cameraReady = false
                            cameraError = "无法打开相机：${e.message ?: "未知错误"}"
                        }
                    }
                }
            }, ContextCompat.getMainExecutor(context))
        }

        onDispose {
            disposed = true
            runCatching { boundProvider?.unbindAll() }
            engine.stop()
        }
    }

    // 每个 vsync 把引擎的最新快照外推到「此刻」再画：状态只在绘制阶段读取，所以只重绘、不重组
    LaunchedEffect(engine) {
        while (true) {
            withFrameNanos { }
            val nowNs = SystemClock.elapsedRealtimeNanos()
            animator.update(
                nowNs,
                engine.snapshot,
                previewSize.width.toFloat(),
                previewSize.height.toFloat(),
            )
            val found = animator.items.isNotEmpty()
            if (found != hasFound) hasFound = found
            frameTick = nowNs
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { previewSize = it }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    animator.hit(tap.x, tap.y, 10.dp.toPx())?.let { onViewState.value(it) }
                }
            },
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        ScanGuideOverlay(Modifier.fillMaxSize(), hasFound = hasFound)
        if (previewSize != IntSize.Zero) {
            val density = LocalDensity.current
            val side = min(previewSize.width, previewSize.height) * 0.66f
            val frameTop = previewSize.height / 2f - with(density) { 20.dp.toPx() } - side / 2f
            val titleY = (frameTop - with(density) { 42.dp.toPx() })
                .coerceAtLeast(with(density) { 8.dp.toPx() })
            Text(
                text = "将二维码或条码放入框内",
                style = MiuixTheme.textStyles.footnote1,
                color = Color.White.copy(alpha = 0.92f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, titleY.roundToInt()) },
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (frameTick >= 0L) drawLensTracks(animator.items, textMeasurer, chipStyle)
        }
        IconButton(
            onClick = {
                torchOn = !torchOn
                runCatching { camera?.cameraControl?.enableTorch(torchOn) }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.28f)),
        ) {
            Icon(
                imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "手电筒",
                tint = Color.White,
            )
        }
        if (!cameraReady) {
            CameraStatusOverlay(
                error = cameraError,
                onRetry = {
                    cameraError = null
                    cameraReady = false
                    retryKey += 1
                },
            )
        }
    }
}

/**
 * 让这一帧转正所需的顺时针角度。不直接用 image.imageInfo.rotationDegrees：它相对的是分析用例创建时的屏幕方向，
 * 而本应用自己处理了屏幕旋转（不重建 Activity），转屏后它会过期，框就对不上码了。这里用传感器方向和「此刻」的屏幕方向算。
 */
private fun frameRotation(previewView: PreviewView, sensorDegrees: Int, image: ImageProxy): Int {
    val fallback = image.imageInfo.rotationDegrees
    if (sensorDegrees < 0) return fallback
    val display = previewView.display ?: return fallback
    val displayDegrees = when (display.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    return (sensorDegrees - displayDegrees + 360) % 360
}


@Composable
private fun ScanGuideOverlay(modifier: Modifier = Modifier, hasFound: Boolean) {
    val guideAlpha by animateFloatAsState(
        targetValue = if (hasFound) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "guide-corners",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Telegram：取景框为短边的 66%，中心上移 20dp，圆角 14dp
        val side = min(w, h) * 0.66f
        val centerY = h / 2f - 20.dp.toPx()
        val left = (w - side) / 2f
        val top = centerY - side / 2f
        val right = left + side
        val bottom = top + side
        val radius = 14.dp.toPx()

        // 25% 黑遮罩，中间挖一个圆角矩形
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, w, h))
            addRoundRect(RoundRect(Rect(left, top, right, bottom), CornerRadius(radius)))
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.28f))

        if (guideAlpha > 0.01f) {
            val arm = 28.dp.toPx()
            val guide = Path().apply {
                moveTo(left, top + arm)
                lineTo(left, top + radius)
                quadraticTo(left, top, left + radius, top)
                lineTo(left + arm, top)

                moveTo(right - arm, top)
                lineTo(right - radius, top)
                quadraticTo(right, top, right, top + radius)
                lineTo(right, top + arm)

                moveTo(right, bottom - arm)
                lineTo(right, bottom - radius)
                quadraticTo(right, bottom, right - radius, bottom)
                lineTo(right - arm, bottom)

                moveTo(left + arm, bottom)
                lineTo(left + radius, bottom)
                quadraticTo(left, bottom, left, bottom - radius)
                lineTo(left, bottom - arm)
            }
            drawPath(
                path = guide,
                color = Color.White.copy(alpha = guideAlpha),
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/* ------------------------------ 跟随框（智能镜头风格） ------------------------------ */

/**
 * 框怎么跟手的几个可调参数。
 *
 * PREVIEW_EXTRA_LATENCY_S：预览画面比分析线程拿到的同一帧「多出来」的延迟（显示管线、缓冲），不同机型大约在 0~0.06 之间。
 * 外推量 = 这一帧的年龄 − 这个值：
 * 移动手机时框跑到码前面去了 → 调大它；框落在码后面 → 调小它。
 * 合成数据里它在 0~0.08 范围内对误差影响很小（平均误差只变 ±1 像素），所以默认值不用太纠结。
 */
private object OverlayTuning {
    const val PREVIEW_EXTRA_LATENCY_S = 0.03f
    const val MAX_LEAD_S = 0.2f
    /** 新快照到来时的位置跳变按这个时间常数衰减掉，框的运动才连续。 */
    const val SMOOTH_TAU_S = 0.045f
    /** 速度小于 LOW 不外推（手抖的速度估计是噪声），大于 HIGH 全额外推；单位 px/s，按 960 宽的帧换算。 */
    const val DEADBAND_LOW = 40f
    const val DEADBAND_HIGH = 160f
    /** 分析线程超过这么久没出新结果，就把框全部撤掉。 */
    const val STALE_NS = 1_500_000_000L
}

private val LensInk = Color(0xFF202124)

/** 一个码在这一帧要画出来的样子，坐标已经是 View 像素。 */
private class OverlayItem(
    val quad: FloatArray,
    val cx: Float,
    val cy: Float,
    /** 码的边长（面积的平方根） */
    val side: Float,
    /** 水平 / 竖直方向上，框里能放下多大的东西（按码的朝向换算） */
    val availW: Float,
    val availH: Float,
    val boxAlpha: Float,
    val boxScale: Float,
    val chipAlpha: Float,
    val chipScale: Float,
    val result: ScanResult?,
)

/**
 * 把引擎发布的快照（分析帧时刻的位置 + 速度）变成每个 vsync 的画面：
 * 1. 外推：位置 + 速度 × 补偿时间，抵消「分析 → 显示」的延迟；
 * 2. 误差平滑：新快照到来时新旧预测会有一点差，把差值记成偏移再指数衰减掉，框就不会一抽一抽地跳。
 * 只在主线程用。
 */
private class OverlayAnimator {

    private class Memo(var snap: TrackSnap, var frameNs: Long) {
        val offset = FloatArray(8)
        var lastNs = 0L
    }

    private val memos = HashMap<Long, Memo>()

    var items: List<OverlayItem> = emptyList()
        private set

    fun update(nowNs: Long, snapshot: ScanSnapshot?, viewW: Float, viewH: Float) {
        if (snapshot == null || viewW <= 0f || viewH <= 0f ||
            snapshot.frameWidth <= 0 || snapshot.frameHeight <= 0 ||
            nowNs - snapshot.frameNs > OverlayTuning.STALE_NS
        ) {
            memos.clear()
            items = emptyList()
            return
        }
        val fw = snapshot.frameWidth.toFloat()
        val fh = snapshot.frameHeight.toFloat()
        // PreviewView 使用 FILL_CENTER：整帧按较大的比例铺满，边缘会被裁掉
        val viewScale = max(viewW / fw, viewH / fh)
        val ox = (viewW - fw * viewScale) / 2f
        val oy = (viewH - fh * viewScale) / 2f
        val widthScale = fw / 960f

        val out = ArrayList<OverlayItem>(snapshot.tracks.size)
        val seen = HashSet<Long>()
        for (track in snapshot.tracks) {
            seen.add(track.id)
            var memo = memos[track.id]
            if (memo == null) {
                memo = Memo(track, snapshot.frameNs)
                memos[track.id] = memo
            }
            if (memo.frameNs != snapshot.frameNs) {
                // 新快照到了：新旧两个预测在此刻的差，变成一个会衰减的偏移
                val before = predict(memo.snap, memo.frameNs, nowNs, widthScale, fw)
                val after = predict(track, snapshot.frameNs, nowNs, widthScale, fw)
                for (i in 0 until 8) memo.offset[i] += before[i] - after[i]
                memo.snap = track
                memo.frameNs = snapshot.frameNs
            }
            if (memo.lastNs != 0L) {
                val dt = ((nowNs - memo.lastNs) / 1e9f).coerceIn(0f, 0.1f)
                val keep = exp(-dt / OverlayTuning.SMOOTH_TAU_S)
                for (i in 0 until 8) memo.offset[i] *= keep
            }
            memo.lastNs = nowNs

            val q = predict(track, snapshot.frameNs, nowNs, widthScale, fw)
            var sx = 0f
            var sy = 0f
            for (i in 0 until 4) {
                q[i * 2] = ox + (q[i * 2] + memo.offset[i * 2]) * viewScale
                q[i * 2 + 1] = oy + (q[i * 2 + 1] + memo.offset[i * 2 + 1]) * viewScale
                sx += q[i * 2]
                sy += q[i * 2 + 1]
            }

            // 框的宽高（对边取平均）和长边方向；按朝向估计横向 / 纵向各能放多大
            val w = (hypot(q[2] - q[0], q[3] - q[1]) + hypot(q[6] - q[4], q[7] - q[5])) / 2f
            val h = (hypot(q[4] - q[2], q[5] - q[3]) + hypot(q[0] - q[6], q[1] - q[7])) / 2f
            val longSide = max(w, h)
            val shortSide = min(w, h)
            val lx: Float
            val ly: Float
            if (w >= h) {
                lx = (q[2] - q[0]) + (q[4] - q[6])
                ly = (q[3] - q[1]) + (q[5] - q[7])
            } else {
                lx = (q[4] - q[2]) + (q[6] - q[0])
                ly = (q[5] - q[3]) + (q[7] - q[1])
            }
            val ll = hypot(lx, ly).coerceAtLeast(1e-3f)
            val cos2 = (lx / ll) * (lx / ll)

            val shown = if (track.shownNs != 0L) track.shownNs else snapshot.frameNs
            val fade = if (track.deathNs == 0L) {
                1f
            } else {
                ((track.deathNs - nowNs) / TRACK_FADE_NS.toFloat()).coerceIn(0f, 1f)
            }
            val boxT = easeOut((((nowNs - shown) / 1e6f) / 160f).coerceIn(0f, 1f))
            // 还没解出内容的框缓慢「呼吸」：告诉用户它在识别，不是卡住了
            val pulse = if (track.result == null) {
                // 先对周期取模再转 Float：elapsedRealtimeNanos 很大，直接除会让 Float 精度只剩零点几秒，呼吸会一卡一卡
                0.82f + 0.18f * sin((nowNs % 1_200_000_000L) / 1.2e9f * 6.2831855f)
            } else {
                1f
            }
            val chipT = if (track.result != null && track.confirmedNs != 0L) {
                easeOut((((nowNs - track.confirmedNs) / 1e6f - 40f) / 200f).coerceIn(0f, 1f))
            } else {
                0f
            }
            out.add(
                OverlayItem(
                    quad = q,
                    cx = sx / 4f,
                    cy = sy / 4f,
                    side = sqrt(w * h),
                    availW = shortSide + (longSide - shortSide) * cos2,
                    availH = shortSide + (longSide - shortSide) * (1f - cos2),
                    boxAlpha = boxT * fade * pulse,
                    boxScale = 0.94f + 0.06f * boxT,
                    chipAlpha = chipT * fade,
                    chipScale = 0.6f + 0.4f * chipT,
                    result = track.result,
                ),
            )
        }
        memos.keys.retainAll(seen)
        items = out
    }

    /** 点中了哪个码的方框（只有已经解出内容的才响应），没点中返回 null。 */
    fun hit(x: Float, y: Float, slop: Float): ScanResult? {
        for (i in items.indices.reversed()) {
            val item = items[i]
            val result = item.result ?: continue
            if (item.chipAlpha < 0.4f) continue
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (k in 0 until 4) {
                val px = item.quad[k * 2]
                val py = item.quad[k * 2 + 1]
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
            }
            if (x >= l - slop && x <= r + slop && y >= t - slop && y <= b + slop) return result
        }
        return null
    }

    /** 快照里的四个角 + 速度 × 补偿时间（帧坐标）。 */
    private fun predict(t: TrackSnap, frameNs: Long, nowNs: Long, widthScale: Float, frameWidth: Float): FloatArray {
        val age = (nowNs - frameNs) / 1e9f
        val lead = (age - OverlayTuning.PREVIEW_EXTRA_LATENCY_S).coerceIn(0f, OverlayTuning.MAX_LEAD_S)
        val speed = hypot(t.vx, t.vy)
        val low = OverlayTuning.DEADBAND_LOW * widthScale
        val high = OverlayTuning.DEADBAND_HIGH * widthScale
        val gain = ((speed - low) / (high - low)).coerceIn(0f, 1f)
        var dx = t.vx * lead * gain
        var dy = t.vy * lead * gain
        val limit = 0.2f * frameWidth
        val len = hypot(dx, dy)
        if (len > limit) {
            dx *= limit / len
            dy *= limit / len
        }
        val q = FloatArray(8)
        for (i in 0 until 4) {
            q[i * 2] = t.quad[i * 2] + dx
            q[i * 2 + 1] = t.quad[i * 2 + 1] + dy
        }
        return q
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }
}

/* ---- 绘制：白色圆角描边框 + 居中的白色胶囊按钮（Google 智能镜头的样子） ---- */

private fun DrawScope.drawLensTracks(
    items: List<OverlayItem>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
) {
    for (item in items) {
        drawLensBox(item)
        drawLensChip(item, textMeasurer, textStyle)
    }
}

/** 贴着码的圆角描边框：跟随码的朝向和透视，而不是屏幕对齐的矩形。 */
private fun DrawScope.drawLensBox(item: OverlayItem) {
    val alpha = item.boxAlpha
    if (alpha <= 0.01f) return
    val dp = 1.dp.toPx()
    // 向外扩一圈，让描边落在码的外面，不压在条上
    val corners = outsetQuad(item.quad, max(5f * dp, item.side * 0.04f))
    val radius = (minEdge(corners) * 0.2f).coerceIn(4f * dp, 12f * dp)
    val path = roundedQuadPath(corners, radius)
    val stroke = 3f * dp
    scale(item.boxScale, item.boxScale, pivot = Offset(item.cx, item.cy)) {
        // 柔和的阴影：垫两层更宽更淡的深色描边，浅色背景上白框也看得清
        drawPath(
            path,
            Color.Black.copy(alpha = 0.08f * alpha),
            style = Stroke(width = stroke + 10f * dp, join = StrokeJoin.Round),
        )
        drawPath(
            path,
            Color.Black.copy(alpha = 0.14f * alpha),
            style = Stroke(width = stroke + 4.5f * dp, join = StrokeJoin.Round),
        )
        drawPath(
            path,
            Color.White.copy(alpha = alpha),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 框正中的白色胶囊：放大镜图标 + 「查看二维码 / 查看条形码」。
 * 码太小放不下就依次退化成「查看」、只有图标；再小就不画。只有解出内容的码才有胶囊。
 */
private fun DrawScope.drawLensChip(
    item: OverlayItem,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
) {
    val result = item.result ?: return
    val alpha = item.chipAlpha
    if (alpha <= 0.01f) return
    val dp = 1.dp.toPx()
    val margin = 2f * dp
    val availW = item.availW - 2f * margin
    val availH = item.availH - 2f * margin
    if (availH < 22f * dp || availW < 22f * dp) return

    val fullHeight = 40f * dp
    val height = min(fullHeight, availH)
    val k = height / fullHeight
    val icon = 18f * dp * k
    val gap = 8f * dp * k
    val padding = 16f * dp * k
    val style = textStyle.copy(
        fontSize = (15.sp.toPx() * k).toSp(),
        fontWeight = FontWeight.Medium,
        color = LensInk,
    )

    var chosen: TextLayoutResult? = null
    var width = 0f
    for (text in arrayOf(chipLabel(result), "查看")) {
        val measured = textMeasurer.measure(text, style)
        val need = padding * 2f + icon + gap + measured.size.width
        if (need <= availW) {
            chosen = measured
            width = need
            break
        }
    }
    // 两种文案都放不下：退化成只有放大镜的圆形按钮
    val labelLayout: TextLayoutResult? = chosen
    if (labelLayout == null) {
        width = min(height, availW)
        if (width < 22f * dp) return
    }

    val left = item.cx - width / 2f
    val top = item.cy - height / 2f
    scale(item.chipScale, item.chipScale, pivot = Offset(item.cx, item.cy)) {
        drawRoundRect(
            Color.Black.copy(alpha = 0.10f * alpha),
            topLeft = Offset(left - 3f * dp, top - 1f * dp),
            size = Size(width + 6f * dp, height + 6f * dp),
            cornerRadius = CornerRadius(height / 2f + 3f * dp),
        )
        drawRoundRect(
            Color.Black.copy(alpha = 0.16f * alpha),
            topLeft = Offset(left - 1.2f * dp, top + 0.2f * dp),
            size = Size(width + 2.4f * dp, height + 2.4f * dp),
            cornerRadius = CornerRadius(height / 2f + 1.2f * dp),
        )
        drawRoundRect(
            Color.White.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = CornerRadius(height / 2f),
        )
        val ink = LensInk.copy(alpha = alpha)
        if (labelLayout == null) {
            drawMagnifier(item.cx, item.cy, height * 0.5f, ink)
        } else {
            drawMagnifier(left + padding + icon / 2f, item.cy, icon, ink)
            drawText(
                labelLayout,
                topLeft = Offset(left + padding + icon + gap, item.cy - labelLayout.size.height / 2f),
                alpha = alpha,
            )
        }
    }
}

private fun DrawScope.drawMagnifier(cx: Float, cy: Float, size: Float, color: Color) {
    val radius = size * 0.30f
    val width = (size * 0.12f).coerceAtLeast(1.6f)
    val ox = cx - size * 0.06f
    val oy = cy - size * 0.06f
    drawCircle(color = color, radius = radius, center = Offset(ox, oy), style = Stroke(width = width))
    val d = radius * 0.7071f
    drawLine(
        color = color,
        start = Offset(ox + d, oy + d),
        end = Offset(ox + d + size * 0.27f, oy + d + size * 0.27f),
        strokeWidth = width * 1.05f,
        cap = StrokeCap.Round,
    )
}

private fun chipLabel(result: ScanResult): String = when (result.formatLabel) {
    "QR 码", "Data Matrix", "Aztec", "汉信码" -> "查看二维码"
    else -> "查看条形码"
}

/** 四个角各沿「远离两条邻边」的方向推出去 [outset]，使每条边向外平移 outset。 */
private fun outsetQuad(q: FloatArray, outset: Float): FloatArray {
    val out = FloatArray(8)
    var cx = 0f
    var cy = 0f
    for (i in 0 until 4) {
        cx += q[i * 2]
        cy += q[i * 2 + 1]
    }
    cx /= 4f
    cy /= 4f
    for (i in 0 until 4) {
        val prev = (i + 3) % 4
        val next = (i + 1) % 4
        val x = q[i * 2]
        val y = q[i * 2 + 1]
        var ax = x - q[prev * 2]
        var ay = y - q[prev * 2 + 1]
        var qx = x - q[next * 2]
        var qy = y - q[next * 2 + 1]
        val al = hypot(ax, ay).coerceAtLeast(1e-3f)
        val bl = hypot(qx, qy).coerceAtLeast(1e-3f)
        ax /= al
        ay /= al
        qx /= bl
        qy /= bl
        var dx = ax + qx
        var dy = ay + qy
        var dl = hypot(dx, dy)
        if (dl < 1e-3f) {
            dx = x - cx
            dy = y - cy
            dl = hypot(dx, dy).coerceAtLeast(1e-3f)
        }
        val push = outset * 1.4142135f
        out[i * 2] = x + dx / dl * push
        out[i * 2 + 1] = y + dy / dl * push
    }
    return out
}

private fun minEdge(q: FloatArray): Float {
    var shortest = Float.MAX_VALUE
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        shortest = min(shortest, hypot(q[j * 2] - q[i * 2], q[j * 2 + 1] - q[i * 2 + 1]))
    }
    return shortest
}

/** 四边形的圆角路径：每个角在两条边上各退 [radius]，用二次曲线连起来。 */
private fun roundedQuadPath(q: FloatArray, radius: Float): Path {
    val path = Path()
    for (i in 0 until 4) {
        val prev = (i + 3) % 4
        val next = (i + 1) % 4
        val cx = q[i * 2]
        val cy = q[i * 2 + 1]
        val px = q[prev * 2] - cx
        val py = q[prev * 2 + 1] - cy
        val nx = q[next * 2] - cx
        val ny = q[next * 2 + 1] - cy
        val pl = hypot(px, py).coerceAtLeast(1e-3f)
        val nl = hypot(nx, ny).coerceAtLeast(1e-3f)
        val r = min(radius, min(pl, nl) * 0.5f)
        val startX = cx + px / pl * r
        val startY = cy + py / pl * r
        val endX = cx + nx / nl * r
        val endY = cy + ny / nl * r
        if (i == 0) path.moveTo(startX, startY) else path.lineTo(startX, startY)
        path.quadraticTo(cx, cy, endX, endY)
    }
    path.close()
    return path
}

@Composable
private fun CameraStatusOverlay(error: String?, onRetry: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().background(colors.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            if (error == null) {
                CircularProgressIndicator()
                Text("正在连接摄像头", style = MiuixTheme.textStyles.body1)
                HintText("首次打开可能需要几秒钟")
            } else {
                Text("摄像头连接失败", style = MiuixTheme.textStyles.body1)
                Text(
                    text = error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                PrimaryButton(onClick = onRetry) { Text("重新连接") }
            }
        }
    }
}

/* ------------------------------ 识别结果弹窗 ------------------------------ */

@Composable
private fun ScanResultDialog(result: ScanResult?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shown = result
    val url = shown?.let { openableUrl(it.text) }
    AppDialog(
        show = shown != null,
        title = shown?.let { "识别结果 · ${it.formatLabel}" } ?: "识别结果",
        onDismiss = onDismiss,
        confirmText = null,
        dismissText = "关闭",
    ) {
        if (shown != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ResultBox(
                    text = shown.text,
                    label = shown.formatLabel,
                    monospace = true,
                )
            }
            if (url != null) {
                PrimaryButton(
                    onClick = { openUrl(context, url) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text("打开链接")
                }
            }
        }
    }
}

private fun openableUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
        return text.takeIf { !uri.host.isNullOrBlank() }
    }
    if (text.contains("://")) return null
    return text.takeIf { Patterns.WEB_URL.matcher(it).matches() }?.let { "https://$it" }
}

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show() }
}

/* ------------------------------ 位图工具 ------------------------------ */

private fun cropCenterSquare(source: Bitmap, fraction: Float): Bitmap {
    val side = (minOf(source.width, source.height) * fraction).toInt().coerceAtLeast(1)
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    return Bitmap.createBitmap(source, left, top, side, side)
}
