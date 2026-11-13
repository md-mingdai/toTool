package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.barcode.BarcodeRenderer
import com.mingdai.toTool.barcode.BarcodeScanner
import com.mingdai.toTool.barcode.BarcodeType
import com.mingdai.toTool.barcode.ScanResult
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

private data class Detection(val result: ScanResult, val lastSeen: Long)

private fun scanKey(result: ScanResult): String = result.formatLabel + "\u0000" + result.text

@Composable
private fun BarcodeScannerPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0 = 摄像头，1 = 相册
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var galleryResult by remember { mutableStateOf<ScanResult?>(null) }
    var selectedResult by remember { mutableStateOf<ScanResult?>(null) }
    var autoShownKey by remember { mutableStateOf<String?>(null) }
    val detections = remember { mutableStateMapOf<String, Detection>() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            detections.keys.toList().forEach { key ->
                val detection = detections[key]
                if (detection != null && now - detection.lastSeen > 3000) detections.remove(key)
            }
        }
    }
    LaunchedEffect(mode) {
        if (mode == 1) detections.clear()
    }

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
            if (scan != null) {
                selectedResult = scan
                autoShownKey = scanKey(scan)
            }
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
                detections = detections.values.map { it.result },
                onDetection = { result ->
                    val key = scanKey(result)
                    detections[key] = Detection(result, System.currentTimeMillis())
                    if (selectedResult == null && autoShownKey != key) {
                        autoShownKey = key
                        selectedResult = result
                    }
                },
                onView = { selectedResult = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            HintText("识别到的码会用方框标出；点「查看」打开结果，扫码会持续进行。")
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
    detections: List<ScanResult>,
    onDetection: (ScanResult) -> Unit,
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
    val callback = rememberUpdatedState(onDetection)

    DisposableEffect(lifecycleOwner, retryKey) {
        camera = null
        cameraReady = false
        cameraError = null
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val executor = Executors.newSingleThreadExecutor()
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
                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()
                        val decoding = AtomicBoolean(false)
                        analysis.setAnalyzer(executor) { image ->
                            if (!decoding.compareAndSet(false, true)) {
                                image.close()
                                return@setAnalyzer
                            }
                            try {
                                val rotation = image.imageInfo.rotationDegrees
                                val upright = rotateBitmap(image.toBitmap(), rotation)
                                val scaled = scaleDown(upright, 960)
                                val result = BarcodeScanner.decode(scaled)
                                if (result != null) {
                                    Handler(Looper.getMainLooper()).post { callback.value(result) }
                                }
                            } catch (e: Throwable) {
                                // 单帧失败不影响下一帧
                            } finally {
                                image.close()
                                decoding.set(false)
                            }
                        }
                        try {
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
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
            runCatching { executor.shutdown() }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { previewSize = it },
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        ScanGuideOverlay(Modifier.fillMaxSize())
        Text(
            text = "将二维码或条码放入框内",
            style = MiuixTheme.textStyles.footnote1,
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        )
        if (previewSize != IntSize.Zero) {
            DetectionBoxes(detections = detections, previewSize = previewSize, onView = onView)
        }
        IconButton(
            onClick = {
                torchOn = !torchOn
                runCatching { camera?.cameraControl?.enableTorch(torchOn) }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
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

@Composable
private fun ScanGuideOverlay(modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "barcode-scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-line",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val side = min(w, h) * 0.74f
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        val right = left + side
        val bottom = top + side

        val scrim = Color.Black.copy(alpha = 0.46f)
        drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(w, top))
        drawRect(scrim, topLeft = Offset(0f, bottom), size = Size(w, h - bottom))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(scrim, topLeft = Offset(right, top), size = Size(w - right, side))

        val corner = side * 0.12f
        val stroke = 3.dp.toPx()
        val white = Color.White.copy(alpha = 0.95f)
        drawLine(white, Offset(left, top), Offset(left + corner, top), stroke, StrokeCap.Round)
        drawLine(white, Offset(left, top), Offset(left, top + corner), stroke, StrokeCap.Round)
        drawLine(white, Offset(right - corner, top), Offset(right, top), stroke, StrokeCap.Round)
        drawLine(white, Offset(right, top), Offset(right, top + corner), stroke, StrokeCap.Round)
        drawLine(white, Offset(left, bottom - corner), Offset(left, bottom), stroke, StrokeCap.Round)
        drawLine(white, Offset(left, bottom), Offset(left + corner, bottom), stroke, StrokeCap.Round)
        drawLine(white, Offset(right - corner, bottom), Offset(right, bottom), stroke, StrokeCap.Round)
        drawLine(white, Offset(right, bottom - corner), Offset(right, bottom), stroke, StrokeCap.Round)

        val lineY = top + side * progress
        drawLine(
            color = primary.copy(alpha = 0.8f),
            start = Offset(left + 10.dp.toPx(), lineY),
            end = Offset(right - 10.dp.toPx(), lineY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DetectionBoxes(
    detections: List<ScanResult>,
    previewSize: IntSize,
    onView: (ScanResult) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MiuixTheme.colorScheme
    val viewW = previewSize.width.toFloat()
    val viewH = previewSize.height.toFloat()
    if (viewW <= 0f || viewH <= 0f) return

    detections.forEach { result ->
        val bounds = result.bounds ?: return@forEach
        if (result.frameWidth <= 0 || result.frameHeight <= 0) return@forEach
        // PreviewView 使用 FILL_CENTER：把整帧按较大比例铺满取景区域，边缘会被裁掉
        val scale = max(viewW / result.frameWidth.toFloat(), viewH / result.frameHeight.toFloat())
        val displayW = result.frameWidth * scale
        val displayH = result.frameHeight * scale
        val offsetX = (viewW - displayW) / 2f
        val offsetY = (viewH - displayH) / 2f
        val groupLeft = offsetX + bounds.left * displayW
        val groupTop = offsetY + bounds.top * displayH
        val groupRight = offsetX + bounds.right * displayW
        val groupBottom = offsetY + bounds.bottom * displayH

        val minSide = with(density) { 48.dp.toPx() }
        val centerX = (groupLeft + groupRight) / 2f
        val centerY = (groupTop + groupBottom) / 2f
        val boxW = max(groupRight - groupLeft, minSide).coerceAtMost(viewW)
        val boxH = max(groupBottom - groupTop, minSide).coerceAtMost(viewH)
        val left = (centerX - boxW / 2f).coerceIn(0f, (viewW - boxW).coerceAtLeast(0f))
        val top = (centerY - boxH / 2f).coerceIn(0f, (viewH - boxH).coerceAtLeast(0f))

        Box(
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(
                    width = with(density) { boxW.toDp() },
                    height = with(density) { boxH.toDp() },
                )
                .border(2.dp, colors.primary, RoundedCornerShape(10.dp))
                .clickable { onView(result) },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "查看",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onPrimary,
                )
            }
        }
    }
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

private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return source
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
    val longest = max(source.width, source.height)
    if (longest <= maxSide) return source
    val factor = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(
        source,
        (source.width * factor).toInt().coerceAtLeast(1),
        (source.height * factor).toInt().coerceAtLeast(1),
        true,
    )
}

private fun cropCenterSquare(source: Bitmap, fraction: Float): Bitmap {
    val side = (minOf(source.width, source.height) * fraction).toInt().coerceAtLeast(1)
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    return Bitmap.createBitmap(source, left, top, side, side)
}
