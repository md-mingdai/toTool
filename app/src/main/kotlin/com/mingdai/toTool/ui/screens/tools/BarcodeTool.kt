package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.barcode.BarcodeRenderer
import com.mingdai.toTool.barcode.BarcodeScanner
import com.mingdai.toTool.barcode.BarcodeType
import com.mingdai.toTool.barcode.ScanResult
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
                BarcodeRenderer.render(matrix, BarcodeRenderer.quietZone(type), 1200)
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BarcodeType.entries.forEach { candidate ->
                SelectChip(
                    selected = type == candidate,
                    onClick = {
                        val oldSample = type.sample
                        type = candidate
                        option = candidate.defaultOption
                        if (content.isBlank() || content == oldSample) content = candidate.sample
                    },
                    label = candidate.label,
                )
            }
        }

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

/* ------------------------------ 扫描识别 ------------------------------ */

@Composable
private fun BarcodeScannerPane() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0 = 摄像头，1 = 相册
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

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
            result = scan
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
                paused = result != null,
                onResult = {
                    result = it
                    status = null
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            val shown = result
            if (shown != null) {
                Spacer(Modifier.height(10.dp))
                ResultBox(
                    text = shown.text,
                    label = "识别结果 · ${shown.formatLabel}",
                    monospace = true,
                )
                SecondaryButton(
                    onClick = { result = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("继续扫描")
                }
            }
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
                val shown = result
                if (shown != null) {
                    ResultBox(
                        text = shown.text,
                        label = "识别结果 · ${shown.formatLabel}",
                        monospace = true,
                    )
                    SecondaryButton(
                        onClick = {
                            result = null
                            status = null
                            picker.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重新选择")
                    }
                }
                HintText("支持 QR、汉信码、Data Matrix、PDF 417、Code 49 与常见条形码。相机扫描会自动裁剪中间区域。")
            }
        }
    }
}

/* ------------------------------ 相机预览 ------------------------------ */

@Composable
private fun CameraScanner(
    paused: Boolean,
    onResult: (ScanResult) -> Unit,
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
    var cameraError by remember { mutableStateOf<String?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val pausedState = rememberUpdatedState(paused)
    val callback = rememberUpdatedState(onResult)

    DisposableEffect(lifecycleOwner) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
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
                            if (pausedState.value || !decoding.compareAndSet(false, true)) {
                                image.close()
                                return@setAnalyzer
                            }
                            try {
                                val rotation = image.imageInfo.rotationDegrees
                                val upright = rotateBitmap(image.toBitmap(), rotation)
                                val scaled = scaleDown(upright, 1000)
                                var scan = BarcodeScanner.decode(scaled)
                                if (scan == null) {
                                    scan = BarcodeScanner.decode(cropCenterSquare(scaled, 0.8f))
                                }
                                if (scan != null) {
                                    val found = scan
                                    Handler(Looper.getMainLooper()).post { callback.value(found) }
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
                        } catch (e: Exception) {
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
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.76f)
                .aspectRatio(1f)
                .border(
                    width = 2.dp,
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(16.dp),
                ),
        )
        Text(
            text = "把条码放进框内",
            style = MiuixTheme.textStyles.footnote1,
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
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
                tint = androidx.compose.ui.graphics.Color.White,
            )
        }
        cameraError?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote1,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
    }
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
