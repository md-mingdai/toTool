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
            val matrix = MultiFormatWriter().encode(text, format, width, height, hints)
            if (type == BarcodeType.DATA_MATRIX) {
                // DataMatrixWriter 不处理 MARGIN 提示，按最小模块宽度补两圈静区
                val module = minimumRun(matrix)
                addQuietZone(matrix, maxOf(2, module * 2))
            } else {
                matrix
            }
        } catch (e: WriterException) {
            throw IllegalArgumentException(e.message ?: "无法生成，请检查内容格式")
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            if (message.contains("checksum", ignoreCase = true)) {
                throw IllegalArgumentException(checksumHint(type))
            }
            throw e
        }
    }

    /** 自定义编码器生成的矩阵没有静区，这里补上；ZXing 已带静区，quietZone 传 0。 */
    fun render(
        matrix: BitMatrix,
        quietZone: Int = 0,
        maxSize: Int = 1200,
        oneDimensional: Boolean = false,
    ): Bitmap {
        val source = if (quietZone > 0) addQuietZone(matrix, quietZone) else matrix
        val scaleX: Int
        val scaleY: Float
        if (oneDimensional) {
            // 一维码横向按需放大，纵向固定成一个较矮的条高，避免预览被拉成一根很高的柱子
            scaleX = if (source.width >= maxSize) 1 else maxOf(1, maxSize / source.width)
            scaleY = 220f / source.height
        } else {
            val longest = maxOf(source.width, source.height)
            scaleX = if (longest >= maxSize) 1 else maxOf(1, maxSize / longest)
            scaleY = scaleX.toFloat()
        }
        val width = source.width * scaleX
        val height = (source.height * scaleY).toInt().coerceAtLeast(1)

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
                        (x * scaleX).toFloat(),
                        (start * scaleY),
                        ((x + 1) * scaleX).toFloat(),
                        (y * scaleY),
                        paint,
                    )
                } else {
                    y++
                }
            }
        }
        return bitmap
    }

    private fun checksumHint(type: BarcodeType): String = when (type) {
        BarcodeType.EAN13 -> "EAN-13 校验位不正确：请输入 12 位数字自动补校验位，或输入校验正确的 13 位"
        BarcodeType.EAN8 -> "EAN-8 校验位不正确：示例 12345670"
        BarcodeType.UPCA -> "UPC-A 校验位不正确：示例 012345678905"
        BarcodeType.ITF -> "ITF 只支持数字，且长度需为偶数"
        else -> "内容校验位不正确，请检查数字"
    }

    fun quietZone(type: BarcodeType): Int = when (type) {
        BarcodeType.HANXIN -> 3
        BarcodeType.CODE49 -> 10
        else -> 0
    }

    /** 估计缩放后的最小模块宽度（最短的黑 / 白连续段）。 */
    private fun minimumRun(matrix: BitMatrix): Int {
        var minimum = Int.MAX_VALUE
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                val dark = matrix[x, y]
                var run = 0
                while (x < matrix.width && matrix[x, y] == dark) {
                    run++
                    x++
                }
                if (run < minimum) minimum = run
            }
        }
        return if (minimum == Int.MAX_VALUE) 2 else minimum
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
