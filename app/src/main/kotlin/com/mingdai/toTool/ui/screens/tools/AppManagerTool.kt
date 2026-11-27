package com.mingdai.toTool.ui.screens.tools

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.permission.HighPermissionManager
import com.mingdai.toTool.ui.components.AppCheckbox
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.copyText
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/* ------------------------------ 应用管理 ------------------------------ */

private data class ManagedApp(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val source: String,
    val splits: List<String>,
    val icon: Bitmap?,
    val targetSdk: Int,
    val minSdk: Int,
    val isSystem: Boolean,
)

private enum class AppTab(val label: String) {
    USER("用户应用"),
    SYSTEM("系统应用"),
}

private enum class AppPage {
    DETAIL,
    CLEAR,
    UNINSTALL,
    FREEZE,
    DEX2OAT,
    ACTIVITIES,
}

private enum class FreezeMode(
    val label: String,
    val description: String,
    val freeze: (String, Int) -> String,
    val unfreeze: (String, Int) -> String,
) {
    RESTRICT(
        "限制使用",
        "停用应用组件，相当于在设置里禁用应用；数据保留。",
        { pkg, user -> "pm disable-user --user $user $pkg" },
        { pkg, user -> "pm enable --user $user $pkg" },
    ),
    DISABLE(
        "冻结（suspend）",
        "挂起应用，图标和组件仍在，但暂时不能使用。",
        { pkg, user -> "pm suspend --user $user $pkg" },
        { pkg, user -> "pm unsuspend --user $user $pkg" },
    ),
    HIDE(
        "完全隐藏",
        "从应用列表和启动器中隐藏，可能因系统版本而不受支持。",
        { pkg, user -> "pm hide --user $user $pkg" },
        { pkg, user -> "pm unhide --user $user $pkg" },
    ),
}

private enum class DexMode(
    val label: String,
    val description: String,
    val command: (String, Int) -> String,
) {
    SPEED(
        "speed",
        "只做常规的 AOT 编译，速度较快。",
        { pkg, user -> "cmd package compile --user $user -m speed -f $pkg" },
    ),
    EVERYTHING(
        "everything",
        "尽可能完整地编译，耗时较长、占用空间更大。",
        { pkg, user -> "cmd package compile --user $user -m everything -f $pkg" },
    ),
    RESET(
        "删除 dexopt",
        "清空已生成的 dexopt 产物，下次启动时重新编译。",
        { pkg, user -> "cmd package compile --user $user --reset $pkg" },
    ),
    FORCE(
        "强制重新编译",
        "强制重新编译，即使当前编译产物已经是最新的。",
        { pkg, user -> "cmd package compile --user $user -m speed -r -f $pkg" },
    ),
}

private data class PrivilegedResult(val code: Int, val output: String) {
    fun describe(): String = buildString {
        append("退出码 ").append(code)
        if (output.isNotBlank()) append("\n\n").append(output.trimEnd())
    }
}

@Composable
fun AppManagerTool(padding: PaddingValues) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<ManagedApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(AppTab.USER) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ManagedApp?>(null) }
    var page by remember { mutableStateOf(AppPage.DETAIL) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var onlyCurrentUser by remember { mutableStateOf(true) }
    var keepData by remember { mutableStateOf(false) }
    var uninstallCurrentUser by remember { mutableStateOf(true) }
    var freezeMode by remember { mutableStateOf(FreezeMode.RESTRICT) }
    var dexMode by remember { mutableStateOf(DexMode.SPEED) }
    var activityNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var activitiesLoading by remember { mutableStateOf(false) }
    var highStatus by remember { mutableStateOf(HighPermissionManager.status(context)) }
    val currentUserId = remember { currentAndroidUserId() }

    fun refreshApps() {
        scope.launch {
            loading = true
            apps = try {
                withContext(Dispatchers.Default) { loadManagedApps(context, pm) }
            } catch (e: Exception) {
                emptyList()
            }
            loading = false
        }
    }

    fun openApp(app: ManagedApp) {
        selected = app
        page = AppPage.DETAIL
        result = null
        onlyCurrentUser = true
        keepData = false
        uninstallCurrentUser = true
        freezeMode = FreezeMode.RESTRICT
        dexMode = DexMode.SPEED
        activityNames = emptyList()
    }

    fun dismissDialog() {
        if (busy) return
        if (page == AppPage.DETAIL) {
            selected = null
            result = null
        } else {
            page = AppPage.DETAIL
            result = null
        }
    }

    fun runOperation(command: String, closeOnSuccess: Boolean = false) {
        if (busy) return
        val currentStatus = HighPermissionManager.status(context)
        highStatus = currentStatus
        if (!currentStatus.ready) {
            result = "高级权限未就绪：${currentStatus.detail}"
            return
        }
        busy = true
        result = null
        scope.launch {
            val outcome = runPrivileged(context, command)
            busy = false
            highStatus = HighPermissionManager.status(context)
            if (closeOnSuccess && outcome.code == 0) {
                selected = null
                Toast.makeText(context, "操作已完成", Toast.LENGTH_SHORT).show()
            } else {
                result = outcome.describe()
            }
            if (outcome.code == 0) refreshApps()
        }
    }

    fun exportApp(app: ManagedApp) {
        if (busy) return
        busy = true
        result = null
        scope.launch {
            val exported = exportManagedApk(context, app)
            busy = false
            result = exported
        }
    }

    fun openSettings(app: ManagedApp) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", app.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startExternal(context, intent)) {
            Toast.makeText(context, "没有可以打开应用设置的应用", Toast.LENGTH_SHORT).show()
        }
    }

    fun openStore(app: ManagedApp) {
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startExternal(context, market)) return
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startExternal(context, web)) {
            Toast.makeText(context, "没有可以打开应用商店的应用", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        apps = try {
            withContext(Dispatchers.Default) { loadManagedApps(context, pm) }
        } catch (e: Exception) {
            emptyList()
        }
        loading = false
        highStatus = HighPermissionManager.status(context)
    }

    LaunchedEffect(page, selected?.packageName) {
        val current = selected
        if (page == AppPage.ACTIVITIES && current != null) {
            activitiesLoading = true
            activityNames = withContext(Dispatchers.Default) {
                loadActivityNames(pm, current.packageName)
            }
            activitiesLoading = false
        }
    }

    KeepScreenOn(busy)

    val shown = remember(apps, tab, query) {
        val base = apps.filter { if (tab == AppTab.SYSTEM) it.isSystem else !it.isSystem }
        val tokens = query.trim().lowercase(Locale.ROOT)
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            base
        } else {
            base.filter { app ->
                val label = app.label.lowercase(Locale.ROOT)
                val pkg = app.packageName.lowercase(Locale.ROOT)
                tokens.all { label.contains(it) || pkg.contains(it) }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTab.entries.forEach { item ->
                val count = apps.count { if (item == AppTab.SYSTEM) it.isSystem else !it.isSystem }
                SelectChip(
                    selected = tab == item,
                    onClick = { tab = item },
                    label = "${item.label} $count",
                )
            }
        }

        Text(
            if (highStatus.ready) {
                "高级权限已就绪：${highStatus.detail}"
            } else {
                "查看信息、跳转设置 / 商店、提取安装包不需要高级权限；修改应用状态需要先配置 Shizuku 或 Root。"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = if (highStatus.ready) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (loading) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "没有找到应用" else "没有匹配「${query.trim()}」的应用",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    ManagedAppRow(app = app, onClick = { openApp(app) })
                }
                item {
                    Text(
                        "共 ${shown.size} 个应用。冻结、卸载和清除数据会影响应用使用，操作前请确认。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }

    val app = selected
    if (app != null) {
        val pageTitle = when (page) {
            AppPage.DETAIL -> app.label
            AppPage.CLEAR -> "清除数据"
            AppPage.UNINSTALL -> "卸载应用"
            AppPage.FREEZE -> "冻结 / 解冻"
            AppPage.DEX2OAT -> "Dex2oat"
            AppPage.ACTIVITIES -> "Activity 列表"
        }
        val summary = if (page == AppPage.DETAIL) {
            "${app.packageName} · ${app.versionName ?: "未知版本"}"
        } else {
            app.label
        }
        AppDialog(
            show = true,
            title = pageTitle,
            summary = summary,
            onDismiss = { dismissDialog() },
            confirmText = null,
            dismissText = if (page == AppPage.DETAIL) "关闭" else "返回",
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    AppPage.DETAIL -> {
                        AppHeader(app)
                        AppInfoCard(app, onCopy = { copyText(context, it, "内容") })
                        AppActionRow(
                            icon = Icons.Filled.Settings,
                            title = "应用设置",
                            subtitle = "跳转到系统应用详情页",
                            color = Color(0xFF2196F3),
                        ) { openSettings(app) }
                        AppActionRow(
                            icon = Icons.Filled.ShoppingCart,
                            title = "应用商店",
                            subtitle = "在应用商店中查看这个应用",
                            color = Color(0xFF03A9F4),
                        ) { openStore(app) }
                        AppActionRow(
                            icon = Icons.Filled.Delete,
                            title = "清除数据",
                            subtitle = "清空应用数据，回收存储空间",
                            color = Color(0xFFFF9800),
                        ) {
                            page = AppPage.CLEAR
                            result = null
                        }
                        AppActionRow(
                            icon = Icons.Filled.Delete,
                            title = "卸载",
                            subtitle = "卸载或仅从当前用户卸载，可选择保留数据",
                            color = Color(0xFFF44336),
                        ) {
                            page = AppPage.UNINSTALL
                            result = null
                        }
                        AppActionRow(
                            icon = Icons.Filled.Lock,
                            title = "冻结 / 解冻",
                            subtitle = "限制使用、停用或完全隐藏应用",
                            color = Color(0xFF29B6F6),
                        ) {
                            page = AppPage.FREEZE
                            result = null
                        }
                        AppActionRow(
                            icon = Icons.Filled.Build,
                            title = "Dex2oat",
                            subtitle = "选择模式重新编译应用的 dex",
                            color = Color(0xFF8BC34A),
                        ) {
                            page = AppPage.DEX2OAT
                            result = null
                        }
                        AppActionRow(
                            icon = Icons.Filled.Menu,
                            title = "Activity 列表",
                            subtitle = "查看应用声明的所有 Activity",
                            color = Color(0xFF607D8B),
                        ) {
                            page = AppPage.ACTIVITIES
                            result = null
                        }
                        AppActionRow(
                            icon = Icons.Filled.Android,
                            title = "提取安装包",
                            subtitle = if (app.splits.isEmpty()) "导出 APK 到下载目录" else "合并基础包和拆分包导出 .apks",
                            color = Color(0xFF4CAF50),
                        ) { exportApp(app) }
                    }

                    AppPage.CLEAR -> {
                        Text(
                            "将清空 ${app.label} 的全部数据。这个操作不可撤销。",
                            style = MiuixTheme.textStyles.body2,
                        )
                        OptionRow("仅当前用户", "只清除当前用户的数据，其它用户不受影响", onlyCurrentUser) {
                            onlyCurrentUser = it
                        }
                        PrimaryButton(
                            onClick = {
                                val user = if (onlyCurrentUser) currentUserId.toString() else "all"
                                runOperation("pm clear --user $user ${app.packageName}")
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("清除数据") }
                    }

                    AppPage.UNINSTALL -> {
                        Text(
                            "卸载 ${app.label}。系统应用或受保护应用可能无法卸载。",
                            style = MiuixTheme.textStyles.body2,
                        )
                        OptionRow("保留应用数据", "卸载时保留数据目录（部分系统版本支持）", keepData) {
                            keepData = it
                        }
                        OptionRow("仅从当前用户卸载", "其它用户仍保留该应用", uninstallCurrentUser) {
                            uninstallCurrentUser = it
                        }
                        PrimaryButton(
                            onClick = {
                                val userPart = if (uninstallCurrentUser) "--user ${currentUserId} " else ""
                                val keepPart = if (keepData) "--keep-data " else ""
                                runOperation(
                                    "pm uninstall $userPart$keepPart${app.packageName}",
                                    closeOnSuccess = true,
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("卸载", maxLines = 1) }
                    }

                    AppPage.FREEZE -> {
                        Text(
                            "选择冻结方式。冻结系统应用可能导致系统功能异常，请谨慎操作。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FreezeMode.entries.forEach { mode ->
                                SelectChip(
                                    selected = freezeMode == mode,
                                    onClick = { freezeMode = mode },
                                    label = mode.label,
                                )
                            }
                        }
                        Text(
                            freezeMode.description,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(
                                onClick = {
                                    runOperation(freezeMode.freeze(app.packageName, currentUserId))
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("冻结", maxLines = 1) }
                            SecondaryButton(
                                onClick = {
                                    runOperation(freezeMode.unfreeze(app.packageName, currentUserId))
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("解冻", maxLines = 1) }
                        }
                    }

                    AppPage.DEX2OAT -> {
                        Text(
                            "四种方式互斥，选择一种后执行。Dex2oat 可能需要较长时间。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DexMode.entries.forEach { mode ->
                                SelectChip(
                                    selected = dexMode == mode,
                                    onClick = { dexMode = mode },
                                    label = mode.label,
                                )
                            }
                        }
                        Text(
                            dexMode.description,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        PrimaryButton(
                            onClick = {
                                runOperation(dexMode.command(app.packageName, currentUserId))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("执行 Dex2oat", maxLines = 1) }
                    }

                    AppPage.ACTIVITIES -> {
                        if (activitiesLoading) {
                            Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            Text(
                                "共 ${activityNames.size} 个 Activity",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (activityNames.isEmpty()) {
                                Text("没有可枚举的 Activity。", style = MiuixTheme.textStyles.body2)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    activityNames.forEach { name ->
                                        Text(
                                            name,
                                            style = MiuixTheme.textStyles.footnote1,
                                            fontFamily = FontFamily.Monospace,
                                            color = MiuixTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            SecondaryButton(
                                onClick = {
                                    scope.launch {
                                        activitiesLoading = true
                                        activityNames = withContext(Dispatchers.Default) {
                                            loadActivityNames(pm, app.packageName)
                                        }
                                        activitiesLoading = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("刷新列表") }
                        }
                    }
                }

                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                        Text("正在执行…", style = MiuixTheme.textStyles.footnote1)
                    }
                }

                result?.let {
                    MessageCard(
                        "执行结果",
                        it,
                        MiuixTheme.colorScheme.secondaryContainer,
                        MiuixTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(app: ManagedApp) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app.icon, app.label)
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MiuixTheme.textStyles.headline2, maxLines = 2)
            Text(
                "${app.versionName ?: "未知版本"} · ${app.packageName}",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppInfoCard(app: ManagedApp, onCopy: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfoLine("应用包名", app.packageName, onCopy)
            InfoLine(
                "安装路径",
                buildString {
                    append(app.source)
                    if (app.splits.isNotEmpty()) append("\n+ ${app.splits.size} 个拆分包")
                },
                onCopy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile("TargetSDK", "SDK ${app.targetSdk}", Modifier.weight(1f))
                InfoTile("MinSDK", "SDK ${app.minSdk}", Modifier.weight(1f))
            }
            if (app.isSystem) {
                Text(
                    "系统应用",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, onCopy: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            Text(
                value,
                style = MiuixTheme.textStyles.footnote1,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
        }
        IconButton(onClick = { onCopy(value) }, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "复制$label",
                tint = colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.body2)
            Text(
                subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppCheckbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body2)
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text("›", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ManagedAppRow(app: ManagedApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(app.icon, app.label)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (app.isSystem) "系统" else "用户",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun AppIcon(bitmap: Bitmap?, label: String) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = label,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/* ------------------------------ 扫描与命令 ------------------------------ */

/** 当前用户的 userId：不同 Android 版本对公开 API 的暴露不一致，优先走反射，失败时退回 0。 */
private fun currentAndroidUserId(): Int {
    runCatching {
        val processClass = Class.forName("android.os.Process")
        val handle = processClass.getMethod("myUserHandle").invoke(null)
        val id = handle?.javaClass?.getMethod("getIdentifier")?.invoke(handle) as? Int
        if (id != null) return id
    }
    runCatching {
        val userHandleClass = Class.forName("android.os.UserHandle")
        return userHandleClass
            .getMethod("getUserId", Int::class.javaPrimitiveType!!)
            .invoke(null, android.os.Process.myUid()) as Int
    }
    return 0
}

private fun loadManagedApps(context: Context, pm: PackageManager): List<ManagedApp> {
    @Suppress("DEPRECATION")
    val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
    return packages.mapNotNull { info ->
        val ai = info.applicationInfo ?: return@mapNotNull null
        val source = ai.sourceDir?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(ai.packageName)
        val icon = runCatching { drawableToBitmap(pm.getApplicationIcon(ai)) }.getOrNull()
        ManagedApp(
            label = label,
            packageName = ai.packageName,
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            source = source,
            splits = ai.splitSourceDirs?.filter { it.isNotBlank() }.orEmpty(),
            icon = icon,
            targetSdk = ai.targetSdkVersion,
            minSdk = ai.minSdkVersion,
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun loadActivityNames(pm: PackageManager, packageName: String): List<String> {
    return runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            .activities
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }.getOrDefault(emptyList())
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private suspend fun runPrivileged(context: Context, command: String): PrivilegedResult =
    withContext(Dispatchers.IO) {
        val output = StringBuilder()
        try {
            val code = HighPermissionManager.execute(context, command, onLine = { isError, line ->
                if (output.length < 6000) {
                    if (isError) output.append("[stderr] ")
                    output.append(line).append('\n')
                }
            })
            PrivilegedResult(code, output.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PrivilegedResult(-1, "执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

private suspend fun exportManagedApk(context: Context, app: ManagedApp): String =
    withContext(Dispatchers.IO) {
        val files = (listOf(app.source) + app.splits).map { File(it) }
        val missing = files.firstOrNull { !it.exists() }
        if (missing != null) return@withContext "找不到安装包文件：${missing.path}"
        val asBundle = app.splits.isNotEmpty()
        val extension = if (asBundle) "apks" else "apk"
        val mime = if (asBundle) {
            "application/octet-stream"
        } else {
            "application/vnd.android.package-archive"
        }
        val safeLabel = app.label.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "app" }
        val name = "${safeLabel}_v${app.versionCode}.$extension"
        val target = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/toTool")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("无法创建下载文件")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        writeApkFiles(out, files, asBundle)
                    } ?: throw IllegalStateException("无法写入下载文件")
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
                    ?: throw IllegalStateException("没有可用的下载目录")
                dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { writeApkFiles(it, files, asBundle) }
                file.absolutePath
            }
        }.getOrNull()
        if (target == null) {
            "导出失败：无法写入文件"
        } else {
            "已保存到 $target" + if (asBundle) "\n（AAB 应用，已合并 ${app.splits.size} 个拆分包）" else ""
        }
    }

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
            if (!used.add(entryName)) {
                entryName = "${index}_$entryName"
                used.add(entryName)
            }
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

private fun startExternal(context: Context, intent: Intent): Boolean =
    runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
