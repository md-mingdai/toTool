package com.mingdai.toTool.barcode

import com.google.zxing.BarcodeFormat

enum class BarcodeGroup(val label: String) {
    TWO_D("二维码"),
    ONE_D("条形码"),
}

/**
 * 支持生成的条码类型。
 * zxingFormat 为空表示由本项目的移植实现编码（汉信码 / Code 49）。
 */
enum class BarcodeType(
    val label: String,
    val group: BarcodeGroup,
    val sample: String,
    val zxingFormat: BarcodeFormat?,
    val note: String,
    val options: List<String> = emptyList(),
    val defaultOption: Int = 0,
) {
    QR(
        "QR 码", BarcodeGroup.TWO_D, "https://github.com", BarcodeFormat.QR_CODE,
        "容错越高，码越密但越耐污损", listOf("L", "M", "Q", "H"), 1,
    ),
    HANXIN(
        "汉信码", BarcodeGroup.TWO_D, "汉信码 Han Xin", null,
        "中国国家标准 GB/T 21049；中文按 UTF-8 存储", listOf("L1", "L2", "L3", "L4"), 1,
    ),
    DATA_MATRIX(
        "Data Matrix", BarcodeGroup.TWO_D, "Data Matrix", BarcodeFormat.DATA_MATRIX,
        "小尺寸、高密度，工业标签常用",
    ),
    PDF417(
        "PDF 417", BarcodeGroup.TWO_D, "PDF 417 示例", BarcodeFormat.PDF_417,
        "堆叠式二维码，证件与登机牌常用",
        listOf("0", "1", "2", "3", "4", "5", "6", "7", "8"), 2,
    ),
    CODE49(
        "Code 49", BarcodeGroup.TWO_D, "CODE 49 123456", null,
        "堆叠式条码，仅支持 ASCII 字符",
    ),
    CODE128(
        "Code 128", BarcodeGroup.ONE_D, "ABC-123456", BarcodeFormat.CODE_128,
        "支持全部 ASCII，最常用的物流条码",
    ),
    CODE39(
        "Code 39", BarcodeGroup.ONE_D, "CODE-39", BarcodeFormat.CODE_39,
        "大写字母、数字和少量符号",
    ),
    CODE93(
        "Code 93", BarcodeGroup.ONE_D, "CODE-93", BarcodeFormat.CODE_93,
        "比 Code 39 更紧凑",
    ),
    ITF(
        "ITF", BarcodeGroup.ONE_D, "123456", BarcodeFormat.ITF,
        "只能包含数字，长度需为偶数",
    ),
    EAN13(
        "EAN-13", BarcodeGroup.ONE_D, "6901234567892", BarcodeFormat.EAN_13,
        "商品条码，12 或 13 位数字",
    ),
    EAN8(
        "EAN-8", BarcodeGroup.ONE_D, "12345678", BarcodeFormat.EAN_8,
        "短商品条码，7 或 8 位数字",
    ),
    UPCA(
        "UPC-A", BarcodeGroup.ONE_D, "012345678905", BarcodeFormat.UPC_A,
        "北美商品条码，11 或 12 位数字",
    ),
    CODABAR(
        "Codabar", BarcodeGroup.ONE_D, "A123456A", BarcodeFormat.CODABAR,
        "首尾需用 A/B/C/D 作为起始符",
    ),
    ;

    val isTwoD: Boolean get() = group == BarcodeGroup.TWO_D
    val isCustom: Boolean get() = zxingFormat == null
}
