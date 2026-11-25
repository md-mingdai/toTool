package com.mingdai.toTool.fakecall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** 用系统精确闹钟安排一次定时来电。 */
internal object FakeCallScheduler {
    private const val REQUEST_CODE = 7321
    private const val PREFS = "fake_call_schedule"
    private const val KEY_AT = "scheduled_at"

    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    fun scheduledAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)

    fun schedule(
        context: Context,
        triggerAtMillis: Long,
        callerName: String,
        callerNumber: String,
        hideNumber: Boolean,
        audioPath: String?,
        ringTimeoutSeconds: Int,
    ): Boolean {
        if (!canScheduleExact(context)) return false
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val intent = Intent(context, FakeCallAlarmReceiver::class.java).apply {
            putExtra(FakeCallTelecom.EXTRA_CALLER_NAME, callerName)
            putExtra(FakeCallTelecom.EXTRA_CALLER_NUMBER, callerNumber)
            putExtra(FakeCallTelecom.EXTRA_HIDE_NUMBER, hideNumber)
            putExtra(FakeCallTelecom.EXTRA_AUDIO_PATH, audioPath.orEmpty())
            putExtra(FakeCallTelecom.EXTRA_RING_TIMEOUT, ringTimeoutSeconds)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return runCatching {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pending,
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_AT, triggerAtMillis)
                .apply()
            true
        }.getOrDefault(false)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, FakeCallAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager?.cancel(pending)
        pending.cancel()
        clearState(context)
    }

    fun clearState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_AT)
            .apply()
    }
}
