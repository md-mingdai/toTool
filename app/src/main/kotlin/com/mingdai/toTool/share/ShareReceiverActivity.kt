package com.mingdai.toTool.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.ui.theme.ToolyTheme

/**
 * 隐藏的分享接收器:在系统分享面板中接收任意 MIME 的文本或文件。
 * 不写入工具列表,只负责展示内容并让用户通过 Documents API 导出。
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val payload = runCatching { parseSharePayload(intent) }.getOrElse {
            SharePayload(null, null, intent?.type, emptyList())
        }
        setContent {
            ToolyTheme(
                mode = prefs.themeMode(),
                customHue = prefs.customHue(),
                customSaturation = prefs.customSaturation(),
                customValue = prefs.customValue(),
            ) {
                ShareDumpScreen(payload = payload, onClose = { finish() })
            }
        }
    }
}
