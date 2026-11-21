package com.mingdai.toTool.notification

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NotificationProgress(
    val value: Int,
    val max: Int,
    val indeterminate: Boolean,
)

data class NotificationHiderItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val bigText: String = "",
    val infoText: String = "",
    val textLines: List<String> = emptyList(),
    val category: String = "",
    val channelId: String = "",
    val ongoing: Boolean = false,
    val clearable: Boolean = true,
    val progress: NotificationProgress? = null,
    val actions: List<String> = emptyList(),
    val media: List<Bitmap> = emptyList(),
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
