package com.mingdai.toTool.barcode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.mingdai.toTool.barcode.code49.Code49ImageDecoder
import com.mingdai.toTool.barcode.hanxin.HanXinImageDecoder
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

/** 归一化到 0..1 的识别框，坐标相对于送进识别器的整张图。 */
data class ScanBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class ScanResult(
    val text: String,
    val formatLabel: String,
    val bounds: ScanBounds? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

/** 从位图里识别条码：常见格式交给 ZXing，汉信码 / Code 49 交给本项目的解码器。 */
object BarcodeScanner {

    private val formats = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODABAR,
        BarcodeFormat.ITF,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
    )

    fun decode(bitmap: Bitmap): ScanResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = HybridBinarizer(source)

        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = formats
        hints[DecodeHintType.TRY_HARDER] = true
        hints[DecodeHintType.ALSO_INVERTED] = true
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        try {
            val result = MultiFormatReader().apply { setHints(hints) }
                .decode(BinaryBitmap(binary))
            return ScanResult(
                text = result.text,
                formatLabel = formatLabel(result.barcodeFormat),
                bounds = zxingBounds(result, width, height),
                frameWidth = width,
                frameHeight = height,
            )
        } catch (e: Exception) {
            // 继续尝试汉信码 / Code 49
        }

        val matrix = runCatching { binary.blackMatrix }.getOrNull() ?: return null
        HanXinImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return ScanResult(
                text = hit.text,
                formatLabel = "汉信码",
                bounds = normalize(
                    hit.left.toFloat(),
                    hit.top.toFloat(),
                    hit.right.toFloat(),
                    hit.bottom.toFloat(),
                    matrix.width,
                    matrix.height,
                ),
                frameWidth = matrix.width,
                frameHeight = matrix.height,
            )
        }
        Code49ImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return ScanResult(
                text = hit.text,
                formatLabel = "Code 49",
                bounds = normalize(
                    hit.left.toFloat(),
                    hit.top.toFloat(),
                    hit.right.toFloat(),
                    hit.bottom.toFloat(),
                    matrix.width,
                    matrix.height,
                ),
                frameWidth = matrix.width,
                frameHeight = matrix.height,
            )
        }
        return null
    }

    private fun zxingBounds(result: Result, frameWidth: Int, frameHeight: Int): ScanBounds? {
        val points = result.resultPoints ?: return null
        if (points.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (point in points) {
            left = min(left, point.x)
            top = min(top, point.y)
            right = max(right, point.x)
            bottom = max(bottom, point.y)
        }
        if (right < left || bottom < top) return null
        val padX = max((right - left) * 0.06f, 2f)
        // 一维码的结果点是一条线，上下需要额外留出可点击的高度
        val padY = if (bottom - top < frameHeight * 0.01f) {
            frameHeight * 0.04f
        } else {
            max((bottom - top) * 0.06f, 2f)
        }
        return normalize(left - padX, top - padY, right + padX, bottom + padY, frameWidth, frameHeight)
    }

    private fun normalize(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        frameWidth: Int,
        frameHeight: Int,
    ): ScanBounds? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val l = (left / frameWidth).coerceIn(0f, 1f)
        val t = (top / frameHeight).coerceIn(0f, 1f)
        val r = (right / frameWidth).coerceIn(0f, 1f)
        val b = (bottom / frameHeight).coerceIn(0f, 1f)
        if (r <= l || b <= t) return null
        return ScanBounds(l, t, r, b)
    }

    private fun formatLabel(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE -> "QR 码"
        BarcodeFormat.DATA_MATRIX -> "Data Matrix"
        BarcodeFormat.PDF_417 -> "PDF 417"
        BarcodeFormat.AZTEC -> "Aztec"
        BarcodeFormat.CODE_128 -> "Code 128"
        BarcodeFormat.CODE_39 -> "Code 39"
        BarcodeFormat.CODE_93 -> "Code 93"
        BarcodeFormat.CODABAR -> "Codabar"
        BarcodeFormat.ITF -> "ITF"
        BarcodeFormat.EAN_13 -> "EAN-13"
        BarcodeFormat.EAN_8 -> "EAN-8"
        BarcodeFormat.UPC_A -> "UPC-A"
        else -> format.name
    }
}
