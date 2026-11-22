package com.mingdai.toTool.storage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.R
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.media.humanSize
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.MessageCard
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.theme.ToolyTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class StorageSnapshot(
    val appFiles: Long,
    val cache: Long,
    val databases: Long,
    val preferences: Long,
    val packageBytes: Long,
    val codeCache: Long,
    val other: Long,
    val usable: Long,
    val totalSpace: Long,
) {
    val total: Long
        get() = appFiles + cache + databases + preferences + packageBytes + codeCache + other
}

class ManageSpaceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            ToolyTheme(
                mode = prefs.themeMode(),
                customHue = prefs.customHue(),
                customSaturation = prefs.customSaturation(),
                customValue = prefs.customValue(),
            ) {
                ManageSpaceScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun ManageSpaceScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun refresh() {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            snapshot = try {
                scanStorage(context)
            } catch (e: Exception) {
                error = "统计存储空间失败：${e.message ?: e.javaClass.simpleName}"
                null
            }
            busy = false
        }
    }

    fun clearCache() {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            val before = snapshot?.cache ?: 0L
            try {
                withContext(Dispatchers.IO) { clearCacheDirs(context) }
            } catch (e: Exception) {
                error = "清理缓存失败：${e.message ?: e.javaClass.simpleName}"
            }
            snapshot = try {
                scanStorage(context)
            } catch (e: Exception) {
                if (error == null) {
                    error = "重新统计存储空间失败：${e.message ?: e.javaClass.simpleName}"
                }
                null
            }
            val after = snapshot?.cache ?: 0L
            if (error == null) {
                notice = "已清理 ${humanSize((before - after).coerceAtLeast(0L))} 缓存。"
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.manage_space_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ToolBody(padding) {
            val current = snapshot
            if (busy && current == null) {
                LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                Text(
                    "正在统计应用存储空间…",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (current != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "应用占用",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(humanSize(current.total), style = MiuixTheme.textStyles.title2)
                        Text(
                            "设备可用 ${humanSize(current.usable)} / 共 ${humanSize(current.totalSpace)}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        StorageRow("应用文件", current.appFiles)
                        StorageRow("缓存", current.cache, "可安全清理")
                        StorageRow("数据库", current.databases)
                        StorageRow("偏好设置", current.preferences)
                        StorageRow("安装包", current.packageBytes)
                        if (current.codeCache > 0L) {
                            StorageRow("代码缓存", current.codeCache)
                        }
                        if (current.other > 0L) {
                            StorageRow("其它数据", current.other)
                        }
                    }
                }
                Text(
                    "清理缓存只会删除缓存目录中的临时文件，不会删除应用文件、数据库或设置。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (busy && current != null) {
                LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
            }
            notice?.let {
                MessageCard(
                    title = null,
                    message = it,
                    container = MiuixTheme.colorScheme.secondaryContainer,
                    content = MiuixTheme.colorScheme.onSecondaryContainer,
                )
            }
            error?.let {
                MessageCard(
                    title = "存储空间",
                    message = it,
                    container = MiuixTheme.colorScheme.errorContainer,
                    content = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            PrimaryButton(
                onClick = { confirmClear = true },
                enabled = !busy && (current?.cache ?: 0L) > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清理缓存") }
            SecondaryButton(
                onClick = { refresh() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("刷新") }
        }
    }

    AppDialog(
        show = confirmClear,
        title = "清理缓存？",
        summary = "只会删除缓存目录中的临时文件，不会删除应用文件、数据库或设置。",
        onDismiss = { confirmClear = false },
        confirmText = "清理",
        onConfirm = {
            confirmClear = false
            clearCache()
        },
    )
}

@Composable
private fun StorageRow(label: String, bytes: Long, caption: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MiuixTheme.textStyles.body1)
            if (caption != null) {
                Text(
                    caption,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Text(humanSize(bytes), style = MiuixTheme.textStyles.body2)
    }
}

private suspend fun scanStorage(context: Context): StorageSnapshot = withContext(Dispatchers.IO) {
    val dataDir = File(context.applicationInfo.dataDir)
    val appFiles = directorySize(context.filesDir) + directorySize(context.getExternalFilesDir(null))
    val cache = directorySize(context.cacheDir) + directorySize(context.externalCacheDir)
    val databases = directorySize(File(dataDir, "databases"))
    val preferences = directorySize(File(dataDir, "shared_prefs"))
    val other = directorySize(context.noBackupFilesDir)
    val packageBytes = (
        listOf(context.applicationInfo.sourceDir) +
            (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
        ).sumOf { File(it).length() }
    val codeCache = directorySize(context.codeCacheDir)
    val probe = context.filesDir
    StorageSnapshot(
        appFiles = appFiles,
        cache = cache,
        databases = databases,
        preferences = preferences,
        packageBytes = packageBytes,
        codeCache = codeCache,
        other = other,
        usable = probe.usableSpace,
        totalSpace = probe.totalSpace,
    )
}

private fun directorySize(file: File?): Long {
    if (file == null || !file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
}

private fun clearCacheDirs(context: Context) {
    listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
