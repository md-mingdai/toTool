package com.mingdai.toTool.barcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mingdai.toTool.barcode.code49.Code49Codec
import com.mingdai.toTool.barcode.hanxin.HanXinCodec
import java.util.EnumMap

/** 生成 BitMatrix，并渲染成清晰的黑白位图。 */
object BarcodeRenderer {

    fun encode(type: BarcodeType, content: String, option: Int): BitMatrix {
        val text = content.trim()
        if (text.isEmpty()) throw IllegalArgumentException("请输入内容")

        if (type.isCustom) {
            return when (type) {
                BarcodeType.HANXIN -> {
                    val matrix = HanXinCodec.encode(text, (option + 1).coerceIn(1, 4))
                    // 自检：编码结果必须能被本项目解码器读回，避免输出无法识别的码
                    check(HanXinCodec.decode(matrix) == text) { "汉信码编码自检失败" }
                    matrix
                }

                BarcodeType.CODE49 -> {
                    val matrix = Code49Codec.encode(text)
                    check(Code49Codec.decode(matrix) == text) { "Code 49 编码自检失败" }
                    matrix
                }

                else -> throw IllegalArgumentException("不支持的条码类型")
            }
        }

        val format = type.zxingFormat ?: throw IllegalArgumentException("不支持的条码类型")
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = if (type.isTwoD) 4 else 10
        when (type) {
            BarcodeType.QR -> {
                val level = when (option.coerceIn(0, 3)) {
                    0 -> ErrorCorrectionLevel.L
                    1 -> ErrorCorrectionLevel.M
                    2 -> ErrorCorrectionLevel.Q
                    else -> ErrorCorrectionLevel.H
                }
                hints[EncodeHintType.ERROR_CORRECTION] = level
            }

            BarcodeType.PDF417 -> hints[EncodeHintType.ERROR_CORRECTION] = option.coerceIn(0, 8)
            else -> Unit
        }

        // DataMatrixWriter 在请求尺寸小于矩阵时会算出 0 倍缩放，必须给一个足够大的正尺寸
        val width = if (type == BarcodeType.DATA_MATRIX) 1200 else 0
        val height = when {
            type == BarcodeType.DATA_MATRIX -> 1200
            type.isTwoD -> 0
            else -> 200
        }
        return try {
            MultiFormatWriter().encode(text, format, width, height, hints)
        } catch (e: WriterException) {
            throw IllegalArgumentException(e.message ?: "无法生成，请检查内容格式")
        } catch (e: IllegalArgumentException) {
            throw e
        }
    }

    /** 自定义编码器生成的矩阵没有静区，这里补上；ZXing 已带静区，quietZone 传 0。 */
    fun render(
        matrix: BitMatrix,
        quietZone: Int = 0,
        maxSize: Int = 1200,
    ): Bitmap {
        val source = if (quietZone > 0) addQuietZone(matrix, quietZone) else matrix
        val longest = maxOf(source.width, source.height)
        val scale = if (longest >= maxSize) 1 else maxOf(1, maxSize / longest)
        val width = source.width * scale
        val height = source.height * scale

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = false
        }
        for (x in 0 until source.width) {
            var y = 0
            while (y < source.height) {
                if (source[x, y]) {
                    val start = y
                    while (y < source.height && source[x, y]) y++
                    canvas.drawRect(
                        (x * scale).toFloat(),
                        (start * scale).toFloat(),
                        ((x + 1) * scale).toFloat(),
                        (y * scale).toFloat(),
                        paint,
                    )
                } else {
                    y++
                }
            }
        }
        return bitmap
    }

    fun quietZone(type: BarcodeType): Int = when (type) {
        BarcodeType.HANXIN -> 3
        BarcodeType.CODE49 -> 10
        else -> 0
    }

    private fun addQuietZone(matrix: BitMatrix, zone: Int): BitMatrix {
        val result = BitMatrix(matrix.width + zone * 2, matrix.height + zone * 2)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) result.set(x + zone, y + zone)
            }
        }
        return result
    }
}
