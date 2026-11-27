package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class UnicodeDirection(val label: String) {
    ENCODE("编码"),
    DECODE("解码"),
}

private enum class UnicodeFormat(val label: String) {
    JAVA("\\uXXXX"),
    CODE_POINT("U+XXXX"),
    HTML("HTML 数字实体"),
}

private data class UnicodeConversion(val output: String, val error: String?)

/** 超过这个长度就先等一小段再在后台转换，避免长文本每敲一个字都全量重算。 */
private const val LARGE_INPUT_THRESHOLD = 32_000

private class UnicodeDecodeException(position: Int, message: String) :
    Exception("位置 ${position + 1}：$message")

@Composable
fun UnicodeTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(UnicodeDirection.ENCODE) }
    var format by rememberSaveable { mutableStateOf(UnicodeFormat.JAVA) }
    var keepAscii by rememberSaveable { mutableStateOf(true) }

    val conversion by produceState(
        UnicodeConversion("", null),
        input, direction, format, keepAscii,
    ) {
        if (input.isEmpty()) {
            value = UnicodeConversion("", null)
        } else {
            if (input.length > LARGE_INPUT_THRESHOLD) delay(150)
            value = withContext(Dispatchers.Default) {
                convertUnicode(input, direction, format, keepAscii)
            }
        }
    }

    val colors = MiuixTheme.colorScheme
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = if (direction == UnicodeDirection.ENCODE) "待编码文本" else "含 Unicode 转义的文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 9,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnicodeDirection.entries.forEach { item ->
                SelectChip(
                    selected = direction == item,
                    onClick = { direction = item },
                    label = item.label,
                )
            }
        }
        if (direction == UnicodeDirection.ENCODE) {
            Text(
                "输出格式",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UnicodeFormat.entries.forEach { item ->
                    SelectChip(
                        selected = format == item,
                        onClick = { format = item },
                        label = item.label,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ASCII 原样保留", style = MiuixTheme.textStyles.body2)
                    Text(
                        "开启时可打印 ASCII 保持可读，控制字符、反斜杠和可能被误认成转义的片段会转义；" +
                            "关闭后所有 ASCII 都写成转义形式",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Switch(checked = keepAscii, onCheckedChange = { keepAscii = it })
            }
        }
        conversion.error?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = colors.error)
        }
        ResultBox(
            text = conversion.output,
            label = if (direction == UnicodeDirection.ENCODE) "Unicode 转义" else "解码结果",
            monospace = true,
            minHeight = 240.dp,
        )
        if (conversion.output.isNotEmpty() && conversion.error == null) {
            SecondaryButton(
                onClick = {
                    input = conversion.output
                    direction = if (direction == UnicodeDirection.ENCODE) {
                        UnicodeDirection.DECODE
                    } else {
                        UnicodeDirection.ENCODE
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("交换输入 / 输出")
            }
        }
        Spacer(Modifier.height(4.dp))
        HintText(
            "解码会自动识别 \\uXXXX、\\u{...}、\\UXXXXXXXX、U+XXXX、&#xXXXX; 和 &#DDDD; 等写法，" +
                "也会把 \\\\ 还原成一个反斜杠；编码时换行、Tab 等控制字符也会转义。默认编码会保护原文里的反斜杠、U+ 和 &# 片段，" +
                "U+ 输出后面紧跟十六进制字符时也会用 \\uXXXX 隔开，编码后再解码可还原原文；" +
                "编码 emoji 等补充平面字符时，\\uXXXX 会输出 UTF-16 代理对，" +
                "U+ 和 HTML 会输出完整码点。",
        )
    }
}

private fun convertUnicode(
    input: String,
    direction: UnicodeDirection,
    format: UnicodeFormat,
    keepAscii: Boolean,
): UnicodeConversion {
    if (input.isEmpty()) return UnicodeConversion("", null)
    return try {
        val output = when (direction) {
            UnicodeDirection.ENCODE -> encodeUnicode(input, format, keepAscii)
            UnicodeDirection.DECODE -> decodeUnicode(input)
        }
        UnicodeConversion(output, null)
    } catch (e: Exception) {
        UnicodeConversion("", e.message ?: "转换失败")
    }
}

private fun encodeUnicode(
    text: String,
    format: UnicodeFormat,
    keepAscii: Boolean,
): String {
    val out = StringBuilder(text.length + 16)
    var index = 0
    // U+XXXX 没有结束符。如果后面紧跟一个可读的 ASCII 十六进制字符，解码器会把它
    // 当成码点的一部分（U+00E9b）。这时把这个字符改用固定 4 位的 \uXXXX 输出，
    // 既不用插入分隔符，也能保证往返一致。
    var previousWasCodePoint = false
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)
        val escape = !keepAscii || codePoint > 0x7F ||
            codePoint < 0x20 || codePoint == 0x7F || startsEscapeSyntax(text, index)
        val separatorEscape = format == UnicodeFormat.CODE_POINT &&
            previousWasCodePoint && !escape && isHexDigit(text[index])
        if (!escape && !separatorEscape) {
            out.append(text, index, index + width)
            previousWasCodePoint = false
        } else if (separatorEscape) {
            appendJavaEscape(out, codePoint)
            previousWasCodePoint = false
        } else if (format == UnicodeFormat.JAVA && keepAscii && text[index] == '\\') {
            out.append("\\\\")
            previousWasCodePoint = false
        } else {
            when (format) {
                UnicodeFormat.JAVA -> appendJavaEscape(out, codePoint)
                UnicodeFormat.CODE_POINT -> {
                    out.append("U+")
                    val hex = codePoint.toString(16).uppercase(Locale.US)
                    out.append(hex.padStart(if (codePoint <= 0xFFFF) 4 else 5, '0'))
                }
                UnicodeFormat.HTML -> {
                    out.append("&#x")
                    out.append(codePoint.toString(16).uppercase(Locale.US))
                    out.append(';')
                }
            }
            previousWasCodePoint = format == UnicodeFormat.CODE_POINT
        }
        index += width
    }
    return out.toString()
}

/**
 * 即使开着「ASCII 原样保留」，这几类片段也必须写成转义：
 * 反斜杠会和后面的转义合并，`U+XXXX` / `&#...;` 会被解码器当成码点。
 * 只转义这些会破坏往返的字符，其余 ASCII 仍然可读。
 */
private fun startsEscapeSyntax(text: String, index: Int): Boolean {
    return when (text[index]) {
        '\\' -> true
        'U', 'u' -> index + 1 < text.length && text[index + 1] == '+'
        '&' -> index + 1 < text.length && text[index + 1] == '#'
        else -> false
    }
}

private fun appendJavaEscape(out: StringBuilder, codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        out.append("\\u").append(
            codePoint.toString(16).uppercase(Locale.US).padStart(4, '0'),
        )
        return
    }
    val value = codePoint - 0x10000
    val high = 0xD800 + (value ushr 10)
    val low = 0xDC00 + (value and 0x3FF)
    out.append("\\u").append(high.toString(16).uppercase(Locale.US).padStart(4, '0'))
    out.append("\\u").append(low.toString(16).uppercase(Locale.US).padStart(4, '0'))
}

private fun decodeUnicode(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when {
            // 两个反斜杠加 u4F60 是对反斜杠的转义，解码后仍是字面量，而不是“你”
            ch == '\\' && index + 1 < text.length && text[index + 1] == '\\' -> {
                out.append('\\')
                index += 2
            }

            ch == '\\' && index + 1 < text.length &&
                (text[index + 1] == 'u' || text[index + 1] == 'U') -> {
                index = decodeSlashEscape(text, index, out)
            }

            (ch == 'U' || ch == 'u') && index + 1 < text.length && text[index + 1] == '+' &&
                hasAtLeastHexDigits(text, index + 2, 4) -> {
                val start = index
                var cursor = index + 2
                val digitsStart = cursor
                while (cursor < text.length && isHexDigit(text[cursor]) &&
                    cursor - digitsStart < 6
                ) {
                    cursor++
                }
                val digits = cursor - digitsStart
                if (cursor < text.length && isHexDigit(text[cursor])) {
                    throw UnicodeDecodeException(start, "U+ 码点最多 6 位十六进制数字")
                }
                val codePoint = text.substring(digitsStart, cursor).toInt(16)
                validateUnicodeScalar(start, codePoint)
                out.appendCodePoint(codePoint)
                index = cursor
            }

            ch == '&' && index + 2 < text.length && text[index + 1] == '#' &&
                htmlNumericEntityStarts(text, index) -> {
                val start = index
                if (text[index + 2] == 'x' || text[index + 2] == 'X') {
                    var cursor = index + 3
                    val digitsStart = cursor
                    while (cursor < text.length && isHexDigit(text[cursor]) &&
                        cursor - digitsStart < 6
                    ) {
                        cursor++
                    }
                    if (cursor >= text.length || text[cursor] != ';') {
                        throw UnicodeDecodeException(start, "HTML 十六进制实体需要以 ; 结尾")
                    }
                    val digits = cursor - digitsStart
                    if (digits < 1) {
                        throw UnicodeDecodeException(start, "HTML 十六进制实体缺少数字")
                    }
                    val codePoint = text.substring(digitsStart, cursor).toInt(16)
                    validateUnicodeScalar(start, codePoint)
                    out.appendCodePoint(codePoint)
                    index = cursor + 1
                } else {
                    var cursor = index + 2
                    val digitsStart = cursor
                    while (cursor < text.length && text[cursor] in '0'..'9' &&
                        cursor - digitsStart < 7
                    ) {
                        cursor++
                    }
                    if (cursor >= text.length || text[cursor] != ';') {
                        throw UnicodeDecodeException(start, "HTML 十进制实体需要以 ; 结尾")
                    }
                    val digits = text.substring(digitsStart, cursor)
                    if (digits.isEmpty()) {
                        throw UnicodeDecodeException(start, "HTML 十进制实体缺少数字")
                    }
                    val codePoint = digits.toLongOrNull()
                        ?: throw UnicodeDecodeException(start, "HTML 实体数字过大")
                    if (codePoint > 0x10FFFF) {
                        throw UnicodeDecodeException(start, "Unicode 码点超出 10FFFF")
                    }
                    validateUnicodeScalar(start, codePoint.toInt())
                    out.appendCodePoint(codePoint.toInt())
                    index = cursor + 1
                }
            }

            else -> {
                out.append(ch)
                index++
            }
        }
    }
    return out.toString()
}

/**
 * 解析 `\uXXXX`、`\u{...}` 以及 Python 风格的 `\UXXXXXXXX`。
 *
 * UTF-16 代理对（例如 emoji 由 D83D 和 DE00 两个码元组成）要合并成一个码点再输出；
 * 只出现一半时直接报错，否则结果里会留下无法编码成 UTF-8 的孤立代理项。
 */
private fun decodeSlashEscape(text: String, start: Int, out: StringBuilder): Int {
    var index = start + 2
    // \user、路径里的 \Users 之类并不是转义写法，原样保留反斜杠，不要把整段文本卡成错误。
    if (index >= text.length || (text[index] != '{' && !isHexDigit(text[index]))) {
        out.append('\\')
        return start + 1
    }
    if (text[index] == '{') {
        val close = text.indexOf('}', index + 1)
        if (close < 0) throw UnicodeDecodeException(start, "\\u{...} 缺少右花括号")
        val hex = text.substring(index + 1, close)
        if (hex.isEmpty() || hex.length > 6 || !hex.all(::isHexDigit)) {
            throw UnicodeDecodeException(start, "\\u{...} 里需要 1–6 位十六进制数字")
        }
        val codePoint = hex.toInt(16)
        validateUnicodeScalar(start, codePoint)
        out.appendCodePoint(codePoint)
        return close + 1
    }
    // 大写 U 后跟 8 位十六进制是 Python 的 \UXXXXXXXX 写法；不足 8 位时按普通 4 位转义处理。
    if (text[start + 1] == 'U' && index + 8 <= text.length) {
        val hex = text.substring(index, index + 8)
        if (hex.all(::isHexDigit)) {
            val codePoint = hex.toLong(16)
            if (codePoint > 0x10FFFF) {
                throw UnicodeDecodeException(start, "Unicode 码点超出 10FFFF")
            }
            validateUnicodeScalar(start, codePoint.toInt())
            out.appendCodePoint(codePoint.toInt())
            return index + 8
        }
    }
    if (index + 4 > text.length) {
        throw UnicodeDecodeException(start, "\\u 后需要 4 位十六进制数字")
    }
    val hex = text.substring(index, index + 4)
    if (!hex.all(::isHexDigit)) {
        throw UnicodeDecodeException(start, "\\u 后需要 4 位十六进制数字")
    }
    val unit = hex.toInt(16)
    if (unit in 0xD800..0xDBFF) {
        val lowStart = index + 4
        if (lowStart + 6 <= text.length &&
            text[lowStart] == '\\' &&
            (text[lowStart + 1] == 'u' || text[lowStart + 1] == 'U')
        ) {
            val lowHex = text.substring(lowStart + 2, lowStart + 6)
            if (lowHex.all(::isHexDigit)) {
                val low = lowHex.toInt(16)
                if (low in 0xDC00..0xDFFF) {
                    out.appendCodePoint(Character.toCodePoint(unit.toChar(), low.toChar()))
                    return lowStart + 6
                }
            }
        }
        throw UnicodeDecodeException(start, "孤立的高代理项")
    }
    if (unit in 0xDC00..0xDFFF) {
        throw UnicodeDecodeException(start, "孤立的低代理项")
    }
    out.append(unit.toChar())
    return index + 4
}

private fun hasAtLeastHexDigits(text: String, start: Int, count: Int): Boolean {
    if (start + count > text.length) return false
    for (offset in 0 until count) {
        if (!isHexDigit(text[start + offset])) return false
    }
    return true
}

/** 只有真的以 `x`+十六进制或十进制数字开头时，才把 `&#` 当成实体；否则是普通文本。 */
private fun htmlNumericEntityStarts(text: String, start: Int): Boolean {
    val marker = text[start + 2]
    return if (marker == 'x' || marker == 'X') {
        start + 3 < text.length && isHexDigit(text[start + 3])
    } else {
        marker in '0'..'9'
    }
}

private fun validateUnicodeScalar(position: Int, codePoint: Int) {
    if (codePoint !in 0..0x10FFFF) {
        throw UnicodeDecodeException(position, "Unicode 码点超出 10FFFF")
    }
    if (codePoint in 0xD800..0xDFFF) {
        throw UnicodeDecodeException(position, "代理区码点不能单独使用")
    }
}

private fun isHexDigit(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
