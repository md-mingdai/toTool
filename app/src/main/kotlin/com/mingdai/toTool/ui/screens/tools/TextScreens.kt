package com.mingdai.toTool.ui.screens.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.ToolBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.net.URLEncoder
import java.net.URLDecoder
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.displayStyle
import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Switch

/**
 * 文字类工具的输入框 / 输出框尺寸。比默认大得多：贴一大段 JSON 或 URL 进来不用一直滚动，
 * 输出框也和输入框一样宽敞，而不是缩成一小条。
 */
private const val BigInputLines = 9
private const val MidInputLines = 6
private val BigOutputHeight = 240.dp
private val MidOutputHeight = 160.dp

/* ------------------------------- 1. 字符统计 -------------------------------- */

private data class TextStats(
    val chars: Int,
    val noSpace: Int,
    val words: Int,
    val lines: Int,
    val bytes: Int,
)

private fun isAsciiWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/**
 * 单次遍历统计，不再为每个单词、每个字节分配对象。
 * 原来用 `split(Regex)` 把所有单词放进 List、再 `toByteArray` 拷一份，文本一大每敲一个字都要分配几十上百 MB。
 * 结果与旧实现逐项一致（已用 6 万个随机字符串对比验证，含代理对、Unicode 空白）。
 */
private fun computeStats(s: String): TextStats {
    val n = s.length
    var noSpace = 0
    var newlines = 0
    var bytes = 0L
    var first = -1
    var last = -1
    var i = 0
    while (i < n) {
        val c = s[i]
        if (!c.isWhitespace()) {
            noSpace++
            if (first < 0) first = i
            last = i
        }
        if (c == '\n') newlines++
        // UTF-8 字节数（与 String.toByteArray(UTF_8) 一致，但不分配数组）
        val code = c.code
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            // 代理对在高位代理处记 4 字节、低位代理处记 0；落单的代理项会被编码成 '?'（1 字节）。
            // 不能在这里跳过低位代理，否则它不会被计入“非空白字符数”。
            Character.isHighSurrogate(c) ->
                if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) 4 else 1
            Character.isLowSurrogate(c) ->
                if (i > 0 && Character.isHighSurrogate(s[i - 1])) 0 else 1
            else -> 3
        }
        i++
    }
    // 词数 = 去掉首尾（Unicode）空白后，按 ASCII 空白切分得到的非空段数
    var words = 0
    if (first >= 0) {
        var inWord = false
        for (k in first..last) {
            if (isAsciiWs(s[k])) inWord = false
            else if (!inWord) {
                words++
                inWord = true
            }
        }
    }
    return TextStats(n, noSpace, words, if (n == 0) 0 else newlines + 1, bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
            minLines = MidInputLines,
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
            minHeight = 150.dp,
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
            minLines = MidInputLines,
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
        ResultBox(output, label = "结果", minHeight = MidOutputHeight)
    }
}

/* ------------------------------- 3. 密码生成 -------------------------------- */

private const val UPPERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val LOWERS = "abcdefghijklmnopqrstuvwxyz"
private const val DIGITS = "0123456789"
private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"

/**
 * 生成密码。采用“拒绝采样”：每一位都从整个字符池里等概率抽取，
 * 如果某个勾选的类别一个都没抽到，就整个重来。
 * 这样结果在所有“每类至少出现一次”的密码上是完全均匀的，下面的熵值公式才是准确的。
 */
private fun genPassword(
    length: Int,
    sets: List<String>,
    rng: SecureRandom = SecureRandom(),
): String {
    val pool = sets.joinToString("")
    if (pool.isEmpty()) return ""
    val len = maxOf(length, sets.size)
    val chars = CharArray(len)
    repeat(10_000) {
        for (i in 0 until len) chars[i] = pool[rng.nextInt(pool.length)]
        if (sets.all { set -> chars.any { c -> set.indexOf(c) >= 0 } }) return String(chars)
    }
    // 概率上不可能走到这里（每一轮被接受的概率都不低于 7%）；兜底：直接给前几位指定类别
    sets.forEachIndexed { i, set -> chars[i] = set[rng.nextInt(set.length)] }
    return String(chars)
}

/**
 * 熵值（比特）：H = log₂(合法密码的总数)。
 * 合法 = 长度为 L、字符取自 N 个字符的池、每个勾选的类别至少出现一次。
 * 总数用容斥原理：Σ over 子集 S 的 (-1)^|S| · (N − 被排除类别的字符数)^L。
 * 只勾一类时就是熟悉的 H = L × log₂N。
 */
private fun passwordEntropyBits(length: Int, sizes: List<Int>): Double {
    val total = sizes.sum()
    if (length <= 0 || total <= 0) return 0.0
    var valid = 0.0
    for (mask in 0 until (1 shl sizes.size)) {
        var excluded = 0
        var picked = 0
        for (i in sizes.indices) {
            if (mask and (1 shl i) != 0) {
                excluded += sizes[i]
                picked++
            }
        }
        val term = (total - excluded).toDouble().pow(length)
        valid += if (picked % 2 == 0) term else -term
    }
    return if (valid <= 0.0) 0.0 else log2(valid)
}

/** 强度档位：0 极弱 · 1 弱 · 2 一般 · 3 强 · 4 极强（常见的熵值分档）。 */
private fun strengthIndex(bits: Double): Int = when {
    bits < 28.0 -> 0
    bits < 36.0 -> 1
    bits < 60.0 -> 2
    bits < 80.0 -> 3
    else -> 4
}

private val StrengthLabels = listOf("极弱", "弱", "一般", "强", "极强")
private val StrengthBad = Color(0xFFE5484D)
private val StrengthGood = Color(0xFF2EAD4F)

/** 数值显示：10 以上取整，10 以下留一位小数（1.8 年、54 秒）。 */
private fun roughNumber(v: Double): String =
    if (v >= 10.0) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

/** 暴力穷举的平均耗时（假设每秒 100 亿次猜测，相当于离线破解快速哈希的量级）。 */
private fun crackTimeLabel(bits: Double): String {
    val seconds = 2.0.pow(bits - 1.0) / 1e10 // 平均要试掉一半的空间
    val minute = 60.0
    val hour = 3600.0
    val day = 86400.0
    val year = 31_557_600.0
    return when {
        seconds < 1.0 -> "不到 1 秒"
        seconds < minute -> "约 ${roughNumber(seconds)} 秒"
        seconds < hour -> "约 ${roughNumber(seconds / minute)} 分钟"
        seconds < day -> "约 ${roughNumber(seconds / hour)} 小时"
        seconds < year -> "约 ${roughNumber(seconds / day)} 天"
        seconds < year * 1e4 -> "约 ${roughNumber(seconds / year)} 年"
        seconds < year * 1e8 -> "约 ${roughNumber(seconds / year / 1e4)} 万年"
        seconds < year * 1.38e10 -> "约 ${roughNumber(seconds / year / 1e8)} 亿年"
        else -> "远超宇宙的年龄（约 138 亿年）"
    }
}

@Composable
private fun CharSetRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun EntropyCard(bits: Double, length: Int, poolSize: Int) {
    val colors = MiuixTheme.colorScheme
    val level = strengthIndex(bits)
    val tint = when (level) {
        0, 1 -> StrengthBad
        2 -> WarnColor
        3 -> StrengthGood
        else -> colors.primary
    }
    val basic = length * log2(poolSize.toDouble())
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%.1f", bits), style = displayStyle(38.sp), color = tint)
                Text(
                    " bit",
                    style = MiuixTheme.textStyles.headline2,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    StrengthLabels[level],
                    style = MiuixTheme.textStyles.headline2,
                    color = tint,
                    modifier = Modifier
                        .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            LinearProgressIndicator(
                progress = (bits / 128.0).toFloat().coerceIn(0f, 1f),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = tint),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "熵值 H = log₂(合法密码的总数)。字符池 N = $poolSize，长度 L = $length，" +
                    "基础公式 L × log₂N = ${String.format(Locale.US, "%.1f", basic)} bit；" +
                    "再扣除“每类至少出现一次”带来的限制，就是上面的精确值。",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            Text(
                "暴力穷举平均耗时：${crackTimeLabel(bits)}（按每秒 100 亿次猜测估算）",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun PasswordTool(padding: PaddingValues) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var length by remember { mutableStateOf(16) }
    var upper by remember { mutableStateOf(true) }
    var lower by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }

    val sets = remember(upper, lower, digits, symbols) {
        buildList {
            if (upper) add(UPPERS)
            if (lower) add(LOWERS)
            if (digits) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }
    }
    val poolSize = sets.sumOf { it.length }
    val bits = remember(length, sets) { passwordEntropyBits(length, sets.map { it.length }) }

    val entropySources = remember(context) { PasswordEntropy.sources(context) }
    var entropyTab by rememberSaveable { mutableStateOf(PasswordEntropyTab.SENSORS) }
    var selectedSourceIds by rememberSaveable {
        mutableStateOf(entropySources.filter { it.sensorType != null }.map { it.id })
    }
    val drawPoints = remember { mutableStateListOf<PasswordDrawPoint?>() }
    val drawCount = drawPoints.count { it != null }
    var collecting by remember { mutableStateOf(false) }
    var entropyNote by remember { mutableStateOf<String?>(null) }
    var entropyError by remember { mutableStateOf<String?>(null) }
    var pendingEntropyGenerate by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 选项一变就重新生成，这样下面显示的密码、熵值永远对应当前的设置；按钮用来“再来一个”
    var output by remember { mutableStateOf("") }
    LaunchedEffect(length, sets) {
        output = genPassword(length, sets)
        entropyNote = null
        entropyError = null
    }

    fun startEntropyGeneration(selected: List<PasswordEntropySource>) {
        if (collecting) return
        if (entropyTab == PasswordEntropyTab.DRAW) {
            if (drawCount < 500) {
                entropyNote = null
                entropyError = "还需要采集 ${500 - drawCount} 个绘制点"
                return
            }
            val rng = PasswordEntropy.mix(listOf(PasswordEntropy.hashDrawing(drawPoints)))
            output = genPassword(length, sets, rng)
            entropyError = null
            entropyNote = "已混入绘制坐标熵源；原始笔迹不会保存。"
            return
        }
        if (selected.isEmpty()) {
            output = genPassword(length, sets)
            entropyError = null
            entropyNote = "未选择传感器，使用系统安全随机数。"
            return
        }
        collecting = true
        scope.launch {
            entropyError = null
            entropyNote = null
            try {
                val collection = PasswordEntropy.collect(context, lifecycleOwner, selected)
                val rng = PasswordEntropy.mix(collection.parts)
                output = genPassword(length, sets, rng)
                entropyNote = buildString {
                    if (collection.parts.isEmpty()) {
                        append("未能采集到额外熵源，已使用系统安全随机数生成。")
                    } else {
                        append("已混入熵源：")
                        append(selected.joinToString("、") { it.label })
                        append("。")
                    }
                    append("原始数据不会保存")
                    if (collection.notes.isNotEmpty()) {
                        append("；")
                        append(collection.notes.joinToString("；"))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                entropyError = "采集熵源失败：${e.message ?: "未知错误"}"
            } finally {
                collecting = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val pending = pendingEntropyGenerate
        pendingEntropyGenerate = null
        val required = entropySources.filter { it.id in selectedSourceIds }
            .mapNotNull { it.permission }
            .distinct()
        val allGranted = required.all { PasswordEntropy.hasPermission(context, it) }
        if (pending != null && allGranted) {
            pending()
        } else {
            entropyNote = null
            entropyError = "需要麦克风或摄像头权限才能使用所选熵源"
        }
    }

    fun generateWithEntropy() {
        if (collecting || pendingEntropyGenerate != null) return
        if (entropyTab == PasswordEntropyTab.DRAW) {
            startEntropyGeneration(emptyList())
            return
        }
        val selected = entropySources.filter { it.id in selectedSourceIds }
        val missing = selected.mapNotNull { it.permission }.distinct()
            .filterNot { PasswordEntropy.hasPermission(context, it) }
        if (missing.isEmpty()) {
            startEntropyGeneration(selected)
        } else {
            pendingEntropyGenerate = { startEntropyGeneration(selected) }
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val drawReady = drawCount >= 500
    val canGenerate = !collecting && (entropyTab != PasswordEntropyTab.DRAW || drawReady)
    val generateLabel = when {
        collecting -> "正在采集熵源…"
        entropyTab == PasswordEntropyTab.DRAW && !drawReady -> "再画一些点（$drawCount/500）"
        entropyTab == PasswordEntropyTab.DRAW -> "使用绘制熵源生成"
        selectedSourceIds.isNotEmpty() -> "采集熵源并生成"
        else -> "重新生成"
    }

    ToolBody(padding) {
        SectionLabel("长度")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("密码长度", style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                    Text("$length 位", style = MiuixTheme.textStyles.headline2, color = colors.primary)
                }
                Spacer(Modifier.height(6.dp))
                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.toInt().coerceIn(4, 32) },
                    valueRange = 4f..32f,
                    steps = 27,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("字符类型")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                // 至少保留一类：只剩一类时，它的开关不让关
                val canTurnOff = sets.size > 1
                CharSetRow("大写字母", "A–Z · ${UPPERS.length} 个字符", upper, enabled = !upper || canTurnOff) { upper = it }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
                CharSetRow("小写字母", "a–z · ${LOWERS.length} 个字符", lower, enabled = !lower || canTurnOff) { lower = it }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
                CharSetRow("数字", "0–9 · ${DIGITS.length} 个字符", digits, enabled = !digits || canTurnOff) { digits = it }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
                CharSetRow("特殊符号", "$SYMBOLS · ${SYMBOLS.length} 个字符", symbols, enabled = !symbols || canTurnOff) { symbols = it }
            }
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("熵源选择")
        PasswordEntropySection(
            tab = entropyTab,
            onTabChange = {
                entropyTab = it
                entropyError = null
            },
            sources = entropySources,
            selectedIds = selectedSourceIds.toSet(),
            onToggleSource = { id ->
                selectedSourceIds = if (id in selectedSourceIds) {
                    selectedSourceIds - id
                } else {
                    selectedSourceIds + id
                }
            },
            drawPoints = drawPoints,
            drawCount = drawCount,
            collecting = collecting,
            onStrokeStart = { offset ->
                if (drawPoints.count { it != null } < 600) {
                    drawPoints.add(null)
                    drawPoints.add(PasswordDrawPoint(offset.x, offset.y, System.nanoTime()))
                }
            },
            onStrokePoint = { offset ->
                if (drawPoints.count { it != null } < 600) {
                    drawPoints.add(PasswordDrawPoint(offset.x, offset.y, System.nanoTime()))
                }
            },
            onClearDrawing = { drawPoints.clear() },
        )

        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            onClick = { generateWithEntropy() },
            enabled = canGenerate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(generateLabel)
        }
        entropyError?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = StrengthBad)
        }
        entropyNote?.let { HintText(it) }
        ResultBox(output, label = "密码", monospace = true, minHeight = 72.dp)

        SectionLabel("强度评估")
        EntropyCard(bits = bits, length = length, poolSize = poolSize)
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
            minLines = MidInputLines,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
                output = Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
                error = null
            }) { Text("编码") }
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
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
        ResultBox(output, label = "结果", monospace = true, minHeight = MidOutputHeight)
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
            minLines = MidInputLines,
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
        ResultBox(output, label = algo, monospace = true, minHeight = 120.dp)
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
            minLines = BigInputLines,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
                try {
                    output = prettyJson(input)
                    error = null
                } catch (e: JSONException) {
                    error = "解析失败：${e.message}"
                }
            }) { Text("格式化") }
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
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
        ResultBox(output, label = "结果", monospace = true, minHeight = BigOutputHeight)
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
            minLines = BigInputLines,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
                output = URLEncoder.encode(input, "UTF-8")
                error = null
            }) { Text("编码") }
            PrimaryButton(modifier = Modifier.weight(1f), onClick = {
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
        ResultBox(output, label = "结果", monospace = true, minHeight = BigOutputHeight)
    }
}
