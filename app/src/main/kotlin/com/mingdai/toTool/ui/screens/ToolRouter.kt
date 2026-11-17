package com.mingdai.toTool.ui.screens

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.mingdai.toTool.data.LocalBehaviorState
import com.mingdai.toTool.data.ToolRegistry
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.ToolScaffold
import com.mingdai.toTool.ui.components.findActivity
import com.mingdai.toTool.ui.components.setSystemBarsHidden
import com.mingdai.toTool.ui.screens.tools.ApkExtractorTool
import com.mingdai.toTool.ui.screens.tools.CustomShortcutTool
import com.mingdai.toTool.ui.screens.tools.Base64Tool
import com.mingdai.toTool.ui.screens.tools.BaseConverterTool
import com.mingdai.toTool.ui.screens.tools.BatteryTool
import com.mingdai.toTool.ui.screens.tools.CaseTool
import com.mingdai.toTool.ui.screens.tools.CharCountTool
import com.mingdai.toTool.ui.screens.tools.ClockScreenFullscreen
import com.mingdai.toTool.ui.screens.tools.ColorTool
import com.mingdai.toTool.ui.screens.tools.CompassTool
import com.mingdai.toTool.ui.screens.tools.DateTool
import com.mingdai.toTool.ui.screens.tools.DecibelTool
import com.mingdai.toTool.ui.screens.tools.DeviceInfoTool
import com.mingdai.toTool.ui.screens.tools.EquationSolverTool
import com.mingdai.toTool.ui.screens.tools.FlashDropTool
import com.mingdai.toTool.ui.screens.tools.FlashlightTool
import com.mingdai.toTool.ui.screens.tools.FunctionPlotTool
import com.mingdai.toTool.ui.screens.tools.HashTool
import com.mingdai.toTool.ui.screens.tools.ImageBlurTool
import com.mingdai.toTool.ui.screens.tools.ImageCompressTool
import com.mingdai.toTool.ui.screens.tools.ImageGrayscaleTool
import com.mingdai.toTool.ui.screens.tools.ImagePixelateTool
import com.mingdai.toTool.ui.screens.tools.ImageRoundRectTool
import com.mingdai.toTool.ui.screens.tools.ImageWatermarkTool
import com.mingdai.toTool.ui.screens.tools.JsonTool
import com.mingdai.toTool.ui.screens.tools.LoanTool
import com.mingdai.toTool.ui.screens.tools.NetworkTool
import com.mingdai.toTool.ui.screens.tools.PasswordTool
import com.mingdai.toTool.ui.screens.tools.PercentTool
import com.mingdai.toTool.ui.screens.tools.PixelCheckFullscreen
import com.mingdai.toTool.ui.screens.tools.ProtractorTool
import com.mingdai.toTool.ui.screens.tools.BarcodeTool
import com.mingdai.toTool.ui.screens.tools.RulerTool
import com.mingdai.toTool.ui.screens.tools.ScoreboardTool
import com.mingdai.toTool.ui.screens.tools.SensorListTool
import com.mingdai.toTool.ui.screens.tools.ShellTool
import com.mingdai.toTool.ui.screens.tools.SpiritLevelTool
import com.mingdai.toTool.ui.screens.tools.SplitInstallerTool
import com.mingdai.toTool.ui.screens.tools.StopwatchTool
import com.mingdai.toTool.ui.screens.tools.UnitConverterTool
import com.mingdai.toTool.ui.screens.tools.UrlTool
import com.mingdai.toTool.ui.screens.tools.UuidTool
import com.mingdai.toTool.ui.screens.tools.VibratorTool
import com.mingdai.toTool.ui.screens.tools.VideoWallpaperTool
import com.mingdai.toTool.ui.screens.tools.WallpaperExtractorTool
import com.mingdai.toTool.ui.screens.tools.WhiteboardFullscreen
import com.mingdai.toTool.ui.screens.tools.WhiteboardTool
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 需要独占屏幕的工具对窗口的特殊要求。
 * 统一放在路由这一层处理（而不是散落在各个工具里），这样“返回”时才能和转场同时恢复。
 */
private data class ToolWindowSpec(
    /** 锁定方向（ActivityInfo.SCREEN_ORIENTATION_*）；null 表示不锁。 */
    val orientation: Int? = null,
    /** 隐藏状态栏和导航栏。 */
    val immersive: Boolean = false,
    /** 无论设置如何都保持屏幕常亮。 */
    val keepOn: Boolean = false,
)

private val ToolWindowSpecs: Map<String, ToolWindowSpec> = mapOf(
    "daily_clock" to ToolWindowSpec(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, immersive = true, keepOn = true),
    "dev_pixelcheck" to ToolWindowSpec(immersive = true, keepOn = true),
    "daily_whiteboard" to ToolWindowSpec(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, immersive = true),
    "daily_ruler" to ToolWindowSpec(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    // 这些工具是“盯着看 / 边测边看”的，息屏会打断
    "daily_stopwatch" to ToolWindowSpec(keepOn = true),
    "daily_scoreboard" to ToolWindowSpec(keepOn = true),
    "dev_level" to ToolWindowSpec(keepOn = true),
    "daily_compass" to ToolWindowSpec(keepOn = true),
    "daily_decibel" to ToolWindowSpec(keepOn = true),
    // 网页服务要一直可达，屏幕常亮避免用户刚连上就息屏
    "dev_flashdrop" to ToolWindowSpec(keepOn = true),
)

/**
 * 当前由哪个页面持有“窗口特殊状态”（方向锁 / 隐藏系统栏）。
 * 返回动画播放期间，用户可能已经点开了下一个工具；旧页面稍后销毁时若无条件恢复，会把新页面刚设好的方向锁覆盖掉。
 */
private var windowOwner: Any? = null

/**
 * Routes a tool id to its screen, wrapped in the standard tool scaffold
 * (back navigation + favorite toggle).
 */
@Composable
fun ToolRouter(navController: NavHostController, toolId: String) {
    val tool = ToolRegistry.byId[ToolRegistry.resolve(toolId)]
    if (tool == null) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "未找到",
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未找到工具：$toolId", color = MiuixTheme.colorScheme.error)
            }
        }
        return
    }

    val spec = ToolWindowSpecs[tool.id]
    val activity = LocalContext.current.findActivity()
    val behavior = LocalBehaviorState.current

    // 进入：锁定方向、隐藏系统栏。页面真正销毁（转场播完）时再兜底恢复一次。
    DisposableEffect(tool.id) {
        val token = Any()
        windowOwner = token
        spec?.orientation?.let { activity?.requestedOrientation = it }
        if (spec?.immersive == true) activity?.setSystemBarsHidden(true)
        onDispose {
            if (windowOwner === token) {
                windowOwner = null
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.setSystemBarsHidden(false)
            }
        }
    }
    KeepScreenOn(spec?.keepOn == true || behavior.keepScreenOn)

    // 返回：横屏工具要“切回竖屏”和“返回主界面”同时进行。
    // 以前是等返回动画播完、页面销毁时才解除横屏锁定，所以会先看到横着的主界面，再转过来。
    // 现在 BACK 一触发就立刻请求竖屏、恢复系统栏，同时发起返回转场，二者并行。
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            if (spec?.orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (spec?.immersive == true) activity?.setSystemBarsHidden(false)
            if (!navController.popBackStack()) leaving = false
        }
    }
    // 系统返回键 / 手势返回也走同一个出口（没有特殊窗口要求的工具保持默认的预测性返回动画）
    BackHandler(enabled = spec?.orientation != null || spec?.immersive == true) { leave() }

    when (tool.id) {
        "dev_pixelcheck" -> { PixelCheckFullscreen(navController); return }
        "daily_clock" -> { ClockScreenFullscreen(navController); return }
        "daily_whiteboard" -> { WhiteboardFullscreen(navController); return }
    }

    ToolScaffold(
        title = tool.name,
        onBack = leave,
        favoriteId = tool.id,
    ) { padding ->
        when (tool.id) {
            // --- 文本工具 ---
            "text_charcount" -> CharCountTool(padding)
            "text_case" -> CaseTool(padding)
            "text_password" -> PasswordTool(padding)
            "text_uuid" -> UuidTool(padding)
            "text_base64" -> Base64Tool(padding)
            "text_hash" -> HashTool(padding)
            "text_json" -> JsonTool(padding)
            "text_url" -> UrlTool(padding)
            // --- 计算工具 ---
            "calc_unit" -> UnitConverterTool(padding)
            "calc_base" -> BaseConverterTool(padding)
            "calc_percent" -> PercentTool(padding)
            "calc_date" -> DateTool(padding)
            "calc_loan" -> LoanTool(padding)
            "calc_equation" -> EquationSolverTool(padding)
            "calc_plot" -> FunctionPlotTool(padding)
            // --- 设备工具 ---
            "dev_flashdrop" -> FlashDropTool(padding)
            "dev_device" -> DeviceInfoTool(padding)
            "dev_battery" -> BatteryTool(padding)
            "dev_network" -> NetworkTool(padding)
            "dev_sensor" -> SensorListTool(padding)
            "dev_level" -> SpiritLevelTool(padding)
            "dev_apk" -> ApkExtractorTool(padding)
            "dev_vibrate" -> VibratorTool(padding)
            "dev_installer" -> SplitInstallerTool(padding)
            "dev_shortcut" -> CustomShortcutTool(padding)
            "dev_shell" -> ShellTool(padding)
            "dev_wallpaper_get" -> WallpaperExtractorTool(padding)
            "dev_wallpaper_video" -> VideoWallpaperTool(padding)
            // --- 日常工具 ---
            "daily_qr" -> BarcodeTool(padding)
            "daily_color" -> ColorTool(padding)
            "daily_stopwatch" -> StopwatchTool(padding)
            "daily_flash" -> FlashlightTool(padding)
            "daily_decibel" -> DecibelTool(padding)
            "daily_ruler" -> RulerTool(padding)
            "daily_compass" -> CompassTool(padding)
            "daily_protractor" -> ProtractorTool(padding)
            "daily_scoreboard" -> ScoreboardTool(padding)
            // --- 图片工具 ---
            "img_compress" -> ImageCompressTool(padding)
            "img_grayscale" -> ImageGrayscaleTool(padding)
            "img_pixelate" -> ImagePixelateTool(padding)
            "img_watermark" -> ImageWatermarkTool(padding)
            "img_blur" -> ImageBlurTool(padding)
            "img_roundrect" -> ImageRoundRectTool(padding)
            else -> Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("该工具尚未实现")
            }
        }
    }
}
