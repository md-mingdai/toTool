package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.caverock.androidsvg.SVG
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** AndroidSVG 的轻量封装：读取固有尺寸，或按用户指定的像素尺寸渲染成位图。 */
internal object SvgCodec {
    fun probe(context: Context, uri: Uri): ImageProbeInfo? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val svg = SVG.getFromInputStream(input)
                val width = ceil(svg.documentWidth.toDouble()).toInt()
                val height = ceil(svg.documentHeight.toDouble()).toInt()
                if (width > 0 && height > 0) ImageProbeInfo(width, height) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun render(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        canceled: AtomicBoolean,
    ): Bitmap? {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) return null
        if (canceled.get()) throw ConvertCanceledException()
        val svg = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                SVG.getFromInputStream(input)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        if (canceled.get()) throw ConvertCanceledException()
        val picture = try {
            svg.renderToPicture(width, height)
        } catch (e: Exception) {
            return null
        }
        if (canceled.get()) throw ConvertCanceledException()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawPicture(picture)
            return bitmap
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }
}
