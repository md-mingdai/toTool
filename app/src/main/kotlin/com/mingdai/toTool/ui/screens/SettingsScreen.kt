package com.mingdai.toTool.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavHostController
import com.mingdai.toTool.R
import com.mingdai.toTool.data.LocalBehaviorState
import com.mingdai.toTool.data.LocalDisplayState
import com.mingdai.toTool.data.LocalFavorites
import com.mingdai.toTool.data.LocalThemeState
import com.mingdai.toTool.data.LocalUnitLabelState
import com.mingdai.toTool.data.SHORTCUT_RADIUS_BASE_PX
import com.mingdai.toTool.data.SHORTCUT_RADIUS_MAX
import com.mingdai.toTool.data.ToolCategory
import com.mingdai.toTool.data.ToolRegistry
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ColorWheelPicker
import com.mingdai.toTool.ui.components.toolShortcutBitmap
import com.mingdai.toTool.ui.theme.ThemeMode
import com.mingdai.toTool.permission.HighPermissionManager
import com.mingdai.toTool.permission.HighPermissionStrategy
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

/** 自定义主题色的默认值，和 Prefs 里的默认值保持一致。 */
private const val DefaultHue = 0f
private const val DefaultSaturation = 0.8f
private const val DefaultValue = 0.55f

private class LicenseItem(val name: String, val usage: String, val license: String)

private val Licenses = listOf(
    LicenseItem("Jetpack Compose / AndroidX", "界面框架与基础库", "Apache License 2.0"),
    LicenseItem("Miuix", "小米风格的界面组件", "Apache License 2.0"),
    LicenseItem("ZXing", "二维码生成", "Apache License 2.0"),
    LicenseItem("Ktor", "隔空闪传的局域网 HTTP 服务", "Apache License 2.0"),
    LicenseItem("React / Vite / Phosphor / noble", "隔空闪传网页前端", "MIT / ISC"),
    LicenseItem("Space Grotesk / JetBrains Mono", "隔空闪传网页字体", "SIL Open Font License 1.1"),
    LicenseItem("FFmpeg", "音视频格式转换（内置的可执行文件，纯 LGPL 构建，不含 GPL / nonfree 组件）", "LGPL 2.1+"),
    LicenseItem("LAME", "FFmpeg 里的 MP3 编码", "LGPL 2.0+"),
    LicenseItem("Opus、libvorbis、libogg", "FFmpeg 里的 Opus / Vorbis 编码", "BSD 3-Clause"),
    LicenseItem("Onest 字体", "界面字体", "SIL Open Font License 1.1"),
)

@Composable
fun SettingsScreen(navController: NavHostController) {
    val themeState = LocalThemeState.current
    val display = LocalDisplayState.current
    val unitLabel = LocalUnitLabelState.current
    val behavior = LocalBehaviorState.current
    val favorites = LocalFavorites.current
    val context = LocalContext.current

    val (versionName, versionCode) = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "1.0") to PackageInfoCompat.getLongVersionCode(info)
        }.getOrDefault("1.0" to 0L)
    }

    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showColorDialog by remember { mutableStateOf(false) }
    var showClearFavorites by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showFontLicense by remember { mutableStateOf(false) }
    var showFfmpegLicense by remember { mutableStateOf(false) }
    var permissionStrategy by remember { mutableStateOf(HighPermissionManager.strategy(context)) }

    val themeOptions = listOf(
        ThemeMode.SYSTEM to R.string.theme_system,
        ThemeMode.CUSTOM to R.string.theme_custom,
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.DARK to R.string.theme_dark,
        ThemeMode.DYNAMIC to R.string.theme_dynamic,
    )

    val favoriteCount = favorites.favorites.size
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()),
        ) {
            AppHeaderCard(
                versionName = versionName,
                versionCode = versionCode,
                toolCount = ToolRegistry.all.size,
            )

            // ── 主题 ─────────────────────────────────────────────
            SmallTitle(text = stringResource(R.string.settings_theme))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                themeOptions.forEach { (mode, labelRes) ->
                    val enabled = mode != ThemeMode.DYNAMIC || supportsDynamic
                    RadioButtonPreference(
                        title = stringResource(labelRes),
                        selected = themeState.mode == mode,
                        onClick = if (enabled) ({ themeState.set(mode) }) else null,
                        enabled = enabled,
                    )
                }
                if (themeState.mode == ThemeMode.CUSTOM) {
                    ArrowPreference(
                        title = stringResource(R.string.pick_color),
                        endActions = {
                            // 当前自选色的预览圆点
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.hsv(themeState.hue, themeState.saturation, themeState.value)),
                            )
                        },
                        onClick = { showColorDialog = true },
                    )
                }
            }

            // ── 显示 ─────────────────────────────────────────────
            SmallTitle(text = stringResource(R.string.settings_display))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = display.compact,
                    onCheckedChange = { display.set(it) },
                    title = stringResource(R.string.settings_compact),
                    summary = stringResource(R.string.settings_compact_desc),
                )
                SwitchPreference(
                    checked = behavior.expandCategories,
                    onCheckedChange = { behavior.updateExpandCategories(it) },
                    title = "分类默认展开",
                    summary = "紧凑显示下，进入主界面时各分类默认展开；关闭则默认收起",
                    enabled = display.compact,
                )
                SwitchPreference(
                    checked = unitLabel.useAbbr,
                    onCheckedChange = { unitLabel.set(it) },
                    title = stringResource(R.string.settings_unit_abbr),
                    summary = stringResource(R.string.settings_unit_abbr_desc),
                )
            }

            // ── 交互 ─────────────────────────────────────────────
            SmallTitle(text = "交互")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = behavior.haptics,
                    onCheckedChange = { behavior.updateHaptics(it) },
                    title = "触感反馈",
                    summary = "列表拉到边缘、长按工具、长按复制等操作时给出振动反馈",
                )
                SwitchPreference(
                    checked = behavior.keepScreenOn,
                    onCheckedChange = { behavior.updateKeepScreenOn(it) },
                    title = "使用工具时保持常亮",
                    summary = "打开任意工具期间屏幕不自动熄灭（时钟、坏点检测等工具始终常亮）",
                )
            }

            // ── 高级权限 ─────────────────────────────────────────
            SmallTitle(text = "高级权限")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "执行 Shell、米游记录获取等高级工具时使用的权限来源。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip(
                            selected = permissionStrategy == HighPermissionStrategy.SHIZUKU,
                            onClick = {
                                HighPermissionManager.setStrategy(context, HighPermissionStrategy.SHIZUKU)
                                permissionStrategy = HighPermissionStrategy.SHIZUKU
                            },
                            label = "Shizuku",
                        )
                        SelectChip(
                            selected = permissionStrategy == HighPermissionStrategy.ROOT,
                            onClick = {
                                HighPermissionManager.setStrategy(context, HighPermissionStrategy.ROOT)
                                permissionStrategy = HighPermissionStrategy.ROOT
                            },
                            label = "Root",
                        )
                    }
                    val permissionStatus = HighPermissionManager.status(context)
                    Text(
                        permissionStatus.detail,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (permissionStatus.ready) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ── 桌面快捷方式 ─────────────────────────────────────
            SmallTitle(text = "桌面快捷方式")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ShortcutRadiusSetting(
                    radius = behavior.shortcutCornerRadius,
                    onRadiusChange = { behavior.updateShortcutCornerRadius(it) },
                )
            }

            // ── 数据 ─────────────────────────────────────────────
            SmallTitle(text = "数据")
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = "清除全部收藏",
                    summary = if (favoriteCount == 0) "还没有收藏的工具" else "已收藏 $favoriteCount 个工具",
                    enabled = favoriteCount > 0,
                    onClick = { showClearFavorites = true },
                )
                ArrowPreference(
                    title = "恢复默认设置",
                    summary = "主题、显示、交互与快捷方式选项恢复为默认值，收藏不受影响",
                    onClick = { showReset = true },
                )
            }

            // ── 关于 ─────────────────────────────────────────────
            SmallTitle(text = stringResource(R.string.settings_about))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                AboutRow(stringResource(R.string.about_developer), "mingdai")
                AboutRow(stringResource(R.string.about_version), "$versionName（$versionCode）")
                AboutRow("工具数量", "${ToolRegistry.all.size} 个")
                ArrowPreference(
                    title = "开源许可",
                    summary = "本应用使用的开源组件",
                    onClick = { showLicenses = true },
                )
                ArrowPreference(
                    title = "字体",
                    summary = "Onest · SIL OFL 1.1（点击查看许可全文）",
                    onClick = { showFontLicense = true },
                )
                ArrowPreference(
                    title = "FFmpeg",
                    summary = "纯 LGPL 构建 · 点击查看许可说明和源码获取方式",
                    onClick = { showFfmpegLicense = true },
                )
            }
            // 给底部手势条留出空间
            Column(modifier = Modifier.padding(bottom = 24.dp)) {}
        }
    }

    // ── 对话框 ────────────────────────────────────────────────────
    AppDialog(
        show = showColorDialog,
        title = stringResource(R.string.theme_custom),
        onDismiss = { showColorDialog = false },
        confirmText = stringResource(R.string.action_done),
        dismissText = null,
    ) {
        ColorWheelPicker(
            hue = themeState.hue,
            saturation = themeState.saturation,
            value = themeState.value,
            onHueChange = { themeState.updateHue(it) },
            onSaturationChange = { themeState.updateSaturation(it) },
            onValueChange = { themeState.updateValue(it) },
        )
    }

    AppDialog(
        show = showClearFavorites,
        title = "清除全部收藏？",
        summary = "将移除 $favoriteCount 个已收藏的工具，此操作无法撤销。",
        onDismiss = { showClearFavorites = false },
        confirmText = "清除",
        onConfirm = {
            favorites.clear()
            showClearFavorites = false
            Toast.makeText(context, "已清除全部收藏", Toast.LENGTH_SHORT).show()
        },
    )

    AppDialog(
        show = showReset,
        title = "恢复默认设置？",
        summary = "主题、显示、交互和快捷方式选项都会恢复为默认值；收藏的工具不受影响。",
        onDismiss = { showReset = false },
        confirmText = "恢复",
        onConfirm = {
            themeState.set(ThemeMode.SYSTEM)
            themeState.updateHue(DefaultHue)
            themeState.updateSaturation(DefaultSaturation)
            themeState.updateValue(DefaultValue)
            display.set(true)
            unitLabel.set(false)
            behavior.reset()
            showReset = false
            Toast.makeText(context, "已恢复默认设置", Toast.LENGTH_SHORT).show()
        },
    )

    AppDialog(
        show = showLicenses,
        title = "开源许可",
        onDismiss = { showLicenses = false },
        confirmText = "关闭",
        dismissText = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Licenses.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.name,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.usage,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = item.license,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // 字体许可全文随应用打包在 assets/licenses 里
    val fontLicenseText = remember(showFontLicense) {
        if (!showFontLicense) {
            ""
        } else {
            runCatching {
                context.assets.open("licenses/Onest-OFL.txt").bufferedReader().use { it.readText() }
            }.getOrElse { "无法读取许可文本。" }
        }
    }
    AppDialog(
        show = showFontLicense,
        title = "Onest · SIL OFL 1.1",
        onDismiss = { showFontLicense = false },
        confirmText = "关闭",
        dismissText = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = fontLicenseText,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    // FFmpeg 的许可说明（含 LGPL 全文）同样随应用打包在 assets/licenses 里
    val ffmpegLicenseText = remember(showFfmpegLicense) {
        if (!showFfmpegLicense) {
            ""
        } else {
            runCatching {
                context.assets.open("licenses/FFMPEG.txt").bufferedReader().use { it.readText() }
            }.getOrElse { "无法读取许可文本。" }
        }
    }
    AppDialog(
        show = showFfmpegLicense,
        title = "FFmpeg · LGPL 2.1+",
        onDismiss = { showFfmpegLicense = false },
        confirmText = "关闭",
        dismissText = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = ffmpegLicenseText,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 顶部的应用信息卡：图标、名称、版本号、工具数量。底色是主题色的淡渐变。 */
@Composable
private fun AppHeaderCard(versionName: String, versionCode: Long, toolCount: Int) {
    val context = LocalContext.current
    val primary = MiuixTheme.colorScheme.primary
    val icon = remember {
        runCatching {
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(192, 192)?.asImageBitmap()
        }.getOrNull()
    }

    Card(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(primary.copy(alpha = 0.16f), primary.copy(alpha = 0.03f)),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "版本 $versionName（$versionCode）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = "共 $toolCount 个实用工具",
                        style = MiuixTheme.textStyles.footnote1,
                        color = primary,
                    )
                }
            }
        }
    }
}

/**
 * 「桌面快捷方式」圆角：滑块 + 实时预览。
 * 预览是每个分类各取一个工具，用和真正添加到桌面时同一套代码画出来的，所见即所得。
 */
@Composable
private fun ShortcutRadiusSetting(radius: Int, onRadiusChange: (Int) -> Unit) {
    val samples = remember {
        ToolCategory.values().mapNotNull { category -> ToolRegistry.byCategory(category).firstOrNull() }
    }
    val previews = remember(radius) {
        samples.map { tool -> toolShortcutBitmap(tool, 128, radius).asImageBitmap() }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "快捷方式圆角",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${radius}px",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Text(
            text = "工具添加到桌面时图标的圆角半径。按 ${SHORTCUT_RADIUS_BASE_PX}px 边长的图标换算，实际图标等比放大；" +
                "0 为直角，${SHORTCUT_RADIUS_MAX} 为正圆。已添加的快捷方式不会变化，重新添加同一个工具即可更新。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            previews.forEach { bitmap ->
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
            }
        }
        Slider(
            value = radius.toFloat(),
            onValueChange = { onRadiusChange(it.roundToInt()) },
            valueRange = 0f..SHORTCUT_RADIUS_MAX.toFloat(),
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    BasicComponent(
        title = label,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
}
