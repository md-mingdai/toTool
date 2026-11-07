package com.mingdai.toTool.ui.screens.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.mingdai.toTool.data.LocalBehaviorState
import com.mingdai.toTool.data.SHORTCUT_RADIUS_BASE_PX
import com.mingdai.toTool.data.SHORTCUT_RADIUS_MAX
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.openAppDetailsSettings
import com.mingdai.toTool.ui.components.requestPinWithFeedback
import com.mingdai.toTool.ui.components.roundShortcutCorners
import com.mingdai.toTool.ui.components.shortcutIconSizePx
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/* ------------------------------ 自定义快捷方式 ------------------------------ */

private data class PickApp(val packageName: String, val label: String)

private data class PickActivity(
    val className: String,
    val label: String,
    val exported: Boolean,
    val enabled: Boolean,
    val needsPermission: Boolean,
    /** 是桌面入口（声明了 MAIN / LAUNCHER 的 Activity），也就是点应用图标时打开的那个。 */
    val isLauncher: Boolean,
) {
    /** 别的应用能直接拉起它：已导出、没被禁用、也不需要我们没有的权限。 */
    val launchable: Boolean get() = exported && enabled && !needsPermission
    val shortName: String get() = className.substringAfterLast('.')

    /**
     * 列表排序的优先级，越小越靠前：名为 MainActivity 的排最前，其次是桌面入口，其余的再按「能否启动」和类名排。
     * 两条都满足的最靠前。
     */
    val priority: Int
        get() {
            val isMain = shortName.equals("MainActivity", ignoreCase = true)
            return when {
                isMain && isLauncher -> 0
                isMain -> 1
                isLauncher -> 2
                else -> 3
            }
        }
}

private data class ShortcutTarget(
    val packageName: String,
    val appLabel: String,
    val activity: PickActivity,
)

private enum class ShortcutStep { FORM, PICK_APP, PICK_ACTIVITY }

private enum class IconSource { TARGET, IMAGE, TEXT }

/** 文字图标可选的底色。 */
private val TextIconColors = listOf(
    0xFF3482FF, 0xFF34C759, 0xFFFF9500, 0xFFFF3B30,
    0xFFAF52DE, 0xFF00B5AD, 0xFFFF2D55, 0xFF4B5563,
)

/* ---------- 数据加载 ---------- */

@Suppress("DEPRECATION")
private fun loadInstalledApps(pm: PackageManager): List<PickApp> =
    pm.getInstalledApplications(0)
        .map { PickApp(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }

/** 读出一个应用声明的全部 Activity：名为 MainActivity 的和桌面入口排最前，其余的可直接启动的在前。 */
@Suppress("DEPRECATION")
private fun loadActivities(context: Context, packageName: String): List<PickActivity> {
    val pm = context.packageManager
    val info = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            )
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        }
    } catch (e: Exception) {
        return emptyList()
    }
    // 声明了 MAIN / LAUNCHER 的 Activity，就是点应用图标时打开的主入口
    val launcherNames: Set<String> = runCatching {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(query, 0)
        }
        resolved.map { it.activityInfo.name }.toSet()
    }.getOrDefault(emptySet())
    return info.activities.orEmpty().map { a ->
        val permission = a.permission
        PickActivity(
            className = a.name,
            label = runCatching { a.loadLabel(pm).toString() }.getOrDefault(a.name),
            exported = a.exported,
            enabled = a.enabled,
            needsPermission = permission != null &&
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED,
            isLauncher = a.name in launcherNames,
        )
    }.sortedWith(
        compareBy<PickActivity> { it.priority }
            .thenByDescending { it.launchable }
            .thenBy { it.className },
    )
}

/** 列表里用的小图标缓存：滚动时不用反复取图、缩放。 */
private val listIconCache = LruCache<String, Bitmap>(160)

private fun listIconOf(pm: PackageManager, packageName: String): Bitmap? {
    listIconCache.get(packageName)?.let { return it }
    val bitmap = runCatching {
        drawableToSquare(pm.getApplicationIcon(packageName), 96, fullBleed = false)
    }.getOrNull() ?: return null
    listIconCache.put(packageName, bitmap)
    return bitmap
}

/* ---------- 图标生成 ---------- */

/**
 * 把 Drawable 画成 size × size 的位图。
 * [fullBleed] = true（做快捷方式图标）：自适应图标不带系统遮罩，直接把背景层和前景层放大到铺满，
 * 桌面会按自己的形状去裁；false（列表缩略图）：按原样带遮罩画。
 */
private fun drawableToSquare(drawable: Drawable, size: Int, fullBleed: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (fullBleed && drawable is AdaptiveIconDrawable) {
        // 自适应图标的图层是 108dp，只有中间 72dp 是可见区域：图层放大 1.5 倍并居中，可见区域刚好铺满
        val pad = (size * 0.25f).toInt()
        val bg = drawable.background
        if (bg != null) {
            bg.setBounds(-pad, -pad, size + pad, size + pad)
            bg.draw(canvas)
        }
        val fg = drawable.foreground
        if (fg != null) {
            fg.setBounds(-pad, -pad, size + pad, size + pad)
            fg.draw(canvas)
        }
    } else {
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
    }
    return bitmap
}

private fun targetIconBitmap(context: Context, target: ShortcutTarget, size: Int): Bitmap? {
    val pm = context.packageManager
    // 优先用 Activity 自己声明的图标；取不到就退回应用图标
    val drawable = runCatching {
        pm.getActivityIcon(ComponentName(target.packageName, target.activity.className))
    }.getOrNull() ?: runCatching { pm.getApplicationIcon(target.packageName) }.getOrNull() ?: return null
    return runCatching { drawableToSquare(drawable, size, fullBleed = true) }.getOrNull()
}

/** 读相册图片，居中裁成正方形，缩放到 size × size。自动处理 EXIF 方向。 */
private fun loadSquareImage(context: Context, uri: Uri, size: Int): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        val w = info.size.width
        val h = info.size.height
        val shortSide = minOf(w, h)
        if (shortSide > size * 2) { // 先缩到短边约 2×size，足够清晰又不占内存
            val ratio = (size * 2f) / shortSide
            decoder.setTargetSize(maxOf(1, (w * ratio).toInt()), maxOf(1, (h * ratio).toInt()))
        }
    }
    val side = minOf(decoded.width, decoded.height)
    val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    Bitmap.createScaledBitmap(cropped, size, size, true)
}.getOrNull()

/** 文字图标：纯色底 + 白色粗体文字（1～2 个字）。 */
private fun renderTextIcon(text: String, background: Int, size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textSize = size * (if (text.codePointCount(0, text.length) > 1) 0.40f else 0.54f)
    val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, size / 2f, baseline, paint)
    return bitmap
}

/** 取前 [count] 个字符（按 Unicode 码点算，不会把 emoji 从中间劈开）。 */
private fun firstCodePoints(text: String, count: Int): String {
    if (text.codePointCount(0, text.length) <= count) return text
    return text.substring(0, text.offsetByCodePoints(0, count))
}

/* ---------- 搜索 / 启动 ---------- */

/** 按空格拆成多个关键词，每个词都要命中某个字段（不区分大小写）。 */
private fun <T> filterByQuery(items: List<T>, query: String, fields: (T) -> List<String>): List<T> {
    val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return items
    return items.filter { item ->
        val haystack = fields(item).map { it.lowercase(Locale.ROOT) }
        tokens.all { token -> haystack.any { it.contains(token) } }
    }
}

private fun targetIntent(target: ShortcutTarget): Intent =
    Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(target.packageName, target.activity.className))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun testLaunch(context: Context, target: ShortcutTarget?) {
    if (target == null) return
    try {
        context.startActivity(targetIntent(target))
    } catch (e: Exception) {
        Toast.makeText(context, "启动失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }
}

/* ---------- 界面 ---------- */

/**
 * 自定义快捷方式：选一个应用的任意 Activity，配上自己的名称和图标，固定到桌面。
 * 点快捷方式时由桌面直接拉起那个 Activity，不经过本应用。
 */
@Composable
fun CustomShortcutTool(padding: PaddingValues) {
    val context = LocalContext.current
    val pm = context.packageManager
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(ShortcutStep.FORM) }
    var apps by remember { mutableStateOf<List<PickApp>?>(null) }
    var pickedApp by remember { mutableStateOf<PickApp?>(null) }
    var activities by remember { mutableStateOf<List<PickActivity>?>(null) }
    var target by remember { mutableStateOf<ShortcutTarget?>(null) }
    var label by remember { mutableStateOf("") }
    var labelEdited by remember { mutableStateOf(false) }
    var iconSource by remember { mutableStateOf(IconSource.TARGET) }
    var pickedImage by remember { mutableStateOf<Bitmap?>(null) }
    var iconText by remember { mutableStateOf("") }
    var iconColor by remember { mutableStateOf(TextIconColors[0]) }
    // 圆角：初始值取设置页里的默认值，之后只影响这一个快捷方式，不会改动全局默认
    val behavior = LocalBehaviorState.current
    var cornerRadius by remember { mutableIntStateOf(behavior.shortcutCornerRadius) }

    // 选择页里按返回：Activity 列表 → 应用列表 → 表单，而不是直接退出整个工具
    BackHandler(enabled = step != ShortcutStep.FORM) {
        step = if (step == ShortcutStep.PICK_ACTIVITY) ShortcutStep.PICK_APP else ShortcutStep.FORM
    }

    // 第一次打开应用选择页时才去读已安装应用列表
    LaunchedEffect(step) {
        if (step == ShortcutStep.PICK_APP && apps == null) {
            apps = withContext(Dispatchers.Default) { loadInstalledApps(pm) }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { loadSquareImage(context, uri, 512) }
                if (bitmap != null) {
                    pickedImage = bitmap
                    iconSource = IconSource.IMAGE
                } else {
                    Toast.makeText(context, "无法读取这张图片", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val iconSize = remember { shortcutIconSizePx(context) }

    // 文字图标留空时取名称的第一个字；预览只依赖这一个字，名称其余部分怎么改都不用重画图标
    val fallbackChar = firstCodePoints(label.trim().ifEmpty { "?" }, 1)

    /** 按当前选择生成图标位图。 */
    fun buildIcon(size: Int): Bitmap? = when (iconSource) {
        IconSource.TARGET -> target?.let { targetIconBitmap(context, it, size) }
        IconSource.IMAGE -> pickedImage?.let { Bitmap.createScaledBitmap(it, size, size, true) }
        IconSource.TEXT -> renderTextIcon(iconText.ifBlank { fallbackChar }, iconColor.toInt(), size)
    }

    // 底图和圆角分开算：拖圆角滑块时只重新裁一遍圆角，不用重新取图、绘制
    val baseIcon = remember(iconSource, target, pickedImage, iconText, iconColor, fallbackChar) { buildIcon(192) }
    val preview = remember(baseIcon, cornerRadius) { baseIcon?.let { roundShortcutCorners(it, cornerRadius) } }

    fun pin() {
        val t = target ?: return
        val bitmap = buildIcon(iconSize)?.let { roundShortcutCorners(it, cornerRadius) }
        if (bitmap == null) {
            Toast.makeText(context, "图标生成失败", Toast.LENGTH_SHORT).show()
            return
        }
        val name = label.trim()
        val info = ShortcutInfoCompat.Builder(context, "custom_${System.currentTimeMillis()}")
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithBitmap(bitmap))
            .setIntent(targetIntent(t))
            .build()
        requestPinWithFeedback(context, info)
    }

    when (step) {
        ShortcutStep.PICK_APP -> AppPickerPage(
            padding = padding,
            apps = apps,
            pm = pm,
            onBack = { step = ShortcutStep.FORM },
            onPick = { app ->
                pickedApp = app
                activities = null
                step = ShortcutStep.PICK_ACTIVITY
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) { loadActivities(context, app.packageName) }
                    // 加载期间用户可能已经退回去选了别的应用
                    if (pickedApp?.packageName == app.packageName) activities = loaded
                }
            },
        )

        ShortcutStep.PICK_ACTIVITY -> ActivityPickerPage(
            padding = padding,
            app = pickedApp,
            pm = pm,
            activities = activities,
            onBack = { step = ShortcutStep.PICK_APP },
            onPick = { activity ->
                val app = pickedApp
                if (app != null) {
                    target = ShortcutTarget(app.packageName, app.label, activity)
                    // 名称没被手动改过就跟着目标走
                    if (!labelEdited || label.isBlank()) label = activity.label.ifBlank { app.label }
                }
                step = ShortcutStep.FORM
            },
        )

        ShortcutStep.FORM -> ToolBody(padding) {
            val current = target

            SectionLabel("目标 Activity")
            Card(
                modifier = Modifier.fillMaxWidth(),
                pressFeedbackType = PressFeedbackType.Sink,
                showIndication = true,
                onClick = { step = ShortcutStep.PICK_APP },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current != null) {
                        AppIconImage(pm, current.packageName, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = current.appLabel,
                                style = MiuixTheme.textStyles.body2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = current.activity.className,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "更换",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "点击选择应用与 Activity",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "选择",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (current != null && !current.activity.launchable) {
                val reason = when {
                    !current.activity.enabled -> "这个 Activity 已被禁用，系统不会启动它。"
                    !current.activity.exported -> "这个 Activity 没有对外导出，系统会拒绝从别的应用启动它，快捷方式很可能点了没反应。"
                    else -> "这个 Activity 需要本应用没有的权限，快捷方式很可能点了没反应。"
                }
                Text(
                    text = reason,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            SectionLabel("名称")
            TextField(
                value = label,
                onValueChange = {
                    label = it
                    labelEdited = true
                },
                label = "快捷方式名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("图标")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = preview
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = "图标预览",
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = when (iconSource) {
                        IconSource.TARGET -> "使用目标 Activity 自己的图标（没有就用应用图标）"
                        IconSource.IMAGE -> "使用相册里的图片，会居中裁成正方形"
                        IconSource.TEXT -> "用文字和底色生成图标"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip(
                    selected = iconSource == IconSource.TARGET,
                    onClick = { iconSource = IconSource.TARGET },
                    label = "目标图标",
                )
                SelectChip(
                    selected = iconSource == IconSource.IMAGE,
                    onClick = {
                        if (pickedImage == null) imagePicker.launch("image/*") else iconSource = IconSource.IMAGE
                    },
                    label = "相册图片",
                )
                SelectChip(
                    selected = iconSource == IconSource.TEXT,
                    onClick = { iconSource = IconSource.TEXT },
                    label = "文字图标",
                )
            }
            when (iconSource) {
                IconSource.IMAGE -> SecondaryButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (pickedImage == null) "选择图片…" else "重新选择图片…")
                }

                IconSource.TEXT -> {
                    TextField(
                        value = iconText,
                        onValueChange = { iconText = firstCodePoints(it, 2) },
                        label = "图标文字（1～2 个字，留空取名称的第一个字）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextIconColors.forEach { swatch ->
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .then(
                                        if (swatch == iconColor) {
                                            Modifier.border(3.dp, MiuixTheme.colorScheme.onSurface, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { iconColor = swatch },
                            )
                        }
                    }
                }

                IconSource.TARGET -> Unit
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "圆角",
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${cornerRadius}px",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Slider(
                value = cornerRadius.toFloat(),
                onValueChange = { cornerRadius = it.roundToInt() },
                valueRange = 0f..SHORTCUT_RADIUS_MAX.toFloat(),
            )
            Text(
                text = "半径按 ${SHORTCUT_RADIUS_BASE_PX}px 边长的图标换算，实际图标等比放大；" +
                    "0 为直角，${SHORTCUT_RADIUS_MAX} 为正圆。初始值取自设置页里的默认圆角。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    onClick = { testLaunch(context, current) },
                    enabled = current != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("测试启动")
                }
                PrimaryButton(
                    onClick = { pin() },
                    enabled = current != null && label.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("添加到主屏幕")
                }
            }

            Text(
                text = "快捷方式由桌面直接拉起所选 Activity，不经过本应用。对方的 Activity 必须「已导出」且不需要特殊权限才能被拉起，" +
                    "选择列表里已标出；不确定时先点「测试启动」试一下。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "小米 / HyperOS 等系统需要先在「应用设置 → 权限」里允许本应用的「桌面快捷方式」，否则点了添加没有任何反应。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SecondaryButton(
                onClick = { openAppDetailsSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开本应用的系统设置")
            }
        }
    }
}

/** 选择页的外壳：返回键 + 标题 + 搜索框 + 内容。 */
@Composable
private fun PickerScaffold(
    padding: PaddingValues,
    title: String,
    searchLabel: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                style = MiuixTheme.textStyles.headline2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        TextField(
            value = query,
            onValueChange = onQueryChange,
            label = searchLabel,
            useLabelAsPlaceholder = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp).size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            singleLine = true,
            cornerRadius = 24.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
private fun AppPickerPage(
    padding: PaddingValues,
    apps: List<PickApp>?,
    pm: PackageManager,
    onBack: () -> Unit,
    onPick: (PickApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        filterByQuery(apps.orEmpty(), query) { listOf(it.label, it.packageName) }
    }
    PickerScaffold(
        padding = padding,
        title = "选择应用",
        searchLabel = "搜索应用名或包名",
        query = query,
        onQueryChange = { query = it },
        onBack = onBack,
    ) {
        when {
            apps == null -> PickerLoading()
            shown.isEmpty() -> PickerEmpty("没有找到匹配的应用")
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    PickRow(
                        title = app.label,
                        subtitle = app.packageName,
                        tag = null,
                        warning = false,
                        onClick = { onPick(app) },
                        leading = { AppIconImage(pm, app.packageName, 36.dp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityPickerPage(
    padding: PaddingValues,
    app: PickApp?,
    pm: PackageManager,
    activities: List<PickActivity>?,
    onBack: () -> Unit,
    onPick: (PickActivity) -> Unit,
) {
    var query by remember(app?.packageName) { mutableStateOf("") }
    val shown = remember(activities, query) {
        filterByQuery(activities.orEmpty(), query) { listOf(it.className, it.label) }
    }
    PickerScaffold(
        padding = padding,
        title = app?.label ?: "选择 Activity",
        searchLabel = "搜索类名或名称",
        query = query,
        onQueryChange = { query = it },
        onBack = onBack,
    ) {
        when {
            activities == null -> PickerLoading()
            shown.isEmpty() -> PickerEmpty(
                if (activities.isEmpty()) "这个应用没有声明任何 Activity" else "没有匹配的 Activity",
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.className }) { activity ->
                    // Activity 没单独起名时，系统会回退成应用名，这时显示类名更有区分度
                    val title = if (activity.label.isNotBlank() &&
                        activity.label != activity.className &&
                        activity.label != app?.label
                    ) activity.label else activity.shortName
                    val tag = when {
                        !activity.enabled -> "已禁用"
                        !activity.exported -> "未导出"
                        activity.needsPermission -> "需权限"
                        activity.isLauncher -> "主入口"
                        else -> "可启动"
                    }
                    PickRow(
                        title = title,
                        subtitle = activity.className,
                        tag = tag,
                        warning = !activity.launchable,
                        onClick = { onPick(activity) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    tag: String?,
    warning: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tag != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tag,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (warning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 应用图标：滚动到才加载，加载过的走缓存。 */
@Composable
private fun AppIconImage(pm: PackageManager, packageName: String, size: Dp) {
    val bitmap by produceState<Bitmap?>(initialValue = listIconCache.get(packageName), packageName) {
        if (value == null) value = withContext(Dispatchers.IO) { listIconOf(pm, packageName) }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size))
    } else {
        Spacer(Modifier.size(size))
    }
}

@Composable
private fun PickerLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PickerEmpty(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    )
}
