package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.ui.components.ResultBox
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.data.LocalUnitLabelState
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.mingdai.toTool.ui.components.AppDatePickerDialog
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SelectChip

/* ------------------------------- 9. 单位换算 -------------------------------- */

private data class UnitDef(
    val label: String,
    val abbr: String,
    val toBase: (Double) -> Double,
    val fromBase: (Double) -> Double,
)

private data class UnitGroup(val name: String, val units: List<UnitDef>)

private fun linear(label: String, abbr: String, factor: Double) =
    UnitDef(label, abbr, { it * factor }, { it / factor })

private val unitGroups: List<UnitGroup> = listOf(
    UnitGroup("长度", listOf(
        linear("毫米", "mm", 0.001), linear("厘米", "cm", 0.01), linear("米", "m", 1.0), linear("千米", "km", 1000.0),
        linear("英寸", "in", 0.0254), linear("英尺", "ft", 0.3048), linear("英里", "mi", 1609.344), linear("海里", "nmi", 1852.0),
    )),
    UnitGroup("重量", listOf(
        linear("毫克", "mg", 1e-6), linear("克", "g", 0.001), linear("千克", "kg", 1.0), linear("吨", "t", 1000.0),
        linear("磅", "lb", 0.45359237), linear("盎司", "oz", 0.0283495),
    )),
    UnitGroup("温度", listOf(
        UnitDef("摄氏度 ℃", "°C", { it }, { it }),
        UnitDef("华氏度 ℉", "°F", { (it - 32) * 5.0 / 9.0 }, { it * 9.0 / 5.0 + 32 }),
        UnitDef("开尔文 K", "K", { it - 273.15 }, { it + 273.15 }),
    )),
    UnitGroup("面积", listOf(
        linear("平方毫米", "mm²", 1e-6), linear("平方厘米", "cm²", 1e-4), linear("平方米", "m²", 1.0), linear("公顷", "ha", 10000.0),
        linear("平方千米", "km²", 1e6), linear("平方英尺", "ft²", 0.09290304), linear("英亩", "acre", 4046.8564224), linear("亩", "mu", 666.6666667),
    )),
    UnitGroup("体积", listOf(
        linear("毫升", "mL", 0.001), linear("升", "L", 1.0), linear("立方米", "m³", 1000.0),
        linear("立方英尺", "ft³", 28.3168466), linear("加仑(美)", "gal", 3.7854118),
    )),
    UnitGroup("速度", listOf(
        linear("米/秒", "m/s", 1.0), linear("千米/时", "km/h", 0.2777778), linear("英里/时", "mph", 0.44704), linear("节", "kn", 0.5144444),
    )),
    UnitGroup("数据", listOf(
        linear("字节 B", "B", 1.0), linear("KB", "KB", 1024.0), linear("MB", "MB", 1048576.0),
        linear("GB", "GB", 1073741824.0), linear("TB", "TB", 1099511627776.0),
    )),
)

/** Drop trailing zeros from a fixed-precision decimal string. */
private fun stripTrailing(s: String): String =
    if (s.contains('.')) s.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
    else s

@Composable
private fun UnitSpinner(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = { showDialog = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(options.getOrElse(selected) { "" }, style = MiuixTheme.textStyles.body1)
            }
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    AppDialog(
        show = showDialog,
        title = label,
        onDismiss = { showDialog = false },
        confirmText = null,
        dismissText = "取消",
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEachIndexed { i, opt ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(i); showDialog = false }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        opt,
                        style = MiuixTheme.textStyles.body1,
                        color = if (i == selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun UnitConverterTool(padding: PaddingValues) {
    var groupIndex by remember { mutableStateOf(0) }
    var fromIndex by remember { mutableStateOf(0) }
    var toIndex by remember { mutableStateOf(1) }
    var input by remember { mutableStateOf("1") }

    val unitLabel = LocalUnitLabelState.current
    val group = unitGroups[groupIndex]
    val options = group.units.map { if (unitLabel.useAbbr) it.abbr else it.label }
    val result = remember(input, fromIndex, toIndex, groupIndex) {
        val v = input.trim().toDoubleOrNull()
        if (v == null) "" else {
            val base = group.units[fromIndex].toBase(v)
            val out = group.units[toIndex].fromBase(base)
            stripTrailing(String.format(Locale.US, "%.6f", out))
        }
    }

    ToolBody(padding) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            unitGroups.forEachIndexed { i, g ->
                SelectChip(
                    selected = groupIndex == i,
                    onClick = {
                        groupIndex = i
                        fromIndex = 0
                        toIndex = if (unitGroups[i].units.size > 1) 1 else 0
                    },
                    label = g.name,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnitSpinner("从", options, fromIndex, { fromIndex = it }, modifier = Modifier.weight(1f))
            IconButton(onClick = { val t = fromIndex; fromIndex = toIndex; toIndex = t }) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "交换")
            }
            UnitSpinner("到", options, toIndex, { toIndex = it }, modifier = Modifier.weight(1f))
        }
        TextField(
            value = input,
            onValueChange = { input = it },
            label = "数值",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        ResultBox(result, label = "结果")
    }
}

/* ------------------------------- 10. 进制转换 ------------------------------- */

@Composable
fun BaseConverterTool(padding: PaddingValues) {
    var input by remember { mutableStateOf("") }
    var radix by remember { mutableStateOf(10) }
    val radices = listOf(2 to "二进制", 8 to "八进制", 10 to "十进制", 16 to "十六进制")
    val parsed = remember(input, radix) {
        val s = input.trim()
        if (s.isEmpty()) null else runCatching { BigInteger(s, radix) }.getOrNull()
    }
    ToolBody(padding) {
        TextField(
            value = input,
            onValueChange = { input = it.uppercase() },
            label = "输入数字",
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (radix == 16) KeyboardType.Ascii else KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            radices.forEach { (r, name) ->
                SelectChip(
                    selected = radix == r,
                    onClick = { radix = r },
                    label = name,
                )
            }
        }
        if (parsed == null) {
            Text(
                if (input.isBlank()) "请输入数字" else "输入与所选进制不匹配",
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote1,
            )
        } else {
            ResultBox(parsed.toString(2), label = "二进制", monospace = true)
            ResultBox(parsed.toString(8), label = "八进制", monospace = true)
            ResultBox(parsed.toString(10), label = "十进制", monospace = true)
            ResultBox(parsed.toString(16).uppercase(), label = "十六进制", monospace = true)
        }
    }
}

/* ------------------------------- 11. 百分比计算 ----------------------------- */

@Composable
fun PercentTool(padding: PaddingValues) {
    var mode by remember { mutableStateOf(0) }
    val modes = listOf("X 的 Y% 是多少", "X 是 Y 的百分之几", "从 X 到 Y 增减率")
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val result = remember(a, b, mode) {
        val x = a.trim().toDoubleOrNull()
        val y = b.trim().toDoubleOrNull()
        if (x == null || y == null) "" else when (mode) {
            0 -> stripTrailing(String.format(Locale.US, "%.4f", x * y / 100.0))
            1 -> if (y == 0.0) "除数不能为 0" else String.format(Locale.US, "%.4f%%", x / y * 100.0)
            2 -> if (x == 0.0) "除数不能为 0" else String.format(Locale.US, "%+.4f%%", (y - x) / x * 100.0)
            else -> ""
        }
    }
    ToolBody(padding) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEachIndexed { i, m ->
                SelectChip(selected = mode == i, onClick = { mode = i }, label = m)
            }
        }
        TextField(
            value = a,
            onValueChange = { a = it },
            label = "X",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = b,
            onValueChange = { b = it },
            label = "Y",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        ResultBox(result, label = modes[mode])
    }
}

/* ------------------------------- 12. 日期计算 ------------------------------- */

@Composable
private fun DateField(label: String, date: LocalDate?, onPick: (LocalDate) -> Unit) {
    var show by remember { mutableStateOf(false) }
    SecondaryButton(
        onClick = { show = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(date?.format(DateTimeFormatter.ISO_LOCAL_DATE) ?: "选择 $label")
    }
    AppDatePickerDialog(
        show = show,
        initial = date ?: LocalDate.now(),
        title = label,
        onDismiss = { show = false },
        onConfirm = { onPick(it); show = false },
    )
}

@Composable
fun DateTool(padding: PaddingValues) {
    var mode by remember { mutableStateOf(0) } // 0 = 作差, 1 = 目标日
    var d1 by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var d2 by remember { mutableStateOf<LocalDate?>(LocalDate.now().plusMonths(1)) }
    var days by remember { mutableStateOf("30") }
    var base by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    ToolBody(padding) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(selected = mode == 0, onClick = { mode = 0 }, label = "作差")
            SelectChip(selected = mode == 1, onClick = { mode = 1 }, label = "目标日")
        }
        if (mode == 0) {
            DateField("起始日期", d1) { d1 = it }
            DateField("结束日期", d2) { d2 = it }
            val result = if (d1 != null && d2 != null) {
                val diff = ChronoUnit.DAYS.between(d1, d2)
                "相差 ${abs(diff)} 天" + if (diff < 0) "（结束早于起始）" else ""
            } else "请选择两个日期"
            ResultBox(result, label = "结果")
        } else {
            DateField("基准日期", base) { base = it }
            TextField(
                value = days,
                onValueChange = { days = it },
                label = "增加/减少天数（可为负）",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            val n = days.trim().toIntOrNull()
            val result = if (base != null && n != null) {
                base!!.plusDays(n.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            } else {
                "请输入有效的天数"
            }
            ResultBox(result, label = "目标日")
        }
    }
}

/* ------------------------------- 13. 贷款计算 -------------------------------- */

@Composable
fun LoanTool(padding: PaddingValues) {
    var amount by remember { mutableStateOf("300000") }
    var rate by remember { mutableStateOf("4.9") }
    var years by remember { mutableStateOf("30") }
    val result = remember(amount, rate, years) {
        val p = amount.trim().toDoubleOrNull()
        val annual = rate.trim().toDoubleOrNull()
        val y = years.trim().toIntOrNull()
        if (p == null || annual == null || y == null) null else {
            val n = y * 12
            if (n <= 0) null else {
                val r = annual / 100.0 / 12.0
                val factor = (1.0 + r).pow(n.toDouble())
                val monthly = if (r == 0.0) p / n else p * r * factor / (factor - 1.0)
                val total = monthly * n
                listOf(
                    "月供" to "¥ " + String.format(Locale.US, "%.2f", monthly),
                    "总利息" to "¥ " + String.format(Locale.US, "%.2f", total - p),
                    "总还款" to "¥ " + String.format(Locale.US, "%.2f", total),
                    "还款期数" to "$n 期（$y 年）",
                )
            }
        }
    }
    ToolBody(padding) {
        TextField(
            value = amount,
            onValueChange = { amount = it },
            label = "贷款金额（元）",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = rate,
            onValueChange = { rate = it },
            label = "年利率（%）",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = years,
            onValueChange = { years = it },
            label = "期限（年）",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (result == null) {
            Text("请输入有效的数值", color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
        } else {
            result.forEach { (k, v) -> ResultBox(v, label = k) }
        }
    }
}
