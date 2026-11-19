package com.mingdai.toTool.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NotificationHiderItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val isHidden: Boolean,
    val hiddenAt: Long,
)

internal object NotificationHiderBridge {
    @Volatile
    var service: NotificationHiderService? = null

    val notifications = MutableStateFlow<List<NotificationHiderItem>>(emptyList())
    val connected = MutableStateFlow(false)

    fun refresh() {
        service?.refresh()
    }

    fun hide(key: String): Boolean = service?.hide(key) == true

    fun unhide(key: String): Boolean = service?.unhide(key) == true

    fun unhideAll(): Boolean = service?.unhideAll() == true
}
