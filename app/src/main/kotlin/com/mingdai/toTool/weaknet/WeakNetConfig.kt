package com.mingdai.toTool.weaknet

import android.content.Context

data class WeakNetConfig(
    val packageName: String? = null,
    val appLabel: String? = null,
    val hostFilter: String = "",
    val downloadKBps: Int = 128,
    val uploadKBps: Int = 64,
    val latencyMs: Int = 300,
    val showOverlay: Boolean = true,
) {
    fun normalized(): WeakNetConfig = copy(
        hostFilter = hostFilter.trim().take(200),
        downloadKBps = downloadKBps.coerceIn(1, 102400),
        uploadKBps = uploadKBps.coerceIn(1, 102400),
        latencyMs = latencyMs.coerceIn(0, 10_000),
        appLabel = appLabel?.take(80),
        packageName = packageName?.take(200),
    )

    val targetLabel: String
        get() = when {
            !appLabel.isNullOrBlank() && hostFilter.isNotBlank() -> "$appLabel · $hostFilter"
            !appLabel.isNullOrBlank() -> appLabel
            hostFilter.isNotBlank() -> hostFilter
            else -> "所有应用"
        }
}

object WeakNetStore {
    private const val NAME = "weaknet_prefs"
    private const val KEY_PACKAGE = "package_name"
    private const val KEY_LABEL = "app_label"
    private const val KEY_HOST = "host_filter"
    private const val KEY_DOWNLOAD = "download_kbps"
    private const val KEY_UPLOAD = "upload_kbps"
    private const val KEY_LATENCY = "latency_ms"
    private const val KEY_OVERLAY = "show_overlay"

    fun load(context: Context): WeakNetConfig {
        val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return WeakNetConfig(
            packageName = sp.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() },
            appLabel = sp.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() },
            hostFilter = sp.getString(KEY_HOST, "") ?: "",
            downloadKBps = sp.getInt(KEY_DOWNLOAD, 128),
            uploadKBps = sp.getInt(KEY_UPLOAD, 64),
            latencyMs = sp.getInt(KEY_LATENCY, 300),
            showOverlay = sp.getBoolean(KEY_OVERLAY, true),
        ).normalized()
    }

    fun save(context: Context, config: WeakNetConfig) {
        val value = config.normalized()
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PACKAGE, value.packageName)
            .putString(KEY_LABEL, value.appLabel)
            .putString(KEY_HOST, value.hostFilter)
            .putInt(KEY_DOWNLOAD, value.downloadKBps)
            .putInt(KEY_UPLOAD, value.uploadKBps)
            .putInt(KEY_LATENCY, value.latencyMs)
            .putBoolean(KEY_OVERLAY, value.showOverlay)
            .apply()
    }
}
