package com.mingdai.toTool.ui.screens.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.util.Locale

/** Pure bitmap utilities shared by the image tool group. All functions allocate
 *  a new bitmap and leave the source untouched. */
object ImageOps {

    /**
     * Decode a content uri (photo picker, saved gallery entry) into a mutable-friendly software bitmap.
     *
     * - 使用 [ImageDecoder]：自动应用 EXIF 旋转（BitmapFactory 不会，手机竖拍的照片会被转成横的）。
     * - 最长边超过 [maxSide] 时按比例缩小，避免 1200 万像素原图在模糊/像素化时 OOM 闪退。
     */
    fun uriToBitmap(context: Context, uri: Uri, maxSide: Int = 4096): Bitmap? {
        // 同时受“最长边”和“内存预算”两道限制。最重的处理（高斯模糊）每像素峰值约 12 字节，
        // 所以按 12 字节/像素估算；内存紧张的机器上会自动取更小的值，而不是等 OOM。
        val maxPixels = minOf(MemoryBudget.maxPixels(context, 12.0), maxSide.toLong() * maxSide)
        val viaDecoder = runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                var f = 1.0
                val longest = maxOf(w, h)
                if (longest > maxSide) f = maxSide.toDouble() / longest
                val px = w.toDouble() * h * f * f
                if (px > maxPixels) f *= Math.sqrt(maxPixels / px)
                if (f < 1.0) {
                    decoder.setTargetSize(
                        (w * f).toInt().coerceAtLeast(1),
                        (h * f).toInt().coerceAtLeast(1),
                    )
                }
            }
        }.getOrNull()
        if (viaDecoder != null) return viaDecoder

        // 兜底：个别格式 ImageDecoder 不支持时退回 BitmapFactory（带降采样）
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (
                maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide ||
                (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > maxPixels
            ) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /** Rasterize any drawable (e.g. the current wallpaper) to a bitmap. */
    fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap? = runCatching {
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        bmp
    }.getOrNull()

    fun copy(src: Bitmap): Bitmap = src.copy(Bitmap.Config.ARGB_8888, true)

    /** Recreate the bitmap at [scale] of its current size, keeping aspect ratio. */
    fun resize(src: Bitmap, scale: Float): Bitmap {
        val s = scale.coerceIn(0.05f, 1f)
        val nw = (src.width * s).toInt().coerceAtLeast(1)
        val nh = (src.height * s).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, nw, nh),
            Paint(Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** Luminance-based black & white conversion. */
    fun grayscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px shr 24) and 0xff
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (y shl 16) or (y shl 8) or y
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Nearest-neighbour down-then-up-scale: blocks of [blockSize] px collapse. */
    fun pixelate(src: Bitmap, blockSize: Int): Bitmap {
        val bs = blockSize.coerceAtLeast(1)
        val sw = (src.width / bs).coerceAtLeast(1)
        val sh = (src.height / bs).coerceAtLeast(1)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, sw, sh), null)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            small,
            Rect(0, 0, sw, sh),
            Rect(0, 0, src.width, src.height),
            Paint().apply { isFilterBitmap = false },
        )
        return out
    }

    /** Four-pass separable box blur. Cheap enough for photos at UI size. */
    fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceAtLeast(1)
        val w = src.width
        val h = src.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        val c = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        boxH(a, b, w, h, r)
        boxV(b, c, w, h, r)
        boxH(c, a, w, h, r)
        boxV(a, b, w, h, r)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(b, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = (2 * r + 1).toFloat()
        for (y in 0 until h) {
            val row = y * w
            var sr = 0f; var sg = 0f; var sb = 0f
            for (i in -r..r) {
                val px = src[row + i.coerceIn(0, w - 1)]
                sr += ((px shr 16) and 0xff).toFloat()
                sg += ((px shr 8) and 0xff).toFloat()
                sb += (px and 0xff).toFloat()
            }
            for (x in 0 until w) {
                dst[row + x] = (0xff shl 24) or
                    ((sr / win).toInt().coerceIn(0, 255) shl 16) or
                    ((sg / win).toInt().coerceIn(0, 255) shl 8) or
                    (sb / win).toInt().coerceIn(0, 255)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val drop = src[row + (x - r).coerceAtLeast(0)]
                sr += ((add shr 16) and 0xff).toFloat() - ((drop shr 16) and 0xff).toFloat()
                sg += ((add shr 8) and 0xff).toFloat() - ((drop shr 8) and 0xff).toFloat()
                sb += (add and 0xff).toFloat() - (drop and 0xff).toFloat()
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = (2 * r + 1).toFloat()
        for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (i in -r..r) {
                val px = src[i.coerceIn(0, h - 1) * w + x]
                sr += ((px shr 16) and 0xff).toFloat()
                sg += ((px shr 8) and 0xff).toFloat()
                sb += (px and 0xff).toFloat()
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xff shl 24) or
                    ((sr / win).toInt().coerceIn(0, 255) shl 16) or
                    ((sg / win).toInt().coerceIn(0, 255) shl 8) or
                    (sb / win).toInt().coerceIn(0, 255)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val drop = src[(y - r).coerceAtLeast(0) * w + x]
                sr += ((add shr 16) and 0xff).toFloat() - ((drop shr 16) and 0xff).toFloat()
                sg += ((add shr 8) and 0xff).toFloat() - ((drop shr 8) and 0xff).toFloat()
                sb += (add and 0xff).toFloat() - (drop and 0xff).toFloat()
            }
        }
    }

    /**
     * 把 [text] 平铺满整张图。
     *
     * [dx] / [dy] 是文字格子的横向 / 纵向间距；[slope] 是文字基线的斜率（k = tan θ，与数学里一致：
     * 正值向右上倾斜，负值向右下倾斜，0 为水平）。
     *
     * 做法：把画布绕图片中心旋转 -θ，再在旋转后的坐标系里按水平方向平铺。
     * 为了让旋转后四个角仍然被铺满，平铺范围取以图片对角线为边长的正方形。
     */
    fun watermark(
        src: Bitmap,
        text: String,
        textSize: Float,
        opacity: Float,
        dx: Float,
        dy: Float,
        slope: Float = 0f,
    ): Bitmap {
        if (text.isBlank() || dx <= 0f || dy <= 0f) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((opacity * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
        }
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val k = if (slope.isNaN() || slope.isInfinite()) 0f else slope
        // 屏幕坐标 y 向下，所以“向右上倾斜”要把画布逆时针转，即传入负角度
        val angle = Math.toDegrees(Math.atan(k.toDouble())).toFloat()
        val diagonal = Math.hypot(w.toDouble(), h.toDouble()).toFloat()
        val left = w / 2f - diagonal / 2f
        val top = h / 2f - diagonal / 2f

        c.save()
        c.rotate(-angle, w / 2f, h / 2f)
        var y = top + dy
        var row = 0
        while (y < top + diagonal + dy) {
            var x = left + if (row % 2 == 0) dx / 2f else dx
            while (x < left + diagonal + dx) {
                c.drawText(text, x, y, paint)
                x += dx
            }
            y += dy
            row++
        }
        c.restore()
        return out
    }

    /** Clip to a rounded rectangle, leaving the corners fully transparent. */
    fun roundRect(src: Bitmap, radius: Float): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val r = radius.coerceIn(0f, minOf(w, h) / 2f)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (r <= 0f) {
            c.drawBitmap(src, 0f, 0f, null)
        } else {
            val path = Path().apply {
                addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            c.clipPath(path)
            c.drawBitmap(src, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        return out
    }

    /** Translucent white veil over the blurred image, used to finish a frosted-glass look. */
    fun frostedGlass(src: Bitmap, blurRadius: Int, veil: Float): Bitmap {
        val blurred = stackBlur(src, blurRadius)
        if (veil <= 0f) return blurred
        val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        // 原来用的是 MULTIPLY：乘以白色等于没变，“白色蒙层”滑块完全没有效果；应当是普通的 SRC_OVER 叠加
        c.drawColor(Color.argb((veil * 255).toInt().coerceIn(0, 255), 255, 255, 255), PorterDuff.Mode.SRC_OVER)
        return out
    }

    fun toJpeg(src: Bitmap, quality: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), baos)
        return baos.toByteArray()
    }

    fun toPng(src: Bitmap): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    /** Write a PNG to the shared Pictures library and return its uri. 边编码边写入，不在内存里攒整个 PNG 字节数组。 */
    fun savePngToGallery(context: Context, src: Bitmap, displayName: String): Uri? =
        saveStreamToGallery(context, displayName, "image/png") { out ->
            if (!src.compress(Bitmap.CompressFormat.PNG, 100, out)) throw java.io.IOException("PNG 编码失败")
        }

    /** Write pre-encoded bytes to the shared Pictures library and return its uri. */
    fun saveBytesToGallery(
        context: Context,
        data: ByteArray,
        mimeType: String,
        displayName: String,
    ): Uri? = saveStreamToGallery(context, displayName, mimeType) { it.write(data) }

    /**
     * 通用的“流式写入相册”：[writer] 直接往目标文件的输出流里写，
     * 大图编码后的字节不必先在内存里攒成 ByteArray（PNG / BMP 动辄几十上百 MB）。
     * 失败时删除写到一半的条目，并在主线程弹 Toast。
     */
    fun saveStreamToGallery(
        context: Context,
        displayName: String,
        mimeType: String,
        writer: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        var created: Uri? = null
        return runCatching {
            // "image/jpg" 不是合法的 MIME 类型（应为 image/jpeg），MediaStore 会按未知类型处理
            val mime = if (mimeType.equals("image/jpg", ignoreCase = true)) "image/jpeg" else mimeType
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/toTool")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: error("insert failed")
            created = uri
            val raw = resolver.openOutputStream(uri) ?: error("openOutputStream failed")
            java.io.BufferedOutputStream(raw, 1 shl 16).use { writer(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        }.onFailure {
            created?.let { u -> runCatching { resolver.delete(u, null, null) } } // 不留半截文件
            // 可能在后台线程被调用，Toast 必须回到主线程弹出
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }.getOrNull()
    }

    /** Human readable file size, e.g. 2.31 MB. */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
