package com.mingdai.toTool.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.countdown.CountdownDisplay
import com.mingdai.toTool.countdown.CountdownEvent
import com.mingdai.toTool.countdown.CountdownStore
import com.mingdai.toTool.countdown.CountdownWidgetProvider
import com.mingdai.toTool.ui.components.AppDatePickerDialog
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.displayStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CountdownDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")

@Composable
fun CountdownTool(padding: PaddingValues) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(CountdownStore.load(context)) }
    val today = LocalDate.now()
    val displays = remember(events, today) { CountdownStore.sorted(events, today) }

    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var repeatYearly by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        CountdownWidgetProvider.updateAll(context)
    }

    fun openEditor(event: CountdownEvent?) {
        editingId = event?.id
        title = event?.title.orEmpty()
        date = event?.date ?: LocalDate.now()
        repeatYearly = event?.repeatYearly ?: false
        editorError = null
        editorOpen = true
    }

    fun saveEvent() {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            editorError = "请输入倒数日名称"
            return
        }
        val id = editingId ?: ((events.maxOfOrNull { it.id } ?: 0L) + 1L)
        val event = CountdownEvent(id, trimmed, date, repeatYearly)
        events = if (editingId == null) {
            events + event
        } else {
            events.map { if (it.id == id) event else it }
        }
        CountdownStore.save(context, events)
        editorOpen = false
    }

    ToolBody(padding) {
        SectionLabel("倒数日")
        PrimaryButton(
            onClick = { openEditor(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Text("添加倒数日")
        }

        if (displays.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("还没有倒数日", style = MiuixTheme.textStyles.body1)
                    HintText("添加生日、纪念日或考试倒计时，点按卡片可以修改。")
                }
            }
        } else {
            displays.forEach { display ->
                CountdownCard(
                    display = display,
                    onEdit = { openEditor(display.event) },
                    onDelete = { deleteId = display.event.id },
                )
            }
        }

        HintText("小组件会显示最近的一条倒数日；长按桌面空白处，从「小部件」里添加「倒数日」。")
    }

    AppDialog(
        show = editorOpen,
        title = if (editingId == null) "添加倒数日" else "编辑倒数日",
        onDismiss = { editorOpen = false },
        confirmText = "保存",
        onConfirm = { saveEvent() },
    ) {
        TextField(
            value = title,
            onValueChange = {
                title = it.take(40)
                editorError = null
            },
            label = "名称",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDatePicker = true },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "日期",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(date.format(CountdownDateFormat), style = MiuixTheme.textStyles.body1)
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("每年重复", style = MiuixTheme.textStyles.body2)
                Text(
                    "生日、纪念日等会滚动到下一次日期",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(checked = repeatYearly, onCheckedChange = { repeatYearly = it })
        }
        editorError?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
    }

    AppDatePickerDialog(
        show = showDatePicker,
        initial = date,
        title = "选择日期",
        onDismiss = { showDatePicker = false },
        onConfirm = {
            date = it
            showDatePicker = false
        },
    )

    val deleting = events.firstOrNull { it.id == deleteId }
    AppDialog(
        show = deleteId != null,
        title = "删除倒数日",
        summary = deleting?.title,
        onDismiss = { deleteId = null },
        confirmText = "删除",
        onConfirm = {
            val id = deleteId
            deleteId = null
            if (id != null) {
                events = events.filterNot { it.id == id }
                CountdownStore.save(context, events)
            }
        },
    )
}

@Composable
private fun CountdownCard(
    display: CountdownDisplay,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    display.event.title,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(display.target.format(CountdownDateFormat))
                        if (display.event.repeatYearly) append(" · 每年")
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (display.days == 0L) {
                    Text("今天", style = displayStyle(28.sp), color = colors.primary)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            abs(display.days).toString(),
                            style = displayStyle(30.sp),
                            color = colors.primary,
                        )
                        Text(
                            if (display.days > 0) " 天后" else " 天前",
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.primary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = colors.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
