package com.mingdai.toTool.ui.screens.format

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.media.AudioProbeInfo
import com.mingdai.toTool.media.ConversionBranch
import com.mingdai.toTool.media.ConversionControl
import com.mingdai.toTool.media.ConversionPlan
import com.mingdai.toTool.media.ConvertCanceledException
import com.mingdai.toTool.media.FfmpegCapabilities
import com.mingdai.toTool.media.GifComposeItem
import com.mingdai.toTool.media.ImageProbeInfo
import com.mingdai.toTool.media.MediaKind
import com.mingdai.toTool.media.ResolutionPreset
import com.mingdai.toTool.media.SourceMedia
import com.mingdai.toTool.media.TargetOption
import com.mingdai.toTool.media.VideoFrameInfo
import com.mingdai.toTool.media.VisualMode
import com.mingdai.toTool.media.branchFor
import com.mingdai.toTool.media.defaultTarget
import com.mingdai.toTool.media.detectMediaSignature
import com.mingdai.toTool.media.extractVideoFrame
import com.mingdai.toTool.media.ffmpegBinary
import com.mingdai.toTool.media.ffmpegUnavailableMessage
import com.mingdai.toTool.media.friendlyError
import com.mingdai.toTool.media.isSameFormat
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.media.needsFfmpeg
import com.mingdai.toTool.media.probeAudio
import com.mingdai.toTool.media.probeCapabilities
import com.mingdai.toTool.media.probeImage
import com.mingdai.toTool.media.probeVideo
import com.mingdai.toTool.media.queryFileName
import com.mingdai.toTool.media.queryFileSize
import com.mingdai.toTool.media.runConversion
import com.mingdai.toTool.media.targetGroupsFor
import com.mingdai.toTool.ui.components.BitmapPreviewCard
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.LabeledPickerCard
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.TargetFormatDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max

private enum class CapabilityState { LOADING, READY, MISSING }

private val SOLID_COLORS = listOf(
    0xFF162018.toInt(),
    0xFF1E2A38.toInt(),
    0xFF2B1B2F.toInt(),
    0xFF3A2A1A.toInt(),
    0xFF101010.toInt(),
    0xFFE8E8E8.toInt(),
)

@Composable
fun FormatQuickConvertPane(modifier: Modifier, onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sourceKind by remember { mutableStateOf(MediaKind.UNKNOWN) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var sourceSize by remember { mutableStateOf(-1L) }
    var sourceMime by remember { mutableStateOf<String?>(null) }
    var sourceExtension by remember { mutableStateOf("") }
    var sourceAnimated by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var videoInfo by remember { mutableStateOf<VideoFrameInfo?>(null) }
    var audioInfo by remember { mutableStateOf<AudioProbeInfo?>(null) }
    var imageInfo by remember { mutableStateOf<ImageProbeInfo?>(null) }
    var infoLoading by remember { mutableStateOf(false) }

    var target by remember { mutableStateOf<TargetOption?>(null) }
    var targetManual by remember { mutableStateOf(false) }
    var targetDialog by remember { mutableStateOf(false) }
    var capabilities by remember { mutableStateOf<FfmpegCapabilities?>(null) }
    var capabilityState by remember { mutableStateOf(CapabilityState.LOADING) }
    var capabilityError by remember { mutableStateOf<String?>(null) }

    var durationText by rememberSaveable { mutableStateOf("5") }
    var frameText by rememberSaveable { mutableStateOf("1") }
    var visualMode by rememberSaveable { mutableStateOf(VisualMode.COVER) }
    var resolution by rememberSaveable { mutableStateOf(ResolutionPreset.P720) }
    var solidColor by rememberSaveable { mutableStateOf(SOLID_COLORS.first()) }
    var customCoverUri by remember { mutableStateOf<Uri?>(null) }
    var customCoverName by remember { mutableStateOf<String?>(null) }
    var musicUri by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf<String?>(null) }
    val gifItems = remember { mutableStateListOf<GifComposeItem>() }
    var gifTotalFramesText by rememberSaveable { mutableStateOf("30") }
    var gifAdding by remember { mutableStateOf(false) }

    var framePreview by remember { mutableStateOf<Bitmap?>(null) }
    var frameLoading by remember { mutableStateOf(false) }
    var frameError by remember { mutableStateOf<String?>(null) }

    var isRunning by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(0f) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var noticeText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val control = remember { ConversionControl() }

    fun clearResult() {
        resultText = null
        noticeText = null
        errorText = null
    }

    LaunchedEffect(Unit) {
        val ffmpeg = withContext(Dispatchers.IO) { ffmpegBinary(context) }
        if (ffmpeg == null) {
            capabilityState = CapabilityState.MISSING
            capabilityError = ffmpegUnavailableMessage(context)
        } else {
            capabilities = withContext(Dispatchers.IO) { probeCapabilities(ffmpeg) }
            capabilityState = CapabilityState.READY
        }
    }

    LaunchedEffect(isRunning) { onBusyChange(isRunning) }
    DisposableEffect(Unit) {
        onDispose {
            control.cancel()
            onBusyChange(false)
        }
    }
    KeepScreenOn(isRunning)

    val sourceExt = sourceExtension
    val groups = remember(sourceKind, sourceExt, capabilities, capabilityState, sourceAnimated) {
        targetGroupsFor(sourceKind, sourceExt, capabilities, capabilityState == CapabilityState.READY, sourceAnimated)
    }
    val branch = target?.let { branchFor(sourceKind, it.medium, it.id) }

    LaunchedEffect(sourceKind, sourceExt, sourceUri, capabilities, capabilityState, sourceAnimated) {
        if (sourceUri == null) {
            target = null
            targetManual = false
            return@LaunchedEffect
        }
        val stillValid = target?.let { selected ->
            groups.any { group -> group.options.any { it.id == selected.id && it.medium == selected.medium } } &&
                !(selected.medium == sourceKind && isSameFormat(sourceExt, selected))
        } == true
        if (!stillValid || !targetManual) {
            target = defaultTarget(
                sourceKind, sourceExt, capabilities, capabilityState == CapabilityState.READY, sourceAnimated,
            )
            if (!stillValid) targetManual = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryFileName(context, uri)
            val size = queryFileSize(context, uri)
            val mime = context.contentResolver.getType(uri)
            recognizing = true
            sourceUri = null
            sourceName = null
            sourceSize = -1L
            sourceMime = null
            sourceExtension = ""
            sourceAnimated = false
            sourceKind = MediaKind.UNKNOWN
            videoInfo = null
            audioInfo = null
            imageInfo = null
            framePreview = null
            frameError = null
            target = null
            targetManual = false
            gifItems.clear()
            gifAdding = false
            errorText = null
            clearResult()
            scope.launch {
                val signature = withContext(Dispatchers.IO) {
                    detectMediaSignature(context, uri, name, mime)
                }
                recognizing = false
                if (signature.kind == MediaKind.UNKNOWN) {
                    errorText = "无法识别这个文件的媒体类型（文件头和扩展名都不匹配）。"
                } else {
                    sourceKind = signature.kind
                    sourceUri = uri
                    sourceName = name
                    sourceSize = size
                    sourceMime = mime
                    sourceExtension = signature.extension
                    sourceAnimated = signature.animated
                    videoInfo = null
                    audioInfo = null
                    imageInfo = null
                    framePreview = null
                    frameError = null
                    customCoverUri = null
                    customCoverName = null
                    durationText = "5"
                    frameText = "1"
                    resultText = null
                    noticeText = null
                    errorText = null
                    stageText = ""
                    progress = 0f
                    targetManual = false
                    if (signature.kind == MediaKind.IMAGE) {
                        gifItems.add(
                            GifComposeItem(uri, name, holdFrames = 5, animated = signature.animated, original = true),
                        )
                        gifTotalFramesText = "30"
                    } else {
                        gifItems.clear()
                    }
                    target = defaultTarget(
                        signature.kind, signature.extension, capabilities,
                        capabilityState == CapabilityState.READY, signature.animated,
                    )
                }
            }
        }
    }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            musicUri = uri
            musicName = queryFileName(context, uri)
            clearResult()
        }
    }

    val gifPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        gifAdding = true
        clearResult()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = queryFileName(context, uri)
                    val mime = context.contentResolver.getType(uri)
                    val signature = detectMediaSignature(context, uri, name, mime)
                    if (signature.kind == MediaKind.IMAGE) {
                        GifComposeItem(uri, name, holdFrames = 5, animated = signature.animated)
                    } else {
                        null
                    }
                }
            }
            gifAdding = false
            if (loaded.isEmpty()) {
                errorText = "没有可加入的图片文件。"
            } else {
                gifItems.addAll(loaded)
                clearResult()
            }
        }
    }

    val customImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            customCoverUri = uri
            customCoverName = queryFileName(context, uri)
            errorText = null
        }
    }

    LaunchedEffect(sourceUri, sourceKind) {
        val uri = sourceUri ?: return@LaunchedEffect
        infoLoading = true
        when (sourceKind) {
            MediaKind.VIDEO -> {
                val info = withContext(Dispatchers.IO) { probeVideo(context, uri) }
                if (sourceUri == uri) {
                    videoInfo = info
                    if (info == null) noticeText = "无法读取视频信息，可能是不受支持的编码。"
                }
            }
            MediaKind.AUDIO -> {
                val info = withContext(Dispatchers.IO) { probeAudio(context, uri) }
                if (sourceUri == uri) {
                    audioInfo = info
                    if (info == null) noticeText = "无法读取音频信息。"
                }
            }
            MediaKind.IMAGE -> {
                val info = withContext(Dispatchers.IO) { probeImage(context, uri) }
                if (sourceUri == uri) imageInfo = info
            }
            MediaKind.UNKNOWN -> Unit
        }
        if (sourceUri == uri) infoLoading = false
    }

    LaunchedEffect(sourceUri, frameText, videoInfo, branch) {
        val uri = sourceUri
        val info = videoInfo
        if (branch != ConversionBranch.VIDEO_IMAGE || uri == null || info == null) {
            framePreview = null
            frameError = null
            frameLoading = false
            return@LaunchedEffect
        }
        val requested = frameText
        val index = requested.toIntOrNull()
        val maxFrame = max(1, info.frameCount)
        if (index == null || index !in 1..maxFrame) {
            framePreview = null
            frameError = "帧号需要在 1…$maxFrame 之间"
            frameLoading = false
            return@LaunchedEffect
        }
        frameLoading = true
        frameError = null
        delay(180)
        val bitmap = withContext(Dispatchers.IO) { extractVideoFrame(context, uri, index, 720) }
        if (sourceUri == uri && frameText == requested) {
            framePreview = bitmap
            frameError = if (bitmap == null) "无法读取这一帧" else null
            frameLoading = false
        }
    }

    val durationSeconds = durationText.trim().toDoubleOrNull()
    val durationValid = durationSeconds != null && durationSeconds > 0.0 && durationSeconds <= 3600.0
    val frameIndex = frameText.toIntOrNull()
    val maxFrame = max(1, videoInfo?.frameCount ?: 1)
    val frameValid = videoInfo != null && frameIndex != null && frameIndex in 1..maxFrame
    val waveformAvailable = capabilities?.filters?.contains("showwaves") != false
    val videoHasAudio = branch != ConversionBranch.VIDEO_AUDIO || videoInfo?.hasAudio != false
    val gifTotalFrames = gifTotalFramesText.toIntOrNull()
    val gifFramesValid = gifTotalFrames != null && gifTotalFrames in 1..10000

    val selectedTarget = target
    val selectedBranch = branch
    val canConvert = sourceKind != MediaKind.UNKNOWN &&
        sourceUri != null &&
        sourceName != null &&
        selectedTarget != null &&
        selectedBranch != null &&
        !(selectedTarget.medium == sourceKind && isSameFormat(sourceExt, selectedTarget)) &&
        !isRunning &&
        !recognizing &&
        (selectedBranch.needsFfmpeg().not() || capabilityState == CapabilityState.READY) &&
        (selectedBranch != ConversionBranch.IMAGE_VIDEO || sourceAnimated || durationValid) &&
        (selectedBranch != ConversionBranch.VIDEO_IMAGE || frameValid) &&
        (selectedBranch != ConversionBranch.IMAGE_GIF || (gifItems.isNotEmpty() && gifFramesValid && !gifAdding)) &&
        (selectedBranch != ConversionBranch.AUDIO_VIDEO || visualMode != VisualMode.CUSTOM || customCoverUri != null) &&
        (selectedBranch != ConversionBranch.AUDIO_VIDEO || visualMode != VisualMode.WAVEFORM || waveformAvailable) &&
        videoHasAudio

    fun startConversion() {
        if (!canConvert || isRunning) return
        val uri = sourceUri ?: return
        val name = sourceName ?: return
        val targetOption = target ?: return
        val conversionBranch = branch ?: return
        val plan = ConversionPlan(
            branch = conversionBranch,
            source = SourceMedia(
                uri = uri,
                name = name,
                sizeBytes = sourceSize,
                kind = sourceKind,
                extension = sourceExtension,
                mime = sourceMime,
                animated = sourceAnimated,
            ),
            target = targetOption,
            durationSeconds = durationSeconds.takeIf {
                conversionBranch == ConversionBranch.IMAGE_VIDEO && !sourceAnimated
            },
            frameIndex = frameIndex.takeIf { conversionBranch == ConversionBranch.VIDEO_IMAGE },
            visualMode = visualMode,
            resolution = resolution,
            solidColor = solidColor,
            customImage = customCoverUri,
            music = musicUri.takeIf { conversionBranch == ConversionBranch.IMAGE_VIDEO },
            gifItems = gifItems.toList().takeIf { conversionBranch == ConversionBranch.IMAGE_GIF }.orEmpty(),
            gifTotalFrames = gifTotalFramesText.toIntOrNull() ?: 0,
        )
        control.reset()
        isRunning = true
        stageText = "准备转换…"
        progress = 0f
        resultText = null
        noticeText = null
        errorText = null
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    runConversion(
                        context = context,
                        plan = plan,
                        capabilities = capabilities,
                        videoInfo = videoInfo,
                        audioInfo = audioInfo,
                        control = control,
                        onStage = { stageText = it },
                        onProgress = { progress = it },
                    )
                }
                resultText = outcome.result
                noticeText = outcome.notice
            } catch (e: ConvertCanceledException) {
                noticeText = "已取消转换"
            } catch (e: Exception) {
                errorText = friendlyError(e)
            } finally {
                stageText = ""
                isRunning = false
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LabeledPickerCard(
                title = "源文件",
                value = sourceName ?: if (recognizing) "正在识别文件…" else "选择文件…",
                supporting = if (recognizing) {
                    "读取文件头判断格式"
                } else {
                    sourceSupporting(sourceKind, sourceExt, sourceSize, sourceAnimated, videoInfo, audioInfo, imageInfo)
                },
                icon = when (sourceKind) {
                    MediaKind.VIDEO -> Icons.Filled.VideoLibrary
                    MediaKind.AUDIO -> Icons.Filled.AudioFile
                    else -> Icons.Filled.FolderOpen
                },
                onClick = { filePicker.launch("*/*") },
                enabled = !isRunning && !recognizing,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
            LabeledPickerCard(
                title = "目标格式",
                value = target?.label ?: "选择格式",
                supporting = "图片 / 视频 / 音频",
                icon = Icons.Filled.KeyboardArrowDown,
                onClick = { if (sourceUri != null) targetDialog = true },
                enabled = sourceUri != null && !isRunning,
                modifier = Modifier.weight(1f),
            )
        }

        if (sourceUri == null) {
            Text(
                "文件类型会自动从文件头和后缀识别，不需要手动选择。图片可转视频，视频可提取音频或单帧，音频可配画面生成视频。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Text(
                "目标格式可跨媒体选择；与源格式相同的项会置灰。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        when (branch) {
            ConversionBranch.IMAGE_VIDEO -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("视频设置", style = MiuixTheme.textStyles.body2)
                        if (sourceAnimated) {
                            Text(
                                "动图会按自身帧率和时长转换为视频，不需要填写秒数。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            TextField(
                                value = durationText,
                                onValueChange = { value ->
                                    durationText = value.filter { it.isDigit() || it == '.' }.take(8)
                                    clearResult()
                                },
                                label = "时长（秒，最大 3600）",
                                singleLine = true,
                                enabled = !isRunning,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                if (durationValid) "将生成约 ${durationText} 秒、25 fps 的视频。" else "请输入 0–3600 之间的时长。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (durationValid) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                        }
                        LabeledPickerCard(
                            title = "配乐（可选）",
                            value = musicName ?: "选择音频…",
                            supporting = "会循环到视频结束",
                            icon = Icons.Filled.AudioFile,
                            onClick = { musicPicker.launch("audio/*") },
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (musicUri != null) {
                            SecondaryButton(
                                onClick = {
                                    musicUri = null
                                    musicName = null
                                    clearResult()
                                },
                                enabled = !isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("移除配乐") }
                        }
                    }
                }
            }

            ConversionBranch.VIDEO_IMAGE -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("选择视频帧", style = MiuixTheme.textStyles.body2)
                        TextField(
                            value = frameText,
                            onValueChange = { value ->
                                frameText = value.filter(Char::isDigit).take(9)
                                clearResult()
                            },
                            label = "帧号（从 1 开始）",
                            singleLine = true,
                            enabled = !isRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val info = videoInfo
                        if (infoLoading) {
                            Text("正在读取视频信息…", style = MiuixTheme.textStyles.footnote2)
                        } else if (info != null) {
                            Text(
                                "共 ${info.frameCount} 帧${if (info.frameCountReliable) "" else "（估算）"} · " +
                                    "${info.width}×${info.height} · ${info.durationMs / 1000} 秒",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            Text(
                                "无法读取视频帧信息，请换一个视频或使用 FFmpeg 命令模式。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        BitmapPreviewCard(
                            bitmap = framePreview,
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            placeholder = "输入帧号后显示预览",
                            busy = frameLoading,
                        )
                        frameError?.let {
                            Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error)
                        }
                    }
                }
            }

            ConversionBranch.AUDIO_VIDEO -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("视频画面", style = MiuixTheme.textStyles.body2)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            VisualMode.entries.forEach { mode ->
                                SelectChip(
                                    selected = visualMode == mode,
                                    onClick = {
                                        visualMode = mode
                                        clearResult()
                                    },
                                    label = mode.label,
                                    enabled = !isRunning && (mode != VisualMode.WAVEFORM || waveformAvailable),
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ResolutionPreset.entries.forEach { preset ->
                                SelectChip(
                                    selected = resolution == preset,
                                    onClick = { resolution = preset; clearResult() },
                                    label = preset.label,
                                    enabled = !isRunning,
                                )
                            }
                        }
                        when (visualMode) {
                            VisualMode.COVER -> Text(
                                "优先使用音频内嵌封面；没有封面时自动生成带文件名和装饰条的封面。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            VisualMode.WAVEFORM -> Text(
                                if (waveformAvailable) {
                                    "黑底动态波形；如果当前 FFmpeg 不支持 showwaves，这个选项会不可用。"
                                } else {
                                    "当前 FFmpeg 构建没有 showwaves 滤镜，请选择其它画面。"
                                },
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (waveformAvailable) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.error
                                },
                            )
                            VisualMode.SOLID -> {
                                Text(
                                    "纯色背景",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SOLID_COLORS.forEach { color ->
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(Color(color))
                                                .border(
                                                    width = if (solidColor == color) 2.dp else 1.dp,
                                                    color = if (solidColor == color) {
                                                        MiuixTheme.colorScheme.primary
                                                    } else {
                                                        MiuixTheme.colorScheme.dividerLine
                                                    },
                                                    shape = CircleShape,
                                                )
                                                .clickable(enabled = !isRunning) {
                                                    solidColor = color
                                                    clearResult()
                                                },
                                        )
                                    }
                                }
                            }
                            VisualMode.CUSTOM -> LabeledPickerCard(
                                title = "自定义画面",
                                value = customCoverName ?: "选择图片…",
                                supporting = "会居中裁切为 ${resolution.width}×${resolution.height}",
                                icon = Icons.Filled.Image,
                                onClick = { customImagePicker.launch("image/*") },
                                enabled = !isRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "视频长度跟随音频；画面按 ${resolution.width}×${resolution.height} 生成。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            ConversionBranch.VIDEO_GIF -> {
                Text(
                    "视频会重新编码为 12 fps、最长边 480 的动图 GIF，画面顺序保持不变。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }

            ConversionBranch.IMAGE_GIF -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("GIF 合成", style = MiuixTheme.textStyles.body2)
                        Text(
                            "原图固定在列表顶端。静态图片按各自停留帧数展开；追加的 GIF / APNG 会按自身帧展开，停留帧数不适用。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (gifItems.size == 1 && !gifItems[0].animated) {
                            Text(
                                "当前只有 1 张静态图片，生成的 GIF 会保持静止；要看到动画，请再添加图片或 GIF。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        gifItems.forEachIndexed { index, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = null,
                                    tint = if (item.animated) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        style = MiuixTheme.textStyles.body2,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        buildString {
                                            if (item.original) append("原图 · ")
                                            if (item.animated) append("GIF 自带帧") else append("停留 ${item.holdFrames} 帧")
                                        },
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                if (!item.animated) {
                                    TextField(
                                        value = item.holdFrames.toString(),
                                        onValueChange = { value ->
                                            val frames = value.filter(Char::isDigit).toIntOrNull() ?: 1
                                            gifItems[index] = item.copy(holdFrames = frames.coerceIn(1, 1000))
                                            clearResult()
                                        },
                                        label = "帧",
                                        singleLine = true,
                                        enabled = !isRunning,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(88.dp),
                                    )
                                }
                                if (!item.original) {
                                    Text(
                                        "移除",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clickable(enabled = !isRunning) {
                                                gifItems.removeAt(index)
                                                clearResult()
                                            }
                                            .padding(horizontal = 6.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                        SecondaryButton(
                            onClick = { gifPicker.launch("image/*") },
                            enabled = !isRunning && !gifAdding,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (gifAdding) "正在识别图片…" else "添加图片 / GIF")
                        }
                        TextField(
                            value = gifTotalFramesText,
                            onValueChange = { value ->
                                gifTotalFramesText = value.filter(Char::isDigit).take(5)
                                clearResult()
                            },
                            label = "GIF 总帧数（1–10000）",
                            singleLine = true,
                            enabled = !isRunning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (gifFramesValid) {
                                "总帧数超过单轮长度会循环，不足会截断。输出 10 fps、480×480，空白处自动留黑。"
                            } else {
                                "请输入 1–10000 之间的总帧数。"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (gifFramesValid) {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                        )
                        val firstHold = gifItems.firstOrNull()?.takeIf { !it.animated }?.holdFrames ?: 0
                        if (gifTotalFrames == 1) {
                            Text(
                                "总帧数为 1，生成的 GIF 只有一帧，不会动。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        } else if (gifItems.size > 1 && firstHold > 0 && gifTotalFrames != null && gifTotalFrames < firstHold) {
                            Text(
                                "总帧数小于第一张图片的停留帧数，GIF 可能只显示第一张图片。",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            ConversionBranch.VIDEO_AUDIO -> {
                if (videoInfo?.hasAudio == false) {
                    Text(
                        "这个视频没有音轨，不能提取音频。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "将提取第一条音轨；目标容器不支持当前编码器时会在结果里提示。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            ConversionBranch.IMAGE_IMAGE -> {
                if (sourceAnimated) {
                    Text(
                        "动图转静态图片会取第一帧；如果要保留动画，请选择视频或 GIF 目标。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            else -> Unit
        }

        if (capabilityState == CapabilityState.LOADING) {
            Text(
                "正在检测 FFmpeg 能力…",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else if (capabilityState == CapabilityState.MISSING) {
            MessageCard(
                title = "FFmpeg 不可用",
                message = capabilityError ?: "图片转换和视频抽帧仍可使用。",
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }

        if (isRunning) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Text(
                (stageText.ifBlank { "转换中…" }) +
                    (progress?.let { " ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: ""),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SecondaryButton(
                onClick = { control.cancel() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("取消") }
        }

        noticeText?.let {
            MessageCard(
                title = null,
                message = it,
                container = MiuixTheme.colorScheme.secondaryContainer,
                content = MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        errorText?.let {
            MessageCard(
                title = "转换失败",
                message = it,
                container = MiuixTheme.colorScheme.errorContainer,
                content = MiuixTheme.colorScheme.onErrorContainer,
            )
        }
        resultText?.let {
            ResultBox(it, label = "输出文件")
        }

        PrimaryButton(
            onClick = ::startConversion,
            enabled = canConvert,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRunning) "转换中…" else "开始转换")
        }
    }

    TargetFormatDialog(
        show = targetDialog,
        groups = groups,
        sourceExt = sourceExt,
        selected = target,
        onSelect = {
            target = it
            targetManual = true
            targetDialog = false
            clearResult()
        },
        onDismiss = { targetDialog = false },
    )
}

private fun sourceSupporting(
    kind: MediaKind,
    ext: String,
    size: Long,
    animated: Boolean,
    videoInfo: VideoFrameInfo?,
    audioInfo: AudioProbeInfo?,
    imageInfo: ImageProbeInfo?,
): String {
    val parts = mutableListOf<String>()
    parts += kind.label
    if (ext.isNotBlank()) parts += ext.uppercase()
    if (animated) parts += "动图"
    if (size > 0L) parts += humanSize(size)
    when (kind) {
        MediaKind.VIDEO -> videoInfo?.let {
            if (it.width > 0 && it.height > 0) parts += "${it.width}×${it.height}"
            if (it.durationMs > 0L) parts += "${it.durationMs / 1000} 秒"
        }
        MediaKind.AUDIO -> audioInfo?.let {
            if (it.durationMs > 0L) parts += "${it.durationMs / 1000} 秒"
            if (it.hasEmbeddedCover) parts += "含封面"
        }
        MediaKind.IMAGE -> imageInfo?.let {
            if (it.width > 0 && it.height > 0) parts += "${it.width}×${it.height}"
        }
        MediaKind.UNKNOWN -> Unit
    }
    return parts.joinToString(" · ")
}
