package com.mingdai.toTool.flashdrop

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.ui.theme.ToolyTheme

/** 后台收到配对请求时弹出的透明窗口；内容与前台共用 [FlashDropPairRequestDialog]。 */
class PairRequestActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        val controller = FlashDropRuntime.get(this, prefs)
        getSystemService(NotificationManager::class.java)?.cancel(PAIR_NOTIFICATION_ID)
        val initialId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        setContent {
            ToolyTheme(
                mode = prefs.themeMode(),
                customHue = prefs.customHue(),
                customSaturation = prefs.customSaturation(),
                customValue = prefs.customValue(),
            ) {
                val request = controller.state.pairRequests.firstOrNull { it.id == initialId }
                    ?: controller.state.pairRequests.firstOrNull()
                LaunchedEffect(request?.id) {
                    if (request == null) finish()
                }
                FlashDropPairRequestDialog(
                    request = request,
                    controller = controller,
                    onFinished = { finish() },
                )
            }
        }
    }
}
