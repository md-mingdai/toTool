package com.mingdai.toTool.videoparse

import android.content.Context

internal object VideoCookieStore {
    private const val PREFS = "video_parser_cookies"

    fun get(context: Context, platform: VideoPlatform): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(platform.name, "").orEmpty()

    fun set(context: Context, platform: VideoPlatform, cookie: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(platform.name, cookie.trim()).apply()
    }
}
