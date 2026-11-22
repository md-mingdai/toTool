package com.mingdai.toTool.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.mingdai.toTool.ui.screens.tools.ImageOps
import java.io.File
import java.util.Locale
import kotlin.math.min

internal class GifComposition(val file: File, val frames: Int)

/** 把图片列表合成为 GIF。静态图片按停留帧重复；GIF / APNG 按自身帧展开。 */
internal fun composeGif(
    context: Context,
    plan: ConversionPlan,
    workDir: File,
    control: ConversionControl,
    onStage: (String) -> Unit,
    onProgress: (Float?) -> Unit,
): GifComposition {
    if (plan.gifItems.isEmpty()) throw ConvertException("GIF 图片列表是空的")
    val ffmpeg = ffmpegBinary(context) ?: throw ConvertException(ffmpegUnavailableMessage(context))
    val root = File(workDir, "gif_${System.nanoTime()}")
    val sourceDir = File(root, "src").apply { mkdirs() }
    val baseFrames = mutableListOf<File>()
    try {
        plan.gifItems.forEachIndexed { index, item ->
            if (control.canceled) throw ConvertCanceledException()
            if (item.animated) {
                onStage("正在展开 ${item.name} 的动画帧…")
                onProgress(0.05f + index.toFloat() / plan.gifItems.size * 0.1f)
                val animInput = copyUriToTemp(
                    context = context,
                    uri = item.uri,
                    dir = sourceDir,
                    totalBytes = queryFileSize(context, item.uri),
                    canceled = control.flag,
                    prefix = "gif_src_",
                ) { }
                val animDir = File(sourceDir, "anim_$index").apply { mkdirs() }
                val pattern = File(animDir, "frame_%05d.png").absolutePath
                val command = listOf(
                    ffmpeg.absolutePath,
                    "-hide_banner", "-nostdin",
                    "-i", animInput.absolutePath,
                    "-vf", "scale=720:720:force_original_aspect_ratio=decrease," +
                        "pad=720:720:(ow-iw)/2:(oh-ih)/2:color=black",
                    "-vsync", "0",
                    "-y", pattern,
                )
                val result = runFfmpeg(command, control, null) { }
                animInput.delete()
                if (result is ConvertResult.Canceled) throw ConvertCanceledException()
                if (result is ConvertResult.Failure) {
                    throw ConvertException("展开动图失败：${result.message}")
                }
                val frames = animDir.listFiles { file -> file.isFile && file.name.endsWith(".png", true) }
                    ?.sortedBy { it.name }
                    .orEmpty()
                if (frames.isEmpty()) throw ConvertException("${item.name} 没有可用的动画帧")
                baseFrames.addAll(frames)
            } else {
                onStage("正在准备 ${item.name}…")
                onProgress(0.05f + index.toFloat() / plan.gifItems.size * 0.1f)
                val png = prepareGifFrame(context, item.uri, sourceDir, index, control.flag)
                repeat(item.holdFrames.coerceIn(1, 1000)) {
                    baseFrames.add(png)
                }
            }
        }
        if (baseFrames.isEmpty()) throw ConvertException("GIF 没有可用的画面")
        val totalFrames = baseFrames.size

        onStage("正在编排 $totalFrames 帧…")
        onProgress(0.18f)
        // 连续的同一张图合并成一个 GIF 帧 + 更长的延迟，保证“停留帧数”真正生效。
        val runs = ArrayList<Pair<File, Int>>()
        baseFrames.forEach { frame ->
            if (control.canceled) throw ConvertCanceledException()
            val last = runs.lastOrNull()
            if (last != null && last.first == frame) {
                runs[runs.lastIndex] = last.first to (last.second + 1)
            } else {
                runs.add(frame to 1)
            }
        }
        onProgress(0.35f)

        onStage("正在合成 GIF…")
        onProgress(0.52f)
        val concatFile = File(root, "concat.txt")
        concatFile.bufferedWriter().use { writer ->
            runs.forEach { (file, frames) ->
                writer.write("file '${file.absolutePath}'\n")
                writer.write("duration ${String.format(Locale.US, "%.3f", frames / 10.0)}\n")
            }
            // concat demuxer 会忽略最后一行的 duration；再写一次末图，让最后一帧也有完整停留时间。
            runs.lastOrNull()?.let { (file, _) ->
                writer.write("file '${file.absolutePath}'\n")
            }
        }

        val output = File(root, plan.outputName)
        val command = listOf(
            ffmpeg.absolutePath,
            "-hide_banner", "-nostdin",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath,
            "-vf", "scale=720:720:force_original_aspect_ratio=decrease," +
                "pad=720:720:(ow-iw)/2:(oh-ih)/2:color=black," +
                "split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse=dither=sierra2_4a",
            "-vsync", "vfr",
            // 不裁剪变化区域、不使用帧间透明，避免部分相册只显示个别帧或花屏；体积会稍大。
            "-gifflags", "-offsetting-transdiff",
            "-loop", "0",
            "-max_muxing_queue_size", "4096",
            "-y", output.absolutePath,
        )
        val result = runFfmpeg(command, control, totalFrames / 10.0) { p ->
            onProgress(0.52f + (p ?: 0f) * 0.45f)
        }
        if (result is ConvertResult.Canceled) throw ConvertCanceledException()
        if (result is ConvertResult.Failure) throw ConvertException("GIF 合成失败：${result.message}")
        if (!output.exists() || output.length() == 0L) throw ConvertException("GIF 合成后没有生成文件")
        onProgress(1f)
        return GifComposition(output, totalFrames)
    } finally {
        sourceDir.deleteRecursively()
    }
}


/** 把任意分辨率的图片统一画到 720×720 黑底画布，保证 concat 的所有输入帧尺寸一致。 */
private fun prepareGifFrame(
    context: Context,
    uri: Uri,
    dir: File,
    index: Int,
    canceled: java.util.concurrent.atomic.AtomicBoolean,
): File {
    if (canceled.get()) throw ConvertCanceledException()
    val source = ImageOps.uriToBitmap(context, uri, 720)
        ?: throw ConvertException("无法解码 GIF 列表里的图片")
    val size = 720
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val outFile = File(dir, "item_${index}.png")
    try {
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val scale = min(size.toFloat() / source.width, size.toFloat() / source.height)
        val drawWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val drawHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            source,
            null,
            android.graphics.RectF(
                (size - drawWidth) / 2f,
                (size - drawHeight) / 2f,
                (size + drawWidth) / 2f,
                (size + drawHeight) / 2f,
            ),
            paint,
        )
        outFile.outputStream().use { stream ->
            if (!output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw ConvertException("GIF 列表图片编码失败")
            }
        }
        return outFile
    } catch (e: Exception) {
        outFile.delete()
        throw e
    } finally {
        source.recycle()
        output.recycle()
    }
}
