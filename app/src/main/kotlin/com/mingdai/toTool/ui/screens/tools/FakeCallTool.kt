package com.mingdai.toTool.ui.screens.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.fakecall.FakeCallAudioStore
import com.mingdai.toTool.fakecall.FakeCallScheduler
import com.mingdai.toTool.fakecall.FakeCallTelecom
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 模拟来电：通过系统 Telecom 注册通话账户，用真实电话界面响铃。 */
@Composable
fun FakeCallTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var callerName by rememberSaveable { mutableStateOf("来电测试") }
    var callerNumber by rememberSaveable { mutableStateOf("13800138000") }
    var hideNumber by rememberSaveable { mutableStateOf(false) }
    var delayText by rememberSaveable { mutableStateOf("10") }
    var timeoutText by rememberSaveable { mutableStateOf("45") }
    var audioName by remember { mutableStateOf(FakeCallAudioStore.audioName(context)) }
    var audioPath by remember { mutableStateOf(FakeCallAudioStore.audioPath(context)) }
    var phonePermission by remember { mutableStateOf(hasPhonePermission(context)) }
    var accountRegistered by remember { mutableStateOf(true) }
    var accountEnabled by remember { mutableStateOf(false) }
    var exactAlarm by remember { mutableStateOf(FakeCallScheduler.canScheduleExact(context)) }
    var scheduledAt by remember { mutableStateOf(FakeCallScheduler.scheduledAt(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showAlarmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick) {
        phonePermission = hasPhonePermission(context)
        exactAlarm = FakeCallScheduler.canScheduleExact(context)
        accountRegistered = FakeCallTelecom.register(context)
        accountEnabled = accountRegistered && FakeCallTelecom.isEnabled(context)
        // 重启、应用更新或强制停止后，按保存的参数重新挂上未触发的闹钟。
        FakeCallScheduler.rescheduleSaved(context)
        val storedSchedule = FakeCallScheduler.scheduledAt(context)
        if (storedSchedule > 0L && storedSchedule < System.currentTimeMillis() - 60_000L) {
            // 过期的定时：连 PendingIntent 一起取消，避免卡片消失后闹钟仍然触发。
            FakeCallScheduler.cancel(context)
            scheduledAt = 0L
        } else {
            scheduledAt = storedSchedule
        }
        audioName = FakeCallAudioStore.audioName(context)
        audioPath = FakeCallAudioStore.audioPath(context)
    }

    LaunchedEffect(scheduledAt) {
        if (scheduledAt <= 0L) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            val now = FakeCallScheduler.scheduledAt(context)
            if (now != scheduledAt) {
                scheduledAt = now
                break
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.READ_PHONE_STATE] == true &&
            result[Manifest.permission.READ_PHONE_NUMBERS] == true
        phonePermission = granted
        if (granted) {
            pendingAction?.invoke()
        } else {
            error = "需要电话状态和号码权限才能模拟来电；如果系统不再弹出授权框，请到系统权限设置中手动开启"
        }
        pendingAction = null
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    FakeCallAudioStore.setAudio(context, uri)
                }
                result.onSuccess { savedName ->
                    audioName = savedName
                    audioPath = FakeCallAudioStore.audioPath(context)
                    notice = "已保存接听音频：$savedName"
                    error = null
                }.onFailure {
                    error = it.message ?: "无法读取所选音频"
                }
            }
        }
    }

    fun withPhonePermission(action: () -> Unit) {
        if (phonePermission) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS,
                ),
            )
        }
    }

    fun validateCaller(): String? {
        val number = callerNumber.trim()
        if (number.isBlank()) return "请填写来电号码"
        if (PhoneNumberUtils.isEmergencyNumber(number)) return "不能使用紧急号码"
        return null
    }

    fun ringTimeout(): Int = timeoutText.toIntOrNull()?.coerceIn(0, 300) ?: 45

    fun triggerNow() {
        withPhonePermission {
            val invalid = validateCaller()
            if (invalid != null) {
                error = invalid
                return@withPhonePermission
            }
            accountRegistered = FakeCallTelecom.register(context)
            if (!accountRegistered) {
                error = "无法注册系统通话账户"
                return@withPhonePermission
            }
            if (!FakeCallTelecom.isEnabled(context)) {
                accountEnabled = false
                showAccountDialog = true
                return@withPhonePermission
            }
            accountEnabled = true
            val ok = FakeCallTelecom.trigger(
                context = context,
                callerName = callerName,
                callerNumber = callerNumber,
                hideNumber = hideNumber,
                audioPath = audioPath,
                ringTimeoutSeconds = ringTimeout(),
            )
            if (ok) {
                notice = "已发起来电，请查看系统电话界面"
                error = null
            } else {
                error = "无法发起来电，请检查通话账户是否已启用"
            }
        }
    }

    fun scheduleCall() {
        withPhonePermission {
            val invalid = validateCaller()
            if (invalid != null) {
                error = invalid
                return@withPhonePermission
            }
            accountRegistered = FakeCallTelecom.register(context)
            if (!accountRegistered) {
                error = "无法注册系统通话账户"
                return@withPhonePermission
            }
            if (!FakeCallTelecom.isEnabled(context)) {
                accountEnabled = false
                showAccountDialog = true
                return@withPhonePermission
            }
            accountEnabled = true
            if (!FakeCallScheduler.canScheduleExact(context)) {
                exactAlarm = false
                showAlarmDialog = true
                return@withPhonePermission
            }
            val seconds = delayText.toIntOrNull()?.coerceIn(1, 24 * 60 * 60) ?: 10
            val at = System.currentTimeMillis() + seconds * 1_000L
            val ok = FakeCallScheduler.schedule(
                context = context,
                triggerAtMillis = at,
                callerName = callerName,
                callerNumber = callerNumber,
                hideNumber = hideNumber,
                audioPath = audioPath,
                ringTimeoutSeconds = ringTimeout(),
            )
            if (ok) {
                scheduledAt = at
                exactAlarm = true
                notice = "已安排在 ${formatCallTime(at)}"
                error = null
            } else {
                error = "无法安排定时来电"
            }
        }
    }

    fun cancelSchedule() {
        FakeCallScheduler.cancel(context)
        scheduledAt = 0L
        notice = "已取消定时来电"
        error = null
    }

    fun openAccountSettings() {
        try {
            context.startActivity(FakeCallTelecom.accountSettingsIntent())
        } catch (e: Exception) {
            error = "无法打开通话账户设置，请到系统电话应用的设置中手动启用「模拟来电」"
        }
    }

    fun openAppSettings() {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            error = "无法打开系统权限设置"
        }
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                error = "无法打开精确闹钟设置"
            }
        }
    }

    ToolBody(padding) {
        if (!phonePermission) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("需要电话权限", style = MiuixTheme.textStyles.body1)
                    Text(
                        "系统要求电话状态与号码权限后才能把模拟来电交给电话应用显示。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.READ_PHONE_NUMBERS,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("授予权限", maxLines = 1) }
                        SecondaryButton(
                            onClick = ::openAppSettings,
                            modifier = Modifier.weight(1f),
                        ) { Text("系统权限设置", maxLines = 1) }
                    }
                }
            }
        }

        SectionLabel("来电信息")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = callerName,
                    onValueChange = { callerName = it },
                    label = "来电人姓名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = callerNumber,
                    onValueChange = { callerNumber = it },
                    label = "来电号码",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { hideNumber = !hideNumber },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(checked = hideNumber, onCheckedChange = { hideNumber = it })
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("隐藏号码", style = MiuixTheme.textStyles.body2)
                        Text(
                            "电话界面会显示为私人号码；号码仍用于系统通话",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        SectionLabel("来电时间")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextField(
                    value = delayText,
                    onValueChange = { delayText = it.filter(Char::isDigit).take(6) },
                    label = "延迟（秒）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(10 to "10 秒", 30 to "30 秒", 60 to "1 分钟", 300 to "5 分钟").forEach { (seconds, label) ->
                        SelectChip(
                            selected = delayText == seconds.toString(),
                            onClick = { delayText = seconds.toString() },
                            label = label,
                        )
                    }
                }
                TextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit).take(3) },
                    label = "响铃超时（秒，0 表示不自动挂断）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton(
                        onClick = ::triggerNow,
                        modifier = Modifier.weight(1f),
                    ) { Text("立即来电", maxLines = 1) }
                    SecondaryButton(
                        onClick = ::scheduleCall,
                        modifier = Modifier.weight(1f),
                    ) { Text("定时来电", maxLines = 1) }
                }
                if (!exactAlarm) {
                    Text(
                        "系统未允许精确闹钟，定时来电可能无法准时。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = WarnColor,
                    )
                    SecondaryButton(
                        onClick = { showAlarmDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("授予精确闹钟权限") }
                }
            }
        }

        if (scheduledAt > 0L) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("定时来电已安排", style = MiuixTheme.textStyles.body1)
                    Text(
                        formatCallTime(scheduledAt),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    SecondaryButton(
                        onClick = ::cancelSchedule,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("取消定时来电") }
                }
            }
        }

        SectionLabel("接听后播放")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (audioName != null) "接听后播放：$audioName" else "接听后播放：默认系统语音",
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    "默认语音：${FakeCallAudioStore.defaultMessage(context)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton(
                        onClick = { audioPicker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("选择音频", maxLines = 1) }
                    if (audioName != null) {
                        SecondaryButton(
                            onClick = {
                                FakeCallAudioStore.clear(context)
                                audioName = null
                                audioPath = null
                                notice = "已改回默认系统语音"
                                error = null
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("默认语音", maxLines = 1) }
                    }
                }
            }
        }

        SectionLabel("通话账户")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        !accountRegistered -> "通话账户注册失败"
                        accountEnabled -> "系统通话账户「${FakeCallTelecom.DEFAULT_ACCOUNT_LABEL}」已启用"
                        else -> "系统通话账户「${FakeCallTelecom.DEFAULT_ACCOUNT_LABEL}」未启用"
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = if (accountEnabled) MiuixTheme.colorScheme.onSurface else WarnColor,
                )
                Text(
                    if (accountEnabled) {
                        "来电会出现在系统电话/拨号界面。"
                    } else {
                        "需要在系统通话账户设置中启用「${FakeCallTelecom.DEFAULT_ACCOUNT_LABEL}」，否则系统不会显示来电。"
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (!accountEnabled) {
                    SecondaryButton(
                        onClick = ::openAccountSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开通话账户设置") }
                }
            }
        }

        notice?.let {
            MessageCard(
                null,
                it,
                MiuixTheme.colorScheme.secondaryContainer,
                MiuixTheme.colorScheme.onSecondaryContainer,
            )
        }
        error?.let {
            MessageCard(
                "模拟来电失败",
                it,
                MiuixTheme.colorScheme.errorContainer,
                MiuixTheme.colorScheme.onErrorContainer,
            )
        }

        HintText("使用前请确认已授予电话权限，并在系统通话账户设置中启用「模拟来电」；模拟来电只在本机展示，不会拨出真实号码。")
        HintText("仅用于个人设备上的测试、演示或脱身场景，请勿用于欺骗、骚扰或冒充他人。")
    }

    AppDialog(
        show = showAccountDialog,
        title = "需要启用通话账户",
        summary = "请在系统通话账户设置中启用「${FakeCallTelecom.DEFAULT_ACCOUNT_LABEL}」，然后返回本页再试一次。",
        confirmText = "打开设置",
        onConfirm = {
            showAccountDialog = false
            openAccountSettings()
        },
        onDismiss = { showAccountDialog = false },
    )
    AppDialog(
        show = showAlarmDialog,
        title = "需要精确闹钟权限",
        summary = "Android 12 及以上需要「闹钟和提醒」权限，才能准时触发定时来电。",
        confirmText = "去授权",
        onConfirm = {
            showAlarmDialog = false
            openExactAlarmSettings()
        },
        onDismiss = { showAlarmDialog = false },
    )
}

private fun hasPhonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
        PackageManager.PERMISSION_GRANTED

private fun formatCallTime(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm:ss", Locale.getDefault()).format(Date(millis))
