package com.mingdai.toTool

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
        // 只在“全新启动”时读快捷方式带来的工具 id：Activity 被系统重建（切换深色模式、进程被回收后恢复）时
        // intent 还是最初那个，再读一次会把已经关掉的工具又打开一遍。
        if (savedInstanceState == null) {
            deepLinkToolId = intent?.getStringExtra(EXTRA_TOOL_ID)
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
                CompositionLocalProvider(
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
        deepLinkToolId = intent.getStringExtra(EXTRA_TOOL_ID)
    }

    companion object {
        /** Intent extra carrying a tool id to open directly (pinned shortcuts). */
        const val EXTRA_TOOL_ID = "extra_tool_id"
    }
}
