package com.mingdai.toTool.barcode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mingdai.toTool.barcode.code49.Code49ImageDecoder
import com.mingdai.toTool.barcode.hanxin.HanXinImageDecoder
import java.util.EnumMap

data class ScanResult(
    val text: String,
    val formatLabel: String,
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
            return ScanResult(result.text, formatLabel(result.barcodeFormat))
        } catch (e: Exception) {
            // 继续尝试汉信码 / Code 49
        }

        val matrix = runCatching { binary.blackMatrix }.getOrNull() ?: return null
        HanXinImageDecoder.decode(matrix)?.let { return ScanResult(it, "汉信码") }
        Code49ImageDecoder.decode(matrix)?.let { return ScanResult(it, "Code 49") }
        return null
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
