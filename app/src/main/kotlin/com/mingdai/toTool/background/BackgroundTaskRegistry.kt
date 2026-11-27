package com.mingdai.toTool.background

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class BackgroundTask(
    val id: String,
    val toolId: String,
    val title: String,
    val detail: String = "",
    val progress: Float? = null,
    val keepAlive: Boolean = true,
    val cancel: (() -> Unit)? = null,
)

/**
 * 后台任务登记表 + 应用级协程。
 * FFmpeg / 隔空闪传等任务在这里运行，离开页面不会随 Compose 作用域取消；
 * 只要还有需要保活的任务，[BackgroundTaskService] 就以前台服务形式维持进程。
 */
object BackgroundTaskRegistry {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val counter = AtomicLong()
    private val _tasks = MutableStateFlow<List<BackgroundTask>>(emptyList())
    val tasks: StateFlow<List<BackgroundTask>> = _tasks.asStateFlow()
    private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val completions: SharedFlow<String> = _completions.asSharedFlow()

    fun newId(prefix: String): String = "${prefix}_${counter.incrementAndGet()}"

    fun add(context: Context, task: BackgroundTask, keepAlive: Boolean = true) {
        synchronized(this) {
            _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
        }
        if (keepAlive) {
            runCatching { BackgroundTaskService.start(context) }
        }
    }

    fun update(id: String, detail: String, progress: Float? = null) {
        synchronized(this) {
            _tasks.value = _tasks.value.map {
                if (it.id == id) it.copy(detail = detail, progress = progress) else it
            }
        }
    }

    fun update(id: String, transform: (BackgroundTask) -> BackgroundTask) {
        synchronized(this) {
            _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
        }
    }

    fun remove(id: String, completed: Boolean = false) {
        val task = synchronized(this) {
            val found = _tasks.value.firstOrNull { it.id == id }
            _tasks.value = _tasks.value.filterNot { it.id == id }
            found
        }
        if (completed && task != null) {
            _completions.tryEmit("${task.title} 已完成")
        }
    }

    fun cancel(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        runCatching { task.cancel?.invoke() }
        remove(id)
    }

    /** 结束全部后台任务；通知栏的「结束全部」和任务抽屉共用这里。 */
    fun cancelAll() {
        val snapshot = synchronized(this) {
            val all = _tasks.value
            _tasks.value = emptyList()
            all
        }
        snapshot.forEach { task ->
            runCatching { task.cancel?.invoke() }
        }
    }

    fun isToolRunning(toolId: String): Boolean =
        _tasks.value.any { it.toolId == toolId }
}
