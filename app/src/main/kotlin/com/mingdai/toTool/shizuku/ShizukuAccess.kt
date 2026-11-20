package com.mingdai.toTool.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

/** Shizuku 当前的可用状态。 */
enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNSUPPORTED,
    NEEDS_PERMISSION,
    DENIED,
    READY,
}

/**
 * Shizuku 授权与跳转的公共封装。供执行 Shell、米游记录获取等需要高级权限的工具复用。
 */
object ShizukuAccess {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 7001
    const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"

    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

    fun state(context: Context): ShizukuState =
        try {
            if (!Shizuku.pingBinder()) {
                if (isInstalled(context)) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            } else if (Shizuku.isPreV11()) {
                ShizukuState.UNSUPPORTED
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.READY
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                ShizukuState.DENIED
            } else {
                ShizukuState.NEEDS_PERMISSION
            }
        } catch (e: Throwable) {
            // 服务恰好在这一刻停掉之类的极端情况：当作没有运行
            ShizukuState.NOT_RUNNING
        }

    /** Shizuku 服务以什么身份运行：adb 启动是 shell（2000），root 启动是 root（0）。 */
    fun identity(): String? =
        try {
            when (val uid = Shizuku.getUid()) {
                0 -> "root（uid 0）"
                2000 -> "shell（uid 2000）"
                else -> "uid $uid"
            }
        } catch (e: Throwable) {
            null
        }

    /**
     * 请求 Shizuku 授权。成功发起返回 null；失败返回可直接展示给用户的错误文本。
     * 授权结果仍由调用方注册的 OnRequestPermissionResultListener 刷新。
     */
    fun requestPermission(): String? =
        try {
            when {
                !Shizuku.pingBinder() -> "Shizuku 还没有运行"
                Shizuku.isPreV11() -> "Shizuku 版本太旧，请先更新"
                else -> {
                    Shizuku.requestPermission(REQUEST_CODE)
                    null
                }
            }
        } catch (e: Throwable) {
            "无法发起授权请求：${e.message ?: e.javaClass.simpleName}"
        }

    fun openShizuku(context: Context): Boolean =
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
                ?: return false
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }

    fun openDownload(context: Context): Boolean =
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            false
        }
}
