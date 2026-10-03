package com.mingdai.toTool.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.mingdai.toTool.data.ToolRegistry
import com.mingdai.toTool.ui.components.ToolScaffold
import com.mingdai.toTool.ui.screens.tools.ApkExtractorTool
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
import com.mingdai.toTool.ui.screens.tools.FlashlightTool
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
import com.mingdai.toTool.ui.screens.tools.QrTool
import com.mingdai.toTool.ui.screens.tools.RulerTool
import com.mingdai.toTool.ui.screens.tools.ScoreboardTool
import com.mingdai.toTool.ui.screens.tools.SensorListTool
import com.mingdai.toTool.ui.screens.tools.SosFlashTool
import com.mingdai.toTool.ui.screens.tools.SpiritLevelTool
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
 * Routes a tool id to its screen, wrapped in the standard tool scaffold
 * (back navigation + favorite toggle).
 */
@Composable
fun ToolRouter(navController: NavHostController, toolId: String) {
    val tool = ToolRegistry.byId[toolId]
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

    if (tool.id == "dev_pixelcheck") {
        PixelCheckFullscreen(navController)
        return
    }

    if (tool.id == "daily_clock") {
        ClockScreenFullscreen(navController)
        return
    }

    if (tool.id == "daily_whiteboard") {
        WhiteboardFullscreen(navController)
        return
    }

    ToolScaffold(
        title = tool.name,
        onBack = { navController.popBackStack() },
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
            // --- 设备工具 ---
            "dev_device" -> DeviceInfoTool(padding)
            "dev_battery" -> BatteryTool(padding)
            "dev_network" -> NetworkTool(padding)
            "dev_sensor" -> SensorListTool(padding)
            "dev_level" -> SpiritLevelTool(padding)
            "dev_apk" -> ApkExtractorTool(padding)
            "dev_vibrate" -> VibratorTool(padding)
            "dev_wallpaper_get" -> WallpaperExtractorTool(padding)
            "dev_wallpaper_video" -> VideoWallpaperTool(padding)
            // --- 日常工具 ---
            "daily_qr" -> QrTool(padding)
            "daily_color" -> ColorTool(padding)
            "daily_stopwatch" -> StopwatchTool(padding)
            "daily_flash" -> FlashlightTool(padding)
            "daily_decibel" -> DecibelTool(padding)
            "daily_ruler" -> RulerTool(padding)
            "daily_compass" -> CompassTool(padding)
            "daily_protractor" -> ProtractorTool(padding)
            "daily_scoreboard" -> ScoreboardTool(padding)
            "daily_sos" -> SosFlashTool(padding)
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
