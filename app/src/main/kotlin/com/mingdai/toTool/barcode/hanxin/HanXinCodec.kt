package com.mingdai.toTool.barcode.hanxin

import com.google.zxing.common.BitMatrix

/** 汉信码生成 / 解码入口。 */
object HanXinCodec {

    /** @param eccLevel 1..4，对应 L1..L4。 */
    fun encode(text: String, eccLevel: Int = 2): BitMatrix {
        require(eccLevel in 1..4) { "纠错级别必须是 1..4" }
        val data = HanXinPayloadCodec.encodeText(text)
        var version = 1
        while (version <= 84 && HanXinTables.dataCodewords(version, eccLevel) < data.size) version++
        if (version > 84) throw IllegalArgumentException("内容过长，汉信码放不下")

        var bestMask = 0
        var bestMatrix = HanXinMatrixCodec.encode(data, version, eccLevel, 0)
        var bestScore = HanXinMatrixCodec.evaluate(bestMatrix)
        for (mask in 1 until 4) {
            val candidate = HanXinMatrixCodec.encode(data, version, eccLevel, mask)
            val score = HanXinMatrixCodec.evaluate(candidate)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
                bestMatrix = candidate
            }
        }
        return bestMatrix
    }

    /** 输入已经按模块对齐的矩阵；成功返回文本。 */
    fun decode(matrix: BitMatrix): String? {
        val decoded = HanXinMatrixCodec.tryDecode(matrix) ?: return null
        return HanXinPayloadCodec.decode(decoded.data)
    }
}
