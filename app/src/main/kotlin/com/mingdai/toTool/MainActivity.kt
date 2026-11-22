package com.mingdai.toTool

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mingdai.toTool.data.BehaviorState
import com.mingdai.toTool.data.DisplayState
import com.mingdai.toTool.data.FavoritesState
import com.mingdai.toTool.data.IncomingPackage
import com.mingdai.toTool.data.LocalBehaviorState
import com.mingdai.toTool.data.LocalDisplayState
import com.mingdai.toTool.data.LocalFavorites
import com.mingdai.toTool.data.LocalPrefs
import com.mingdai.toTool.data.LocalThemeState
import com.mingdai.toTool.data.LocalUnitLabelState
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.data.ThemeState
import com.mingdai.toTool.data.UnitLabelState
import com.mingdai.toTool.ui.nav.AppNavGraph
import com.mingdai.toTool.ui.theme.ToolyTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    /** Tool id delivered by a pinned home-screen shortcut, if any. */
    private var deepLinkToolId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        // 只在“全新启动”时读外部送来的内容（快捷方式的工具 id、“打开方式”送来的安装包）：
        // Activity 被系统重建（切换深色模式、进程被回收后恢复）时 intent 还是最初那个，
        // 再读一次会把已经关掉的工具又打开一遍。
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            // 这些状态对象必须 remember：否则每次重组都会新建一份，Local 提供的实例不断变化。
            val themeState = remember {
                ThemeState(
                    initial = prefs.themeMode(),
                    initialHue = prefs.customHue(),
                    initialSat = prefs.customSaturation(),
                    initialVal = prefs.customValue(),
                    prefs = prefs,
                )
            }
            val favorites = remember { FavoritesState(prefs) }
            val display = remember { DisplayState(prefs) }
            val unitLabel = remember { UnitLabelState(prefs) }
            val behavior = remember { BehaviorState(prefs) }
            ToolyTheme(
                mode = themeState.mode,
                customHue = themeState.hue,
                customSaturation = themeState.saturation,
                customValue = themeState.value,
            ) {
                // 平台拉伸式 overscroll（Android 12+ 拉伸，低版本发光）；不要用 Miuix 默认的位移式效果。
                val platformOverscrollFactory = rememberPlatformOverscrollFactory(
                    glowColor = MiuixTheme.colorScheme.primary,
                )
                CompositionLocalProvider(
                    LocalOverscrollFactory provides platformOverscrollFactory,
                    LocalPrefs provides prefs,
                    LocalFavorites provides favorites,
                    LocalThemeState provides themeState,
                    LocalDisplayState provides display,
                    LocalUnitLabelState provides unitLabel,
                    LocalBehaviorState provides behavior,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.surface),
                    ) {
                        AppNavGraph(
                            deepLinkToolId = deepLinkToolId,
                            onDeepLinkConsumed = { deepLinkToolId = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理外部送来的 Intent：
     *  - 带 [EXTRA_TOOL_ID]：桌面快捷方式，直接打开对应的工具；
     *  - 没有工具 id、但是 ACTION_VIEW + content / file 数据：别的应用用「打开方式」送来的
     *    .apks / .xapk / .apkm，交给「安装特殊应用」读取。
     * 工具快捷方式的 Intent 不带数据，所以两种情况不会混淆。
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val toolId = intent.getStringExtra(EXTRA_TOOL_ID)
        val data = intent.data
        val scheme = data?.scheme
        if (toolId.isNullOrEmpty() && intent.action == Intent.ACTION_VIEW &&
            data != null && (scheme == "content" || scheme == "file")
        ) {
            IncomingPackage.uri = data
            deepLinkToolId = INSTALLER_TOOL_ID
        } else {
            deepLinkToolId = toolId
        }
    }

    companion object {
        /** Intent extra carrying a tool id to open directly (pinned shortcuts). */
        const val EXTRA_TOOL_ID = "extra_tool_id"

        /** 「安装特殊应用」工具的 id：收到安装包文件时跳到这里。 */
        private const val INSTALLER_TOOL_ID = "dev_installer"
    }
}
