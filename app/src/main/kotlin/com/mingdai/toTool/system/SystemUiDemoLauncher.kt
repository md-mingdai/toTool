package com.mingdai.toTool.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 直接拉起 SystemUI 自带的 DemoMode；它没有工具页面，点击即跳转。 */
object SystemUiDemoLauncher {
    const val TOOL_ID = "dev_systemui_demo"

    fun launch(context: Context): Boolean {
        val intent = Intent()
            .setComponent(ComponentName("com.android.systemui", "com.android.systemui.DemoMode"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "当前系统没有提供 SystemUI DemoMode，或该入口已被 ROM 移除",
                Toast.LENGTH_LONG,
            ).show()
            false
        }
    }

    fun handle(context: Context, toolId: String?): Boolean {
        if (toolId != TOOL_ID) return false
        launch(context)
        return true
    }
}
