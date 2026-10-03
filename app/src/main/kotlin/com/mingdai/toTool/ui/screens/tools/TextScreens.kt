package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.ToolBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.net.URLEncoder
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mingdai.toTool.ui.components.AppCheckbox
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SelectChip

/* ------------------------------- 1. 字符统计 -------------------------------- */

private data class TextStats(
    val chars: Int,
    val noSpace: Int,
    val words: Int,
    val lines: Int,
    val bytes: Int,
)

private fun computeStats(s: String): TextStats {
    val noSpace = s.count { !it.isWhitespace() }
    val words = if (s.isBlank()) 0 else s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    val lines = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1
    return TextStats(s.length, noSpace, words, lines, s.toByteArray(Charsets.UTF_8).size)
}

@Composable
fun CharCountTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    val stats = remember(input) { computeStats(input) }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "输入文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        ResultBox(
            text = buildString {
                append("字符数（含空格）：${stats.chars}\n")
                append("字符数（不含空格）：${stats.noSpace}\n")
                append("单词数：${stats.words}\n")
                append("行数：${stats.lines}\n")
                append("字节数（UTF-8）：${stats.bytes}")
            },
            label = "统计结果",
        )
    }
}

/* ------------------------------- 2. 大小写转换 ------------------------------ */

private fun titleCase(s: String): String =
    s.trim().split(Regex("\\s+")).joinToString(" ") { w ->
        val lw = w.lowercase()
        if (lw.isEmpty()) "" else lw.substring(0, 1).uppercase() + lw.substring(1)
    }

private fun sentenceCase(s: String): String {
    val sb = StringBuilder(s.length)
    var cap = true
    for (c in s) {
        if (cap && c.isLetter()) {
            sb.append(c.uppercaseChar())
            cap = false
        } else {
            sb.append(c.lowercaseChar())
        }
        if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') cap = true
    }
    return sb.toString()
}

@Composable
fun CaseTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PrimaryButton(onClick = { output = input.uppercase() }) { Text("大写") }
            PrimaryButton(onClick = { output = input.lowercase() }) { Text("小写") }
            PrimaryButton(onClick = { output = titleCase(input) }) { Text("标题") }
            PrimaryButton(onClick = { output = sentenceCase(input) }) { Text("句首") }
            PrimaryButton(onClick = { output = input.reversed() }) { Text("反转") }
        }
        ResultBox(output, label = "结果")
    }
}

/* ------------------------------- 3. 密码生成 -------------------------------- */

private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"

private fun genPassword(length: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean): String {
    val rng = SecureRandom()
    val up = ('A'..'Z').toList()
    val lo = ('a'..'z').toList()
    val di = ('0'..'9').toList()
    val sy = SYMBOLS.toList()
    val required = mutableListOf<Char>()
    val pool = mutableListOf<Char>()
    if (upper) { pool.addAll(up); required.add(up[rng.nextInt(up.size)]) }
    if (lower) { pool.addAll(lo); required.add(lo[rng.nextInt(lo.size)]) }
    if (digits) { pool.addAll(di); required.add(di[rng.nextInt(di.size)]) }
    if (symbols) { pool.addAll(sy); required.add(sy[rng.nextInt(sy.size)]) }
    if (pool.isEmpty()) pool.addAll(lo) // always have something
    val n = length.coerceAtLeast(required.size)
    while (required.size < n) required.add(pool[rng.nextInt(pool.size)])
    Collections.shuffle(required, rng)
    return String(required.toCharArray())
}

private fun passwordStrength(length: Int, sets: Int): String = when {
    length >= 14 && sets >= 3 -> "强"
    length >= 8 && sets >= 2 -> "中"
    else -> "弱"
}

@Composable
fun PasswordTool(padding: PaddingValues) {
    var length by remember { mutableStateOf(16) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var output by remember { mutableStateOf("") }
    val setCount = remember(upper, lower, digits, symbols) {
        listOf(upper, lower, digits, symbols).count { it }
    }
    ToolBody(padding) {
        Text("长度：$length 位", style = MiuixTheme.textStyles.body2)
        Slider(
            value = length.toFloat(),
            onValueChange = { length = it.toInt().coerceIn(4, 32) },
            valueRange = 4f..32f,
            steps = 27,
        )
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AppCheckbox(checked = upper, onCheckedChange = { upper = it })
                Text("大写字母 A-Z")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AppCheckbox(checked = lower, onCheckedChange = { lower = it })
                Text("小写字母 a-z")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AppCheckbox(checked = digits, onCheckedChange = { digits = it })
                Text("数字 0-9")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AppCheckbox(checked = symbols, onCheckedChange = { symbols = it })
                Text("特殊符号")
            }
        }
        PrimaryButton(onClick = { output = genPassword(length, upper, lower, digits, symbols) }) {
            Text("生成密码")
        }
        ResultBox(output, label = "密码", monospace = true)
        Text(
            "强度：${passwordStrength(length, setCount)}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/* ------------------------------- 4. UUID 生成 ------------------------------- */

@Composable
fun UuidTool(padding: PaddingValues) {
    var output by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var uppercase by remember { mutableStateOf(false) }
    ToolBody(padding) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PrimaryButton(onClick = { output = UUID.randomUUID().toString() }) { Text("生成新 UUID") }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp),
            ) {
                AppCheckbox(checked = uppercase, onCheckedChange = { uppercase = it })
                Text("大写")
            }
        }
        ResultBox(
            text = if (uppercase) output.uppercase() else output,
            label = "UUID v4",
            monospace = true,
        )
    }
}

/* ------------------------------- 5. Base64 编解码 --------------------------- */

@Composable
fun Base64Tool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(onClick = {
                output = Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
                error = null
            }) { Text("编码") }
            PrimaryButton(onClick = {
                try {
                    output = String(Base64.getDecoder().decode(input.trim()), Charsets.UTF_8)
                    error = null
                } catch (e: IllegalArgumentException) {
                    error = "解码失败：输入不是有效的 Base64"
                }
            }) { Text("解码") }
        }
        error?.let {
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        }
        ResultBox(output, label = "结果", monospace = true)
    }
}

/* ------------------------------- 6. 哈希计算 -------------------------------- */

private fun hashHex(input: String, algo: String): String {
    val md = MessageDigest.getInstance(algo)
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

@Composable
fun HashTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var algo by remember { mutableStateOf("SHA-256") }
    val algos = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")
    val output = remember(input, algo) { if (input.isEmpty()) "" else hashHex(input, algo) }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "待哈希文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            algos.forEach { a ->
                SelectChip(
                    selected = algo == a,
                    onClick = { algo = a },
                    label = a,
                )
            }
        }
        ResultBox(output, label = algo, monospace = true)
    }
}

/* ------------------------------- 7. JSON 格式化 ----------------------------- */

private fun prettyJson(input: String): String {
    val s = input.trim()
    return if (s.startsWith("[")) JSONArray(s).toString(2) else JSONObject(s).toString(2)
}

private fun minifyJson(input: String): String {
    val s = input.trim()
    return if (s.startsWith("[")) JSONArray(s).toString() else JSONObject(s).toString()
}

@Composable
fun JsonTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "JSON 文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(onClick = {
                try {
                    output = prettyJson(input)
                    error = null
                } catch (e: JSONException) {
                    error = "解析失败：${e.message}"
                }
            }) { Text("格式化") }
            PrimaryButton(onClick = {
                try {
                    output = minifyJson(input)
                    error = null
                } catch (e: JSONException) {
                    error = "解析失败：${e.message}"
                }
            }) { Text("压缩") }
        }
        error?.let {
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        }
        ResultBox(output, label = "结果", monospace = true)
    }
}

/* ------------------------------- 8. URL 编解码 ------------------------------ */

@Composable
fun UrlTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "文本",
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(onClick = {
                output = URLEncoder.encode(input, "UTF-8")
                error = null
            }) { Text("编码") }
            PrimaryButton(onClick = {
                try {
                    output = URLDecoder.decode(input, "UTF-8")
                    error = null
                } catch (e: IllegalArgumentException) {
                    error = "解码失败：输入包含非法的转义序列"
                }
            }) { Text("解码") }
        }
        error?.let {
            Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        }
        ResultBox(output, label = "结果", monospace = true)
    }
}
