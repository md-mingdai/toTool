package com.mingdai.toTool.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mingdai.toTool.R
import com.mingdai.toTool.data.Prefs
import com.mingdai.toTool.data.SHORTCUT_RADIUS_BASE_PX
import com.mingdai.toTool.data.SHORTCUT_RADIUS_MAX
import com.mingdai.toTool.data.Tool
import com.mingdai.toTool.data.ToolCategory

/* 桌面快捷方式的公共部分：生成图标、发出“固定到桌面”请求、给出提示。
 * 工具自己的快捷方式（Common.kt 的 pinToolShortcut）和「自定义快捷方式」工具都用这里。 */

/** 快捷方式图标的边长（像素）：取桌面允许的最大尺寸，限制在 96..512 之间。 */
fun shortcutIconSizePx(context: Context): Int {
    val w = ShortcutManagerCompat.getIconMaxWidth(context)
    val h = ShortcutManagerCompat.getIconMaxHeight(context)
    return minOf(w, h).coerceIn(96, 512)
}

/** 各分类的快捷方式底色：桌面上不同类别的工具一眼能分开。 */
private fun shortcutColorOf(category: ToolCategory): Int = when (category) {
    ToolCategory.DAILY -> 0xFFFF9500.toInt()
    ToolCategory.TEXT -> 0xFF3482FF.toInt()
    ToolCategory.CALC -> 0xFF34C759.toInt()
    ToolCategory.DEVICE -> 0xFF7C5CFC.toInt()
    ToolCategory.IMAGE -> 0xFFFF2D55.toInt()
    ToolCategory.MEDIA -> 0xFF00A6A6.toInt()
    ToolCategory.ADVANCED -> 0xFF607D8B.toInt()
}

/**
 * 把 Compose 的矢量图标画成方形位图：纯色底 + 白色图标。
 * 底色是铺满的方形，不自带圆角——桌面会按自己的图标形状去裁。
 * 直接按 ImageVector 里的路径数据绘制，不依赖 Composition，可以在任何地方调用。
 */
fun renderVectorShortcutIcon(icon: ImageVector, background: Int, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL

    val inset = sizePx * 0.24f
    val scale = (sizePx - 2 * inset) / maxOf(icon.viewportWidth, icon.viewportHeight)
    val matrix = Matrix()
    matrix.setScale(scale, scale)
    matrix.postTranslate(inset, inset)

    fun drawNode(node: VectorNode) {
        when (node) {
            is VectorGroup -> node.forEach { drawNode(it) }
            is VectorPath -> {
                val path = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                    android.graphics.Path.FillType.EVEN_ODD
                } else {
                    android.graphics.Path.FillType.WINDING
                }
                path.transform(matrix)
                canvas.drawPath(path, paint)
            }
            else -> Unit
        }
    }
    drawNode(icon.root)
    return bitmap
}

/**
 * 把方形位图裁成圆角矩形（四角透明）。
 * [radius] 的单位是 px，按 [SHORTCUT_RADIUS_BASE_PX] 边长的图标换算：位图更大时半径等比放大，
 * 所以同一个数值在任何设备上看起来都一样。0 表示直角，原样返回；[SHORTCUT_RADIUS_MAX] 是正圆。
 */
fun roundShortcutCorners(source: Bitmap, radius: Int): Bitmap {
    val r = radius.coerceIn(0, SHORTCUT_RADIUS_MAX)
    if (r == 0) return source
    val width = source.width
    val height = source.height
    val radiusPx = r.toFloat() / SHORTCUT_RADIUS_BASE_PX * minOf(width, height)
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    Canvas(result).drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radiusPx, radiusPx, paint)
    return result
}

/** 工具自己的快捷方式图标位图：分类底色 + 工具图标，再按 [radius] 裁圆角。设置页的预览也用它。 */
fun toolShortcutBitmap(tool: Tool, sizePx: Int, radius: Int): Bitmap =
    roundShortcutCorners(renderVectorShortcutIcon(tool.icon, shortcutColorOf(tool.category), sizePx), radius)

/** 工具自己的快捷方式图标：圆角取自设置页里的默认值。 */
fun toolShortcutIcon(context: Context, tool: Tool): IconCompat =
    IconCompat.createWithBitmap(
        toolShortcutBitmap(tool, shortcutIconSizePx(context), Prefs(context).shortcutCornerRadius()),
    )

/**
 * 请求桌面固定一个快捷方式，并给出提示。
 *
 * requestPinShortcut 返回 true 只代表“请求已经发给桌面”，用户有没有确认、桌面有没有弹窗，应用这边拿不到准确结果；
 * 小米 / HyperOS 等系统没放行「桌面快捷方式」权限时会静默失败，所以提示里把这点说清楚。
 */
fun requestPinWithFeedback(context: Context, shortcut: ShortcutInfoCompat) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, context.getString(R.string.action_pin_unsupported), Toast.LENGTH_LONG).show()
        return
    }
    val sent = try {
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    } catch (e: Exception) {
        false
    }
    val message = if (sent) R.string.action_pin_requested else R.string.action_pin_failed
    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
}

/** 打开本应用的系统设置页（在那里给「桌面快捷方式」之类的权限放行）。 */
fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开应用设置", Toast.LENGTH_SHORT).show()
    }
}
