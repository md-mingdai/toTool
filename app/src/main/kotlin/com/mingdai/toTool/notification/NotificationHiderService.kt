package com.mingdai.toTool.notification

import android.app.Notification
import android.graphics.Bitmap
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通过通知使用权隐藏单条通知：
 * - hide() 调用 snoozeNotification 打盹约 3 年；
 * - onNotificationPosted 对已隐藏的 key 再次 snooze，防止应用重新推送；
 * - 隐藏状态保存在本地，取消隐藏后用极短 snooze 让系统重新投递通知。
 *
 * 不读取通知正文用于网络传输，所有数据只保存在本机。
 */
class NotificationHiderService : NotificationListenerService() {
    companion object {
        private const val PREFS = "notification_hider"
        private const val KEY_RECORDS = "hidden_records"
        private const val HIDE_DURATION_MS = 94_608_000_000L
        private const val UNSNOOZE_MS = 1L

        fun requestAccessRebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, NotificationHiderService::class.java))
            }
        }

        fun hasAccess(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.isNotificationListenerAccessGranted(
                ComponentName(context, NotificationHiderService::class.java),
            )
        }.getOrDefault(false)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val appNames = mutableMapOf<String, String>()
    private val hiddenRecords = mutableMapOf<String, HiddenRecord>()
    private var lastItems = emptyMap<String, NotificationHiderItem>()
    private var refreshScheduled = false
    private var connected = false

    private data class HiddenRecord(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val hiddenAt: Long,
    )

    override fun onCreate() {
        super.onCreate()
        loadRecords()
        NotificationHiderBridge.service = this
        NotificationHiderBridge.connected.value = false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        NotificationHiderBridge.connected.value = true
        refresh()
        rehidePersisted()
    }

    override fun onListenerDisconnected() {
        connected = false
        NotificationHiderBridge.connected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (hiddenRecords.containsKey(sbn.key)) {
            // 防御性重隐藏：应用重新推送同一 key 时继续 snooze。
            runCatching { snoozeNotification(sbn.key, HIDE_DURATION_MS) }
        }
        scheduleRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        scheduleRefresh()
    }

    override fun onDestroy() {
        connected = false
        NotificationHiderBridge.connected.value = false
        if (NotificationHiderBridge.service === this) {
            NotificationHiderBridge.service = null
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    internal fun hide(key: String): Boolean {
        if (!connected) return false
        val item = lastItems[key] ?: return false
        val record = HiddenRecord(
            key = key,
            packageName = item.packageName,
            appName = item.appName,
            title = item.title.take(500),
            text = item.text.take(1000),
            postTime = item.postTime,
            hiddenAt = System.currentTimeMillis(),
        )
        val snoozed = runCatching {
            snoozeNotification(key, HIDE_DURATION_MS)
            true
        }.getOrDefault(false)
        if (!snoozed) return false
        hiddenRecords[key] = record
        saveRecords()
        scheduleRefresh()
        return true
    }

    internal fun unhide(key: String): Boolean {
        if (!connected) return false
        if (hiddenRecords.remove(key) == null) return false
        saveRecords()
        // 公开 API 没有 cancelSnooze，1ms 的 snooze 会在系统侧立即到期并重新投递。
        runCatching { snoozeNotification(key, UNSNOOZE_MS) }
        scheduleRefresh()
        return true
    }

    internal fun unhideAll(): Boolean {
        if (!connected || hiddenRecords.isEmpty()) return false
        val keys = hiddenRecords.keys.toList()
        hiddenRecords.clear()
        saveRecords()
        keys.forEach { key -> runCatching { snoozeNotification(key, UNSNOOZE_MS) } }
        scheduleRefresh()
        return true
    }

    internal fun refresh() {
        if (!connected) return
        val records = runCatching { getActiveNotifications() }.getOrNull().orEmpty()
        val snoozed = runCatching { getSnoozedNotifications() }.getOrNull().orEmpty()
        val all = (records.asList() + snoozed.asList()).distinctBy { it.key }
        val items = LinkedHashMap<String, NotificationHiderItem>()
        for (sbn in all) {
            val hidden = hiddenRecords[sbn.key]
            val item = toItem(sbn, hidden)
            items[sbn.key] = item
        }
        // 系统已经丢弃但本地仍保留的隐藏记录也要显示，方便恢复。
        hiddenRecords.values.forEach { record ->
            if (!items.containsKey(record.key)) {
                items[record.key] = NotificationHiderItem(
                    key = record.key,
                    packageName = record.packageName,
                    appName = record.appName,
                    title = record.title,
                    text = record.text,
                    postTime = record.postTime,
                    isHidden = true,
                    hiddenAt = record.hiddenAt,
                )
            }
        }
        lastItems = items
        NotificationHiderBridge.notifications.value = items.values.sortedWith(
            compareByDescending<NotificationHiderItem> { it.isHidden }
                .thenByDescending { if (it.isHidden) it.hiddenAt else it.postTime },
        )
    }

    private fun rehidePersisted() {
        if (hiddenRecords.isEmpty()) return
        val active = runCatching { getActiveNotifications() }.getOrNull().orEmpty()
        active.forEach { sbn ->
            if (hiddenRecords.containsKey(sbn.key)) {
                runCatching { snoozeNotification(sbn.key, HIDE_DURATION_MS) }
            }
        }
    }

    private fun toItem(sbn: StatusBarNotification, record: HiddenRecord?): NotificationHiderItem {
        val notification = sbn.notification
        val extras = notification.extras
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?: notification.tickerText)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val progressValue = extras?.getInt(Notification.EXTRA_PROGRESS, -1) ?: -1
        val progressMax = extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val indeterminate = extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false
        val progress = if (progressValue >= 0 || indeterminate) {
            NotificationProgress(progressValue.coerceAtLeast(0), progressMax.coerceAtLeast(0), indeterminate)
        } else {
            null
        }
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.take(20)
            .orEmpty()
        val actions = notification.actions
            ?.mapNotNull { it.title?.toString()?.takeIf(String::isNotBlank) }
            ?.take(20)
            .orEmpty()
        val media = ArrayList<Bitmap>(4)
        (extras?.get(Notification.EXTRA_PICTURE) as? Bitmap)?.let(media::add)
        (extras?.get(Notification.EXTRA_LARGE_ICON_BIG) as? Bitmap)?.let(media::add)
        (extras?.get(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let(media::add)
        runCatching { notification.getLargeIcon()?.loadDrawable(this)?.toBitmap() }
            .getOrNull()?.let(media::add)
        return NotificationHiderItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName(sbn.packageName),
            title = title.ifBlank { appName(sbn.packageName) },
            text = text,
            subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            infoText = extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty(),
            textLines = lines,
            category = notification.category.orEmpty(),
            channelId = notification.channelId.orEmpty(),
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            progress = progress,
            actions = actions,
            media = media.distinctBy { System.identityHashCode(it) }.take(4),
            postTime = sbn.postTime,
            isHidden = record != null,
            hiddenAt = record?.hiddenAt ?: 0L,
        )
    }

    private fun appName(packageName: String): String = appNames.getOrPut(packageName) {
        runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        handler.postDelayed({
            refreshScheduled = false
            refresh()
        }, 250)
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadRecords() {
        hiddenRecords.clear()
        val raw = prefs().getString(KEY_RECORDS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val key = obj.optString("key")
                if (key.isBlank()) continue
                hiddenRecords[key] = HiddenRecord(
                    key = key,
                    packageName = obj.optString("packageName"),
                    appName = obj.optString("appName"),
                    title = obj.optString("title"),
                    text = obj.optString("text"),
                    postTime = obj.optLong("postTime"),
                    hiddenAt = obj.optLong("hiddenAt"),
                )
            }
        }
    }

    private fun saveRecords() {
        val array = JSONArray()
        hiddenRecords.values.forEach { record ->
            array.put(JSONObject().apply {
                put("key", record.key)
                put("packageName", record.packageName)
                put("appName", record.appName)
                put("title", record.title)
                put("text", record.text)
                put("postTime", record.postTime)
                put("hiddenAt", record.hiddenAt)
            })
        }
        prefs().edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}
