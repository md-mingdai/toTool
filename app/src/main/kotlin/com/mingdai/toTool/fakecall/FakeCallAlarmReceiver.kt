package com.mingdai.toTool.fakecall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** 精确闹钟触发后发起来电；用 goAsync + 短唤醒锁保证广播返回前完成 Telecom 调用。 */
class FakeCallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val app = context.applicationContext
        val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "toTool:fakecall")
            ?.apply { acquire(15_000L) }
        try {
            FakeCallScheduler.clearState(app)
            val name = intent?.getStringExtra(FakeCallTelecom.EXTRA_CALLER_NAME).orEmpty()
            val number = intent?.getStringExtra(FakeCallTelecom.EXTRA_CALLER_NUMBER).orEmpty()
            val hide = intent?.getBooleanExtra(FakeCallTelecom.EXTRA_HIDE_NUMBER, false) ?: false
            val audio = intent?.getStringExtra(FakeCallTelecom.EXTRA_AUDIO_PATH).orEmpty()
            val timeout = intent?.getIntExtra(FakeCallTelecom.EXTRA_RING_TIMEOUT, 45) ?: 45
            FakeCallTelecom.register(app)
            if (FakeCallTelecom.isEnabled(app)) {
                FakeCallTelecom.trigger(app, name, number, hide, audio, timeout)
            }
        } finally {
            wakeLock?.let { lock ->
                runCatching { if (lock.isHeld) lock.release() }
            }
            pending.finish()
        }
    }
}
