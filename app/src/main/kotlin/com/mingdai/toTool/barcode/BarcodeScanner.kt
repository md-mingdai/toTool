package com.mingdai.toTool.barcode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.multi.qrcode.detector.MultiDetector
import com.mingdai.toTool.barcode.code49.Code49ImageDecoder
import com.mingdai.toTool.barcode.hanxin.HanXinImageDecoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** 归一化到 0..1 的识别框，坐标相对于送进识别器的整张图。 */
data class ScanBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** 只完成定位、还没有解码的候选码框。 */
data class ScanCandidate(
    val bounds: ScanBounds,
    val frameWidth: Int,
    val frameHeight: Int,
)

data class ScanResult(
    val text: String,
    val formatLabel: String,
    val bounds: ScanBounds? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
)

/** 从位图里识别条码：常见格式交给 ZXing，汉信码 / Code 49 交给本项目的解码器。 */
object BarcodeScanner {

    private val allFormats = listOf(
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

    private val nonQrFormats = allFormats.filter { it != BarcodeFormat.QR_CODE }

    /** 相册单图识别：找到第一个可解码的码。 */
    fun decode(bitmap: Bitmap): ScanResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = HybridBinarizer(source)

        try {
            val result = MultiFormatReader().apply { setHints(singleHints()) }
                .decode(BinaryBitmap(binary))
            return toScanResult(result, width, height)
        } catch (e: Exception) {
            // 继续尝试汉信码 / Code 49
        }

        val matrix = runCatching { binary.blackMatrix }.getOrNull() ?: return null
        HanXinImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return toScanResult(hit, matrix)
        }
        Code49ImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return toScanResult(hit, matrix, "Code 49")
        }
        return null
    }

    /** 摄像头逐帧的快速定位阶段：只找候选框，不做解码。 */
    fun detect(bitmap: Bitmap): List<ScanCandidate> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return emptyList()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val matrix = runCatching {
            HybridBinarizer(RGBLuminanceSource(width, height, pixels)).blackMatrix
        }.getOrNull() ?: return emptyList()
        return detectCandidates(matrix)
    }

    /**
     * 需要解码的帧：多个 QR、多个其它格式、汉信码和 Code 49 并行解码。
     * 该方法会阻塞到所有解码任务结束，调用方应放在后台线程。
     */
    fun decodeAll(bitmap: Bitmap): List<ScanResult> {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return emptyList()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull()
            ?: return emptyList()

        return runBlocking {
            coroutineScope {
                val qr = async(Dispatchers.Default) {
                    decodeQrMultiple(source, width, height)
                }
                val others = async(Dispatchers.Default) {
                    decodeOthersMultiple(source, width, height)
                }
                val hanXin = async(Dispatchers.Default) {
                    HanXinImageDecoder.decodeDetailed(matrix)?.let { hit ->
                        toScanResult(hit, matrix)
                    }
                }
                val code49 = async(Dispatchers.Default) {
                    Code49ImageDecoder.decodeDetailed(matrix)?.let { hit ->
                        toScanResult(hit, matrix, "Code 49")
                    }
                }

                val results = ArrayList<ScanResult>()
                results.addAll(qr.await())
                results.addAll(others.await())
                hanXin.await()?.let { results.add(it) }
                code49.await()?.let { results.add(it) }
                results.distinctBy { it.formatLabel + "\u0000" + it.text }
            }
        }
    }

    private fun decodeQrMultiple(source: LuminanceSource, width: Int, height: Int): List<ScanResult> {
        val hints = qrHints()
        return runCatching {
            QRCodeMultiReader().decodeMultiple(
                BinaryBitmap(HybridBinarizer(source)),
                hints,
            ).toList()
        }.getOrDefault(emptyList()).mapNotNull { toScanResult(it, width, height) }
    }

    private fun decodeOthersMultiple(source: LuminanceSource, width: Int, height: Int): List<ScanResult> {
        val hints = nonQrHints()
        var results = runCatching {
            GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(
                BinaryBitmap(HybridBinarizer(source)),
                hints,
            ).toList()
        }.getOrDefault(emptyList())
        if (results.isEmpty()) {
            results = runCatching {
                listOf(
                    MultiFormatReader().apply { setHints(hints) }
                        .decode(BinaryBitmap(HybridBinarizer(source))),
                )
            }.getOrDefault(emptyList())
        }
        return results.mapNotNull { toScanResult(it, width, height) }
    }

    private fun detectCandidates(matrix: BitMatrix): List<ScanCandidate> {
        val width = matrix.width
        val height = matrix.height
        val candidates = ArrayList<ScanCandidate>()

        fun add(points: Array<ResultPoint>?) {
            val bounds = boundsFromPoints(points, width, height) ?: return
            val w = bounds.right - bounds.left
            val h = bounds.bottom - bounds.top
            if (w < 0.015f || h < 0.015f) return
            if (w > 0.96f || h > 0.96f) return
            val aspect = w / h.coerceAtLeast(1e-4f)
            if (aspect < 0.35f || aspect > 2.8f) return
            if (w * h > 0.75f) return
            val candidate = ScanCandidate(bounds, width, height)
            if (candidates.none { candidateKey(it.bounds) == candidateKey(bounds) }) {
                candidates.add(candidate)
            }
        }

        runCatching {
            MultiDetector(matrix).detectMulti(mapOf(DecodeHintType.TRY_HARDER to true))
        }.getOrNull()?.forEach { add(it.points) }

        return candidates.take(12)
    }

    private fun toScanResult(hit: HanXinImageDecoder.Hit, matrix: BitMatrix): ScanResult =
        toScanResult(hit, matrix, "汉信码")

    private fun toScanResult(
        hit: HanXinImageDecoder.Hit,
        matrix: BitMatrix,
        label: String,
    ): ScanResult = ScanResult(
        text = hit.text,
        formatLabel = label,
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

    private fun toScanResult(hit: Code49ImageDecoder.Hit, matrix: BitMatrix, label: String): ScanResult =
        ScanResult(
            text = hit.text,
            formatLabel = label,
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

    private fun toScanResult(result: Result, frameWidth: Int, frameHeight: Int): ScanResult? {
        val text = result.text
        if (text.isEmpty()) return null
        return ScanResult(
            text = text,
            formatLabel = formatLabel(result.barcodeFormat),
            bounds = zxingBounds(result, frameWidth, frameHeight),
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
    }

    private fun singleHints(): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to allFormats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private fun qrHints(): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private fun nonQrHints(): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to nonQrFormats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private fun zxingBounds(result: Result, frameWidth: Int, frameHeight: Int): ScanBounds? {
        val points = result.resultPoints ?: return null
        return boundsFromPoints(points, frameWidth, frameHeight)
    }

    private fun boundsFromPoints(
        points: Array<ResultPoint>?,
        frameWidth: Int,
        frameHeight: Int,
    ): ScanBounds? {
        if (points.isNullOrEmpty()) return null
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
        val padY = if (bottom - top < frameHeight * 0.01f) {
            frameHeight * 0.04f
        } else {
            max((bottom - top) * 0.06f, 2f)
        }
        return normalize(left - padX, top - padY, right + padX, bottom + padY, frameWidth, frameHeight)
    }

    private fun candidateKey(bounds: ScanBounds): String {
        val centerX = ((bounds.left + bounds.right) * 12f).roundToInt()
        val centerY = ((bounds.top + bounds.bottom) * 12f).roundToInt()
        val width = ((bounds.right - bounds.left) * 12f).roundToInt()
        return "$centerX:$centerY:$width"
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
