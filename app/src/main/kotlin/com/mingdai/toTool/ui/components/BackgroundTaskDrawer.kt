package com.mingdai.toTool.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mingdai.toTool.background.BackgroundTask
import com.mingdai.toTool.background.BackgroundTaskRegistry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主界面右侧的可收纳任务抽屉:有后台任务时贴边显示，展开后列出进度和停止按钮。 */
@Composable
fun BackgroundTaskDrawer(modifier: Modifier = Modifier) {
    val tasks by BackgroundTaskRegistry.tasks.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tasks.isEmpty()) {
        if (tasks.isEmpty()) open = false
    }
    if (tasks.isEmpty()) return

    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Card(
                modifier = Modifier
                    .width(300.dp)
                    .padding(end = 10.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("后台任务", style = MiuixTheme.textStyles.title4)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { open = false }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "收起",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        tasks.forEach { task ->
                            TaskRow(task)
                        }
                    }
                }
            }
        }
        if (!open) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(MiuixTheme.colorScheme.primary)
                    .clickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tasks.size.toString(),
                        style = MiuixTheme.textStyles.title4,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                    Text(
                        "任务",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: BackgroundTask) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            task.title,
            style = MiuixTheme.textStyles.body1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (task.detail.isNotBlank()) {
            Text(
                task.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (task.progress != null) {
            LinearProgressIndicator(
                progress = task.progress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (task.cancel != null) {
            SecondaryButton(
                onClick = { BackgroundTaskRegistry.cancel(task.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("停止") }
        }
    }
}
