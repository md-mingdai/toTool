package com.mingdai.toTool.ui.screens.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.data.IncomingPackage
import com.mingdai.toTool.ui.components.AppCheckbox
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.KeepScreenOn
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 安装特殊应用：安装 APKS / XAPK / APKM / ZIP（内含多个 APK）以及一组散装的 .apk 拆分包。
 *
 * 为什么系统自带的安装器装不了它们：这些文件其实是“压缩包 + 多个 APK”，
 * 必须把主包和需要的拆分包放进同一个 PackageInstaller 会话里一次提交才能装上。
 * 挑哪几个拆分的规则在 SplitApkParser.kt（纯 Kotlin，已单独测过），这里负责：
 *   读文件 → 解析清单 → 让用户确认 → 写会话 → 提交 → 响应系统的确认页 → （可选）复制 OBB。
 */

/** 读 APK 元数据时，超过这个大小的主包就不拷到缓存里解析了（只为显示名字不值得）。 */
private const val PROBE_LIMIT = 300L * 1024 * 1024

/** 拷贝用的缓冲区。 */
private const val COPY_BUFFER = 256 * 1024

/** 进度回调的最小间隔（字节），避免一秒刷新几千次界面。 */
private const val PROGRESS_STEP = 1024 * 1024L

/* ───────────────────────── 数据模型 ───────────────────────── */

private class InstPicked(val uri: Uri, val name: String, val size: Long)

/** 压缩包里的一个 OBB / 数据文件，复制到外部存储根目录下的 [target]。 */
private class InstObb(val zipPath: String, val target: String, val size: Long)

private class InstArchiveInfo(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val icon: Bitmap?,
)

/** 读入后的安装包：元数据 + 每个条目的取流方式。 */
private class InstPackage(
    val format: String,
    val fileName: String,
    val title: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val icon: Bitmap?,
    val plan: SplitPlan,
    val obb: List<InstObb>,
    private val opener: (String) -> InputStream,
    private val closer: () -> Unit,
) {
    fun open(entry: SplitEntry): InputStream = opener(entry.path)
    fun open(item: InstObb): InputStream = opener(item.zipPath)
    fun close() = closer()
}

private class InstZip(val zip: ZipFile, val cleanup: () -> Unit)

private class InstObbResult(val copied: Int, val failed: List<String>)

private sealed interface InstStage {
    data object Idle : InstStage
    data object Loading : InstStage
    data object Ready : InstStage
    data class Writing(val done: Long, val total: Long) : InstStage
    data object Confirming : InstStage
    data class CopyingObb(val done: Long, val total: Long) : InstStage
    data object Deleting : InstStage
    data class Finished(
        val ok: Boolean,
        val title: String,
        val detail: String?,
        val launchPackage: String?,
        val canRetry: Boolean,
    ) : InstStage
}

/* ───────────────────────── 读取与解析 ───────────────────────── */

private fun instDeviceProfile(context: Context): DeviceProfile {
    val locales = context.resources.configuration.locales
    val languages = (0 until locales.size())
        .map { locales.get(it).language.lowercase(Locale.ROOT) }
        .ifEmpty { listOf(Locale.getDefault().language.lowercase(Locale.ROOT)) }
        .distinct()
    return DeviceProfile(
        abis = Build.SUPPORTED_ABIS.toList(),
        densityDpi = context.resources.displayMetrics.densityDpi,
        languages = languages,
    )
}

private fun instDescribe(context: Context, uri: Uri): InstPicked {
    var name: String? = null
    var size = -1L
    try {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    } catch (e: Exception) {
        // 查不到名字和大小也能继续
    }
    return InstPicked(uri, name ?: uri.lastPathSegment ?: "package", if (size > 0) size else -1L)
}

private fun instFormatSize(bytes: Long): String = when {
    bytes < 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

private fun instBitmapFromDrawable(drawable: Drawable): Bitmap? {
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}

/** 用系统解析一个 APK 文件，拿到包名 / 应用名 / 版本 / 图标。 */
private fun instReadArchiveInfo(context: Context, apk: File): InstArchiveInfo? {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
    val app = info.applicationInfo
    var label: String? = null
    var icon: Bitmap? = null
    if (app != null) {
        app.sourceDir = apk.absolutePath
        app.publicSourceDir = apk.absolutePath
        label = try { app.loadLabel(pm).toString() } catch (e: Exception) { null }
        icon = try { instBitmapFromDrawable(app.loadIcon(pm)) } catch (e: Exception) { null }
    }
    return InstArchiveInfo(info.packageName ?: "", label, info.versionName, info.longVersionCode, icon)
}

/** 把主包拷到缓存里解析元数据，用完即删。 */
private fun instProbeBase(context: Context, cacheDir: File, source: () -> InputStream): InstArchiveInfo? {
    val tmp = File(cacheDir, "probe.apk")
    return try {
        source().use { input -> FileOutputStream(tmp).use { out -> input.copyTo(out, COPY_BUFFER) } }
        instReadArchiveInfo(context, tmp)
    } catch (e: Exception) {
        null
    } finally {
        tmp.delete()
    }
}

/** 校验并规范化 OBB / 数据文件的目标路径：只允许落在 Android/obb 或 Android/data 下，不允许 “..”。 */
private fun instObbTarget(raw: String): String? {
    val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    val joined = parts.joinToString("/")
    val lower = joined.lowercase(Locale.ROOT)
    return if (lower.startsWith("android/obb/") || lower.startsWith("android/data/")) {
        "Android/" + joined.substring("android/".length)
    } else {
        null
    }
}

private fun ZipFile.readSmallText(entry: ZipEntry): String {
    if (entry.size > (4L * 1024 * 1024)) throw IOException("${entry.name} 过大")
    return getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
}

/** 能直接拿到可寻址的文件描述符就不拷贝（大型 XAPK 动辄几个 GB）；拿不到返回 null。 */
private fun instOpenZipDirect(context: Context, uri: Uri): ZipFile? {
    return try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            if (pfd.statSize > 0) ZipFile("/proc/self/fd/${pfd.fd}") else null
        }
    } catch (e: ZipException) {
        throw IOException("所选文件不是有效的安装包（不是压缩包或已损坏）")
    } catch (e: Exception) {
        null
    }
}

private fun instOpenZip(context: Context, picked: InstPicked, cacheDir: File): InstZip {
    instOpenZipDirect(context, picked.uri)?.let { return InstZip(it) { } }

    // 退路：拷到缓存目录再打开
    if (picked.size > 0 && cacheDir.usableSpace < picked.size + 64L * 1024 * 1024) {
        throw IOException("存储空间不足：读取这个安装包需要约 ${instFormatSize(picked.size)} 的临时空间")
    }
    val target = File(cacheDir, "source.zip")
    val input = context.contentResolver.openInputStream(picked.uri) ?: throw IOException("无法打开所选文件")
    input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o, COPY_BUFFER) } }
    val zip = try {
        ZipFile(target)
    } catch (e: ZipException) {
        target.delete()
        throw IOException("所选文件不是有效的安装包（不是压缩包或已损坏）")
    }
    return InstZip(zip) { target.delete() }
}

/** 一次选了若干个 .apk：把它们当作同一个应用的拆分包。 */
private fun instLoadLoose(context: Context, picked: List<InstPicked>, device: DeviceProfile, cacheDir: File): InstPackage {
    // 同名文件会让后面的覆盖前面（路径是 Map 的键），所以给重名的加上序号前缀
    val paths = picked.mapIndexed { i, p ->
        if (picked.count { it.name == p.name } > 1) "$i/${p.name}" else p.name
    }
    val uriByPath = HashMap<String, Uri>()
    paths.forEachIndexed { i, path -> uriByPath[path] = picked[i].uri }

    val entries = picked.mapIndexed { i, p -> SplitApkParser.classify(paths[i], p.size) }
    val plan = SplitApkParser.plan(entries, device)
    val openPath: (String) -> InputStream = { path ->
        val uri = uriByPath[path] ?: throw IOException("找不到 $path")
        context.contentResolver.openInputStream(uri) ?: throw IOException("无法打开 $path")
    }

    val base = plan.entries.firstOrNull { it.kind == SplitKind.BASE }
    var meta: InstArchiveInfo? = null
    if (base != null && (base.size <= 0 || base.size <= PROBE_LIMIT)) {
        meta = instProbeBase(context, cacheDir) { openPath(base.path) }
    }
    return InstPackage(
        format = if (picked.size == 1) "APK" else "APK × ${picked.size}",
        fileName = picked.first().name,
        title = meta?.label,
        packageName = meta?.packageName?.ifBlank { null },
        versionName = meta?.versionName,
        versionCode = meta?.versionCode,
        icon = meta?.icon,
        plan = plan,
        obb = emptyList(),
        opener = openPath,
        closer = { },
    )
}

/** 读一个压缩包（APKS / XAPK / APKM / ZIP）。 */
private fun instLoadArchive(context: Context, file: InstPicked, device: DeviceProfile, cacheDir: File): InstPackage {
    val opened = instOpenZip(context, file, cacheDir)
    val zip = opened.zip
    try {
        val all = zip.entries().toList().filter { !it.isDirectory }
        val byLowerName = HashMap<String, ZipEntry>()
        all.forEach { byLowerName.putIfAbsent(it.name.lowercase(Locale.ROOT), it) }
        fun find(name: String?): ZipEntry? =
            if (name == null) null else zip.getEntry(name) ?: byLowerName[name.lowercase(Locale.ROOT)]

        var pkgName: String? = null
        var title: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var iconName: String? = null
        var format = file.name.substringAfterLast('.', "").uppercase(Locale.ROOT).ifEmpty { "ZIP" }
        val refs = ArrayList<Triple<String, Long, String?>>() // 路径、大小、清单里的 id
        val obb = ArrayList<InstObb>()

        val manifest = find("manifest.json")
        val info = find("info.json")
        if (manifest != null) {
            // XAPK（APKPure）：manifest.json 里有包名、拆分列表、OBB 列表
            try {
                val json = JSONObject(zip.readSmallText(manifest))
                pkgName = json.optString("package_name").ifBlank { null }
                title = json.optString("name").ifBlank { null }
                versionName = json.optString("version_name").ifBlank { null }
                versionCode = json.optString("version_code").toLongOrNull()
                iconName = json.optString("icon").ifBlank { null }
                val splits = json.optJSONArray("split_apks")
                if (splits != null) {
                    for (i in 0 until splits.length()) {
                        val o = splits.optJSONObject(i) ?: continue
                        val e = find(o.optString("file")) ?: continue
                        refs += Triple(e.name, e.size, o.optString("id").ifBlank { null })
                    }
                }
                val expansions = json.optJSONArray("expansions")
                if (expansions != null) {
                    for (i in 0 until expansions.length()) {
                        val o = expansions.optJSONObject(i) ?: continue
                        val e = find(o.optString("file")) ?: continue
                        val target = instObbTarget(o.optString("install_path").ifBlank { e.name })
                            ?: if (e.name.endsWith(".obb", ignoreCase = true) && pkgName != null) {
                                "Android/obb/$pkgName/${e.name.substringAfterLast('/')}"
                            } else {
                                null
                            }
                        if (target != null) obb += InstObb(e.name, target, e.size)
                    }
                }
                format = "XAPK"
            } catch (e: Exception) {
                // 不是 XAPK 的 manifest.json：忽略，按普通压缩包处理
            }
        } else if (info != null) {
            // APKM（APKMirror）：info.json 里有包名、应用名、版本
            try {
                val json = JSONObject(zip.readSmallText(info))
                pkgName = json.optString("pname").ifBlank { null }
                title = json.optString("app_name").ifBlank { null }
                versionName = json.optString("release_version").ifBlank { null }
                versionCode = json.optString("versioncode").toLongOrNull()
                if (pkgName != null || title != null) format = "APKM"
            } catch (e: Exception) {
                // 同上
            }
        }

        if (refs.isEmpty()) {
            // APKS（bundletool）有 splits/ 目录；其它情况取全部 APK
            val candidates = all.filter { SplitApkParser.isInstallableApk(it.name) }
            val preferred = candidates
                .filter { it.name.lowercase(Locale.ROOT).startsWith("splits/") }
                .ifEmpty { candidates }
            preferred.forEach { refs += Triple(it.name, it.size, null) }
        }
        if (refs.isEmpty()) throw IOException("压缩包里没有找到 APK 文件")

        if (obb.isEmpty()) {
            all.forEach { e ->
                val low = e.name.lowercase(Locale.ROOT)
                if (low.startsWith("android/obb/") || low.startsWith("android/data/")) {
                    val target = instObbTarget(e.name)
                    if (target != null) obb += InstObb(e.name, target, e.size)
                }
            }
        }

        val entries = refs.map { (path, size, id) ->
            SplitApkParser.classify(path, if (size > 0) size else -1L, nameHint = id, baseHint = pkgName)
        }
        val plan = SplitApkParser.plan(entries, device)

        var icon: Bitmap? = null
        val iconEntry = find(iconName ?: "icon.png")
        if (iconEntry != null) {
            icon = try {
                zip.getInputStream(iconEntry).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }

        // 清单里没给名字 / 包名 / 版本时，才把主包拷出来用系统解析一下
        val baseEntry = plan.entries.firstOrNull { it.kind == SplitKind.BASE }
        if (baseEntry != null && (title == null || pkgName == null || versionName == null) &&
            (baseEntry.size <= 0 || baseEntry.size <= PROBE_LIMIT)
        ) {
            val meta = instProbeBase(context, cacheDir) { zip.getInputStream(zip.getEntry(baseEntry.path)) }
            if (meta != null) {
                pkgName = pkgName ?: meta.packageName.ifBlank { null }
                title = title ?: meta.label
                versionName = versionName ?: meta.versionName
                versionCode = versionCode ?: meta.versionCode
                icon = icon ?: meta.icon
            }
        }

        return InstPackage(
            format = format,
            fileName = file.name,
            title = title,
            packageName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            icon = icon,
            plan = plan,
            obb = obb,
            opener = { path -> zip.getInputStream(zip.getEntry(path) ?: throw IOException("压缩包里缺少 $path")) },
            closer = {
                try {
                    zip.close()
                } catch (e: IOException) {
                    // 关闭失败无所谓
                }
                opened.cleanup()
            },
        )
    } catch (e: Throwable) {
        try {
            zip.close()
        } catch (ignored: IOException) {
            // 同上
        }
        opened.cleanup()
        throw e
    }
}

private fun instLoadPackage(context: Context, picked: List<InstPicked>, device: DeviceProfile): InstPackage {
    val cacheDir = File(context.cacheDir, "installer")
    cacheDir.deleteRecursively() // 清掉上次遗留的临时文件
    cacheDir.mkdirs()
    val allApks = picked.all { it.name.endsWith(".apk", ignoreCase = true) }
    if (allApks) return instLoadLoose(context, picked, device, cacheDir)
    if (picked.size != 1) {
        throw IOException("请只选择一个压缩包（.apks / .xapk / .apkm / .zip），或者一次选择多个 .apk 文件")
    }
    return instLoadArchive(context, picked.first(), device, cacheDir)
}

/* ───────────────────────── 安装与 OBB ───────────────────────── */

/**
 * 把选中的 APK 写进一个 PackageInstaller 会话并提交。
 * 提交之后系统会通过 [action] 广播告诉我们结果（需要用户确认时也走这里）。
 */
private suspend fun instWriteAndCommit(
    context: Context,
    pkg: InstPackage,
    chosen: List<SplitEntry>,
    action: String,
    onProgress: (done: Long, total: Long) -> Unit,
    beforeCommit: () -> Unit,
) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val total = chosen.sumOf { it.size.coerceAtLeast(0L) }
    if (chosen.all { it.size > 0 }) params.setSize(total)

    val sessionId = installer.createSession(params)
    var committed = false
    try {
        installer.openSession(sessionId).use { session ->
            val buffer = ByteArray(COPY_BUFFER)
            var done = 0L
            var reported = 0L
            chosen.forEachIndexed { index, entry ->
                val name = if (entry.kind == SplitKind.BASE) "base.apk" else "split_$index.apk"
                pkg.open(entry).use { input ->
                    session.openWrite(name, 0, entry.size).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (done - reported >= PROGRESS_STEP) {
                                reported = done
                                onProgress(done, total)
                            }
                        }
                        session.fsync(out)
                    }
                }
            }
            onProgress(done, total)

            // 系统会把结果（含“需要用户确认”）填进这个 Intent，所以 PendingIntent 必须是可变的
            val intent = Intent(action).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            // 先切到“等待确认”再提交：提交后系统的回调随时可能到，不能被这里反过来覆盖
            beforeCommit()
            session.commit(pending.intentSender)
            committed = true
        }
    } finally {
        if (!committed) {
            try {
                installer.abandonSession(sessionId)
            } catch (e: Exception) {
                // 会话可能已被系统回收
            }
        }
    }
}

private suspend fun instCopyObb(
    pkg: InstPackage,
    items: List<InstObb>,
    onProgress: (done: Long, total: Long) -> Unit,
): InstObbResult {
    val root = Environment.getExternalStorageDirectory()
    val total = items.sumOf { it.size.coerceAtLeast(0L) }
    val buffer = ByteArray(COPY_BUFFER)
    var done = 0L
    var reported = 0L
    var copied = 0
    val failed = ArrayList<String>()
    for (item in items) {
        val target = File(root, item.target)
        try {
            target.parentFile?.mkdirs()
            pkg.open(item).use { input ->
                FileOutputStream(target).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - reported >= PROGRESS_STEP) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            copied++
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            failed += "${item.target.substringAfterLast('/')}：${e.message ?: e.javaClass.simpleName}"
        }
    }
    onProgress(done, total)
    return InstObbResult(copied, failed)
}

private fun instFailureTitle(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统拦截"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "与已安装的应用冲突"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "与本设备不兼容"
    PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
    else -> "安装失败"
}

private fun instFailureHint(status: Int): String? = when (status) {
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "可能被系统的安全防护或设备管理策略阻止，请留意系统弹窗里写的原因。"
    PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "多半是签名与已安装的版本不同，或版本低于已安装的版本。可以先卸载旧版本再装（会清除该应用的数据）。"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包要求的系统版本、CPU 架构等与本机不符。"
    PackageInstaller.STATUS_FAILURE_INVALID -> "文件可能已损坏，或者拆分包不全（缺少主包或必要的配置拆分）。"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "请清理出足够的空间后重试。"
    else -> null
}

@Suppress("DEPRECATION")
private fun Intent.installConfirmIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

private fun instKindLabel(e: SplitEntry): String = when (e.kind) {
    SplitKind.BASE -> "主包（必装）"
    SplitKind.FEATURE -> if (e.token.isEmpty()) "功能模块" else "附加资源 · ${e.token}"
    SplitKind.ABI -> "CPU 架构 · ${e.token.replace('_', '-')}"
    SplitKind.DENSITY -> "屏幕密度 · ${e.token}"
    SplitKind.LANGUAGE -> "语言 · ${e.token.replace('_', '-')}"
}

/* ───────────────────────── 安装完成后清理 ───────────────────────── */

private data class InstDeleteResult(
    val deleted: Int,
    val failed: List<String>,
) {
    val text: String?
        get() = when {
            deleted == 0 && failed.isEmpty() -> null
            failed.isEmpty() -> "已删除 $deleted 个安装包文件。"
            else -> buildString {
                if (deleted > 0) append("已删除 $deleted 个安装包文件，") else append("安装包删除失败：")
                append(failed.joinToString("、"))
                append("。安装已完成，可以手动删除这些文件。")
            }
        }
}

private fun instDeleteSources(context: Context, uris: List<Uri>): InstDeleteResult {
    var deleted = 0
    val failed = mutableListOf<String>()
    uris.distinct().forEach { uri ->
        if (instDeleteUri(context, uri)) {
            deleted++
        } else {
            failed += (uri.lastPathSegment ?: uri.toString()).take(120)
        }
    }
    return InstDeleteResult(deleted, failed)
}

private fun instDeleteUri(context: Context, uri: Uri): Boolean =
    when (uri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> uri.path?.let { File(it).delete() } == true
        "content" -> {
            val viaDocuments = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            if (viaDocuments) {
                true
            } else {
                runCatching {
                    context.contentResolver.delete(uri, null, null) > 0
                }.getOrDefault(false)
            }
        }
        else -> false
    }

/** 选安装包时同时请求写权限，供“安装完成后删除安装包”使用。 */
private class OpenInstallDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .setType("*/*")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null) {
            return (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri }
        }
        return listOfNotNull(intent.data)
    }
}

/* ───────────────────────── 界面 ───────────────────────── */

@Composable
fun SplitInstallerTool(padding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember { instDeviceProfile(context) }
    val resultAction = remember { "${context.packageName}.action.SPLIT_INSTALL_RESULT" }

    var stage by remember { mutableStateOf<InstStage>(InstStage.Idle) }
    var loaded by remember { mutableStateOf<InstPackage?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var withObb by remember { mutableStateOf(true) }
    var askAllowSource by remember { mutableStateOf(false) }
    var sourceUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var deleteAfterInstall by rememberSaveable { mutableStateOf(false) }

    // 回到前台时刷新“所有文件访问权限”的状态（用户可能刚从系统设置页回来）
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val allFilesGranted = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    // 读文件、写会话期间保持亮屏，免得大文件处理到一半息屏
    KeepScreenOn(
        stage is InstStage.Loading || stage is InstStage.Writing ||
            stage is InstStage.CopyingObb || stage is InstStage.Deleting,
    )

    // 离开页面：关掉压缩包、清掉缓存
    val loadedNow by rememberUpdatedState(loaded)
    DisposableEffect(Unit) {
        onDispose {
            loadedNow?.close()
            val dir = File(context.cacheDir, "installer")
            thread(name = "installer-cleanup") { dir.deleteRecursively() }
        }
    }

    fun finishInstalled(installedPackage: String?) {
        val pkg = loaded
        val items = if (withObb) pkg?.obb.orEmpty() else emptyList()
        val owner = installedPackage ?: pkg?.packageName
        val shouldDelete = deleteAfterInstall && sourceUris.isNotEmpty()
        val sources = sourceUris
        if (pkg == null || items.isEmpty()) {
            if (!shouldDelete) {
                stage = InstStage.Finished(true, "安装成功", null, owner, false)
                return
            }
            stage = InstStage.Deleting
            scope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    runCatching { pkg?.close() }
                    instDeleteSources(context, sources)
                }
                loaded = null
                stage = InstStage.Finished(true, "安装成功", deleted.text, owner, false)
            }
            return
        }
        stage = InstStage.CopyingObb(0, items.sumOf { it.size.coerceAtLeast(0L) })
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                instCopyObb(pkg, items) { d, t -> stage = InstStage.CopyingObb(d, t) }
            }
            val copyDetail = when {
                result.failed.isEmpty() -> "已复制 ${result.copied} 个 OBB 数据文件。"
                else -> buildString {
                    append("已复制 ${result.copied} 个，${result.failed.size} 个失败：\n")
                    append(result.failed.joinToString("\n"))
                    append("\n\n可以手动把它们放进 Android/obb/${owner ?: "<包名>"}/ 目录。")
                }
            }
            // OBB 复制失败时可能还需要压缩包里的数据，保留安装包不删。
            val deleted = if (shouldDelete && result.failed.isEmpty()) {
                stage = InstStage.Deleting
                withContext(Dispatchers.IO) {
                    runCatching { pkg.close() }
                    instDeleteSources(context, sources)
                }
            } else {
                null
            }
            if (deleted != null) loaded = null
            val detail = buildList {
                add(copyDetail)
                if (deleted != null) {
                    deleted.text?.let { add(it) }
                } else if (shouldDelete) {
                    add("OBB 复制未完全成功，已保留安装包。")
                }
            }.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
            stage = InstStage.Finished(true, "安装成功", detail, owner, false)
        }
    }

    // 系统的安装结果（含“需要用户确认”）通过广播回来
    DisposableEffect(context, resultAction) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.installConfirmIntent()
                        stage = InstStage.Confirming
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                ctx.startActivity(confirm)
                            } catch (e: Exception) {
                                stage = InstStage.Finished(
                                    false, "无法打开系统的安装确认页", e.message, null, true,
                                )
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        finishInstalled(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
                    else -> {
                        val hint = instFailureHint(status)
                        val system = message?.takeIf { it.isNotBlank() }?.let { "系统返回：$it" }
                        stage = InstStage.Finished(
                            ok = false,
                            title = instFailureTitle(status),
                            detail = listOfNotNull(hint, system).joinToString("\n\n").ifBlank { null },
                            launchPackage = null,
                            canRetry = status != PackageInstaller.STATUS_FAILURE_ABORTED || loaded != null,
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(resultAction), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // 已经注销
            }
        }
    }

    // 读入所选文件：文件选择器和「打开方式」送来的文件走同一条路
    fun loadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        sourceUris = uris.distinct()
        deleteAfterInstall = false
        loaded?.close()
        loaded = null
        stage = InstStage.Loading
        scope.launch {
            try {
                val pkg = withContext(Dispatchers.IO) {
                    instLoadPackage(context, uris.map { instDescribe(context, it) }, device)
                }
                loaded = pkg
                chosen = pkg.plan.selected
                withObb = true
                stage = InstStage.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stage = InstStage.Finished(
                    false, "无法读取安装包", e.message ?: e.javaClass.simpleName, null, false,
                )
            }
        }
    }

    val picker = rememberLauncherForActivityResult(OpenInstallDocuments()) { uris ->
        loadUris(uris)
    }

    // 别的应用用「打开方式」送来了安装包（.apks / .xapk / .apkm）：取走并读入。
    // 正在读取 / 写入 / 等系统确认 / 复制 OBB 时不能换掉当前的包，这种情况直接提示并放弃新文件。
    val incoming = IncomingPackage.uri
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        IncomingPackage.uri = null
        val busy = stage is InstStage.Loading || stage is InstStage.Writing ||
            stage is InstStage.Confirming || stage is InstStage.CopyingObb ||
            stage is InstStage.Deleting
        if (busy) {
            Toast.makeText(context, "正在处理另一个安装包，已忽略新打开的文件", Toast.LENGTH_LONG).show()
        } else {
            loadUris(listOf(uri))
        }
    }

    fun startInstall() {
        val pkg = loaded ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            askAllowSource = true
            return
        }
        val toInstall = pkg.plan.entries.filter { it.path in chosen }
        if (toInstall.isEmpty()) return
        stage = InstStage.Writing(0, toInstall.sumOf { it.size.coerceAtLeast(0L) })
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    instWriteAndCommit(
                        context = context,
                        pkg = pkg,
                        chosen = toInstall,
                        action = resultAction,
                        onProgress = { d, t -> stage = InstStage.Writing(d, t) },
                        beforeCommit = { stage = InstStage.Confirming },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stage = InstStage.Finished(
                    false, "写入安装会话失败", e.message ?: e.javaClass.simpleName, null, true,
                )
            }
        }
    }

    fun resetAll() {
        loaded?.close()
        loaded = null
        chosen = emptySet()
        sourceUris = emptyList()
        deleteAfterInstall = false
        stage = InstStage.Idle
    }

    ToolBody(padding) {
        when (val s = stage) {
            is InstStage.Idle -> InstIdleContent(onPick = { picker.launch(arrayOf("*/*")) })

            is InstStage.Loading -> InstBusyCard(
                title = "正在读取安装包…",
                note = "大文件需要几秒钟，请稍候。",
                done = 0,
                total = 0,
            )

            is InstStage.Ready -> {
                val pkg = loaded
                if (pkg == null) {
                    InstIdleContent(onPick = { picker.launch(arrayOf("*/*")) })
                } else {
                    InstReadyContent(
                        pkg = pkg,
                        chosen = chosen,
                        onToggle = { path, on -> chosen = if (on) chosen + path else chosen - path },
                        withObb = withObb,
                        onWithObb = { withObb = it },
                        deleteAfterInstall = deleteAfterInstall,
                        onDeleteAfterInstall = { deleteAfterInstall = it },
                        allFilesGranted = allFilesGranted,
                        onGrantAllFiles = {
                            val page = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                            try {
                                context.startActivity(page)
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } catch (e2: Exception) {
                                    // 这台设备没有对应的设置页
                                }
                            }
                        },
                        onInstall = { startInstall() },
                        onReselect = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }

            is InstStage.Writing -> InstBusyCard(
                title = "正在准备安装包…",
                note = "正在把所选 APK 写入系统的安装会话，请不要离开本页。",
                done = s.done,
                total = s.total,
            )

            is InstStage.Confirming -> InstConfirmingContent(onBack = { stage = InstStage.Ready })

            is InstStage.CopyingObb -> InstBusyCard(
                title = "正在复制 OBB 数据…",
                note = "应用已经装好，正在把附带的数据文件放到 Android/obb 下。",
                done = s.done,
                total = s.total,
            )

            is InstStage.Deleting -> InstBusyCard(
                title = "正在删除安装包…",
                note = "安装已完成，正在删除所选的全部安装包文件。",
                done = 0,
                total = 0,
            )

            is InstStage.Finished -> InstFinishedContent(
                result = s,
                onOpen = s.launchPackage?.let { pkgName ->
                    context.packageManager.getLaunchIntentForPackage(pkgName)?.let { launch ->
                        {
                            try {
                                context.startActivity(launch)
                            } catch (e: Exception) {
                                // 没有可启动的界面
                            }
                        }
                    }
                },
                onRetry = if (s.canRetry && loaded != null) ({ stage = InstStage.Ready }) else null,
                onAgain = { resetAll() },
            )
        }
    }

    AppDialog(
        show = askAllowSource,
        title = "需要允许安装未知应用",
        summary = "接下来会打开系统设置，请打开「允许来自此来源的应用」开关，然后返回本页，再点一次「开始安装」。",
        confirmText = "去设置",
        onConfirm = {
            askAllowSource = false
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (e2: Exception) {
                    // 这台设备没有对应的设置页
                }
            }
        },
        onDismiss = { askAllowSource = false },
    )
}

@Composable
private fun InstIdleContent(onPick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(68.dp).background(colors.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Apps,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text("选择安装包", style = MiuixTheme.textStyles.title3)
            Text(
                "支持 APKS · XAPK · APKM · ZIP，也可以一次选择多个 .apk 拆分包",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("选择文件") }
        }
    }
    HintText("也可以在文件管理器或浏览器的下载里，直接选择用本应用打开 .apks / .xapk / .apkm 文件")
    HintText("会按本机的 CPU 架构、屏幕密度和语言自动挑选需要的拆分包")
    HintText("首次使用需要在系统设置里允许本应用「安装未知应用」")
    HintText("请只安装来源可信的安装包")
}

@Composable
private fun InstBusyCard(title: String, note: String, done: Long, total: Long) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MiuixTheme.textStyles.headline2)
            val progress = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            if (total > 0) {
                Text(
                    "${instFormatSize(done)} / ${instFormatSize(total)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Text(note, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun InstConfirmingContent(onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("等待系统确认安装", style = MiuixTheme.textStyles.headline2)
            LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
            Text(
                "请在系统弹出的安装确认页里点「安装」。如果没有弹出，可能被其它窗口挡住了，请回到前台看看。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
    SecondaryButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
}

@Composable
private fun InstTag(text: String, color: Color) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun InstPackageHeader(pkg: InstPackage) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bitmap = pkg.icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    pkg.title ?: pkg.fileName,
                    style = MiuixTheme.textStyles.headline2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val packageName = pkg.packageName
                if (packageName != null) {
                    Text(
                        packageName,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val version = listOfNotNull(
                    pkg.versionName?.let { "v$it" },
                    pkg.versionCode?.let { "($it)" },
                ).joinToString(" ")
                if (version.isNotEmpty()) {
                    Text(
                        version,
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
            InstTag(pkg.format, colors.primary)
        }
    }
}

@Composable
private fun InstSplitRow(entry: SplitEntry, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val locked = entry.kind == SplitKind.BASE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked) { onToggle(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCheckbox(checked = checked, onCheckedChange = onToggle, enabled = !locked)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                entry.path.substringAfterLast('/'),
                style = MiuixTheme.textStyles.body2,
                color = if (checked) colors.onSurface else colors.onSurfaceVariantActions,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                instKindLabel(entry),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
        Text(
            instFormatSize(entry.size),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun InstReadyContent(
    pkg: InstPackage,
    chosen: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    withObb: Boolean,
    onWithObb: (Boolean) -> Unit,
    deleteAfterInstall: Boolean,
    onDeleteAfterInstall: (Boolean) -> Unit,
    allFilesGranted: Boolean,
    onGrantAllFiles: () -> Unit,
    onInstall: () -> Unit,
    onReselect: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val entries = pkg.plan.entries
    val selectedEntries = entries.filter { it.path in chosen }
    val selectedBytes = selectedEntries.sumOf { it.size.coerceAtLeast(0L) }
    val hasBase = selectedEntries.any { it.kind == SplitKind.BASE }

    InstPackageHeader(pkg)

    SectionLabel("将安装的 APK（已选 ${selectedEntries.size} / ${entries.size} 个，约 ${instFormatSize(selectedBytes)}）")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 54.dp)
                            .height(0.5.dp)
                            .background(colors.dividerLine),
                    )
                }
                InstSplitRow(
                    entry = entry,
                    checked = entry.path in chosen,
                    onToggle = { on -> onToggle(entry.path, on) },
                )
            }
        }
    }
    HintText("已按本机配置勾选推荐项，一般无需改动")

    pkg.plan.warnings.forEach { warning ->
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = WarnColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                warning,
                style = MiuixTheme.textStyles.footnote1,
                color = WarnColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (pkg.obb.isNotEmpty()) {
        SectionLabel("附带的数据文件（OBB）")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onWithObb(!withObb) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(checked = withObb, onCheckedChange = onWithObb)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("安装完成后复制 ${pkg.obb.size} 个数据文件", style = MiuixTheme.textStyles.body2)
                        Text(
                            "共 ${instFormatSize(pkg.obb.sumOf { it.size.coerceAtLeast(0L) })}，" +
                                "目标：${pkg.obb.first().target.substringBeforeLast('/')}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
                if (withObb && !allFilesGranted) {
                    Text(
                        "Android 11 及以上写入 Android/obb 需要「所有文件访问权限」，请先授权，否则复制会失败。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = WarnColor,
                    )
                    SecondaryButton(onClick = onGrantAllFiles, modifier = Modifier.fillMaxWidth()) {
                        Text("去授权")
                    }
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onDeleteAfterInstall(!deleteAfterInstall) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = deleteAfterInstall,
                    onCheckedChange = onDeleteAfterInstall,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("完成后删除安装包", style = MiuixTheme.textStyles.body2)
                    Text(
                        "安装成功并完成 OBB 复制后，删除所选的全部安装包文件；默认关闭",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
            if (deleteAfterInstall) {
                Text(
                    "删除后无法恢复；如果 OBB 复制失败会保留安装包。部分文件提供方可能不允许删除，届时会在结果里提示。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = WarnColor,
                )
            }
        }
    }

    PrimaryButton(onClick = onInstall, enabled = hasBase, modifier = Modifier.fillMaxWidth()) {
        Text("开始安装")
    }
    SecondaryButton(onClick = onReselect, modifier = Modifier.fillMaxWidth()) { Text("重新选择") }
}

@Composable
private fun InstFinishedContent(
    result: InstStage.Finished,
    onOpen: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onAgain: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val accent = if (result.ok) colors.primary else WarnColor
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (result.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(52.dp),
            )
            Text(result.title, style = MiuixTheme.textStyles.title3)
            val detail = result.detail
            if (detail != null) {
                Text(
                    detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (onOpen != null) {
        PrimaryButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("打开应用") }
    }
    if (onRetry != null) {
        PrimaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("返回重试") }
    }
    SecondaryButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
        Text(if (result.ok) "继续安装其它" else "重新选择")
    }
}
