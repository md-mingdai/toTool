package com.mingdai.toTool.fakecall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 重启或应用更新后重新挂上尚未触发的定时来电。 */
class FakeCallBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FakeCallScheduler.rescheduleSaved(context.applicationContext)
    }
}
