package com.mingdai.toTool.barcode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.multi.qrcode.detector.MultiDetector
import com.google.zxing.qrcode.decoder.Decoder
import com.mingdai.toTool.barcode.code49.Code49ImageDecoder
import com.mingdai.toTool.barcode.hanxin.HanXinImageDecoder

/**
 * 一次识别结果。
 * [quad] 是码的四个角（顺时针，x0,y0,…,x3,y3），坐标在送进识别器的那张图上；相册识别不需要定位，为 null。
 * [slow] = true 表示来自低频的慢路径（汉信码 / Code 49 / 反色 / 竖放一维码），刷新比较慢。
 */
data class ScanResult(
    val text: String,
    val formatLabel: String,
    val quad: FloatArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val slow: Boolean = false,
)

/** 已经定位到、但这一帧还没解出内容的码（运动模糊、失焦、太小……）。 */
class Candidate(val quad: FloatArray, val score: Float)

/** 快路径一帧的结果：解出来的码 + 只定位到的码。 */
class FastScan(val results: List<ScanResult>, val candidates: List<Candidate>)

/** 慢路径一轮做什么：ZXing 的重型尝试，或本项目自己的汉信码 / Code 49 解码器。 */
enum class SlowPhase { ZXING, CUSTOM }

/** 条码识别：常见格式交给 ZXing，汉信码 / Code 49 交给本项目的解码器。 */
object BarcodeScanner {

    private const val QR_LABEL = "QR 码"

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

    private val galleryHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to allFormats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    // QR 的 TRY_HARDER 只是把找定位点的扫描行距从 5 行缩到 3 行，几乎不增加耗时，快路径也开
    private val qrHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    // 快路径的其它格式：TRY_HARDER 让一维码扫全部行（否则只扫画面中间一条带），但不开反色
    private val fastOtherHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to nonQrFormats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    private val slowOtherHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to nonQrFormats,
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** 相册单图识别：找到第一个可解码的码。 */
    fun decode(bitmap: Bitmap): ScanResult? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)

        try {
            // MultiFormatReader.decode(image) 会先 setHints(null)，之前 setHints 设的格式、TRY_HARDER、
            // ALSO_INVERTED 全部作废；必须用带 hints 的重载。
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), galleryHints)
            val text = result.text
            if (!text.isNullOrEmpty()) {
                return ScanResult(text, formatLabel(result.barcodeFormat), null, width, height)
            }
        } catch (e: Exception) {
            // 继续尝试汉信码 / Code 49
        }

        // ALSO_INVERTED 会就地翻转它拿到的二值矩阵，所以汉信码 / Code 49 用全新的一份
        val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull() ?: return null
        HanXinImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return ScanResult(hit.text, "汉信码", null, width, height)
        }
        Code49ImageDecoder.decodeDetailed(matrix)?.let { hit ->
            return ScanResult(hit.text, "Code 49", null, width, height)
        }
        return null
    }

    /**
     * 实时快路径（每帧，目标 < 30ms）：多个 QR + 其它常见格式，返回码的四个角。
     * QR 同时返回「定位到但没解出来」的候选，用来在运动模糊时继续保持框。
     */
    fun scanFast(frame: LumaFrame): FastScan {
        val source = LumaSource(frame, allowRotate = false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // 快路径不开 ALSO_INVERTED，所以不会有人就地翻转这份矩阵，QR、其它格式、一维码量高度可以共用
        val matrix = runCatching { bitmap.blackMatrix }.getOrNull() ?: return FastScan(emptyList(), emptyList())

        val results = ArrayList<ScanResult>(4)
        val candidates = ArrayList<Candidate>(4)
        scanQr(matrix, frame, false, results, candidates)
        runCatching {
            GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(bitmap, fastOtherHints)
        }.getOrNull()?.forEach { r ->
            toFrameResult(r, matrix, frame, false)?.let { results.add(it) }
        }
        return FastScan(dedupe(results), candidates)
    }

    /**
     * 慢路径（独立线程，一轮几百毫秒）。两种活交替做，互不拖累：
     * - ZXING：QR 反色、一维码竖放 / 反色（TRY_HARDER + ALSO_INVERTED）；
     * - CUSTOM：汉信码、Code 49。
     * 这两个自带解码器是按「整张图只有一个码」写的，在画面里找不到就会把所有候选版本试一遍，
     * 非常慢，所以必须有截止时间，并且不能和快路径放在同一个线程里（旧代码里它们会拖住所有 QR 的结果）。
     */
    fun scanSlow(frame: LumaFrame, phase: SlowPhase, deadlineNs: Long): List<ScanResult> {
        val found = ArrayList<ScanResult>(4)
        val source = LumaSource(frame, allowRotate = true)
        if (phase == SlowPhase.ZXING) {
            // 一维码量条高用的矩阵：和 ZXing 内部那份分开，免得被 ALSO_INVERTED 翻转
            val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull()
            runCatching { HybridBinarizer(source.invert()).blackMatrix }.getOrNull()?.let { inverted ->
                scanQr(inverted, frame, true, found, null)
            }
            if (System.nanoTime() < deadlineNs) {
                runCatching {
                    GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(
                        BinaryBitmap(HybridBinarizer(source)),
                        slowOtherHints,
                    )
                }.getOrNull()?.forEach { r ->
                    toFrameResult(r, matrix, frame, true)?.let { found.add(it) }
                }
            }
        } else {
            val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull()
                ?: return emptyList()
            HanXinImageDecoder.decodeDetailed(matrix, deadlineNs)?.let { hit ->
                rectResult(hit.text, "汉信码", hit.left, hit.top, hit.right, hit.bottom, frame)?.let { found.add(it) }
            }
            if (System.nanoTime() < deadlineNs) {
                Code49ImageDecoder.decodeDetailed(matrix)?.let { hit ->
                    rectResult(hit.text, "Code 49", hit.left, hit.top, hit.right, hit.bottom, frame)
                        ?.let { found.add(it) }
                }
            }
        }
        return dedupe(found)
    }

    /**
     * QR：自己走「定位 → 解码」两步（等价于 QRCodeMultiReader.decodeMultiple），
     * 这样解码失败的定位结果也能留下来当候选；ZXing 自带的多码读取会把它们直接吞掉。
     */
    private fun scanQr(
        matrix: BitMatrix,
        frame: LumaFrame,
        slow: Boolean,
        results: MutableList<ScanResult>,
        candidates: MutableList<Candidate>?,
    ) {
        val detections = runCatching { MultiDetector(matrix).detectMulti(qrHints) }.getOrNull() ?: return
        val decoder = Decoder()
        for (detection in detections) {
            val raw = CodeGeometry.qrQuad(detection.points) ?: continue
            val quad = CodeGeometry.canonical(raw) ?: continue
            val text = try {
                decoder.decode(detection.bits, qrHints).text
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrEmpty()) {
                candidates?.add(Candidate(quad, 1f))
            } else {
                results.add(ScanResult(text, QR_LABEL, quad, frame.width, frame.height, slow))
            }
        }
    }

    private fun toFrameResult(r: Result, matrix: BitMatrix?, frame: LumaFrame, slow: Boolean): ScanResult? {
        val text = r.text
        if (text.isNullOrEmpty()) return null
        val quad = CodeGeometry.quadFromResult(r, matrix) ?: return null
        return ScanResult(text, formatLabel(r.barcodeFormat), quad, frame.width, frame.height, slow)
    }

    /** 自带解码器给的是整数像素的外接矩形（最后一个像素也算在内）。 */
    private fun rectResult(
        text: String,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        frame: LumaFrame,
    ): ScanResult? {
        val quad = CodeGeometry.canonical(
            CodeGeometry.rectQuad(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat()),
        ) ?: return null
        return ScanResult(text, label, quad, frame.width, frame.height, true)
    }

    private fun dedupe(list: List<ScanResult>): List<ScanResult> =
        list.distinctBy { it.formatLabel + "\u0000" + it.text }

    private fun formatLabel(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE -> QR_LABEL
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
