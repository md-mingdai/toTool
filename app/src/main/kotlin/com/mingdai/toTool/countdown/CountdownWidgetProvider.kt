package com.mingdai.toTool.countdown

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import java.time.LocalDate

/** 主屏幕倒数日小组件：显示最近的一条，点按打开工具。 */
class CountdownWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        super.onReceive(context, intent)
        when (intent?.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
            -> updateAll(context)
        }
    }

    companion object {
        private const val TOOL_ID = "daily_countdown"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CountdownWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id ->
                updateWidget(context, manager, id)
            }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)
            val display = CountdownStore.nextDisplay(CountdownStore.load(context), LocalDate.now())
            if (display == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.countdown_widget_empty))
                views.setTextViewText(R.id.widget_days, "—")
                views.setTextViewText(R.id.widget_unit, "")
                views.setTextViewText(R.id.widget_date, context.getString(R.string.countdown_widget_empty_hint))
            } else {
                val days = display.days
                views.setTextViewText(R.id.widget_title, display.event.title)
                if (days == 0L) {
                    views.setTextViewText(R.id.widget_days, context.getString(R.string.countdown_widget_today))
                    views.setTextViewText(R.id.widget_unit, "")
                } else {
                    views.setTextViewText(R.id.widget_days, kotlin.math.abs(days).toString())
                    views.setTextViewText(
                        R.id.widget_unit,
                        context.getString(
                            if (days > 0) R.string.countdown_widget_days_after
                            else R.string.countdown_widget_days_before,
                        ),
                    )
                }
                val target = display.target
                val dateText = if (display.event.repeatYearly) {
                    "${target.monthValue}月${target.dayOfMonth}日 · 每年"
                } else {
                    "${target.year}年${target.monthValue}月${target.dayOfMonth}日"
                }
                views.setTextViewText(R.id.widget_date, dateText)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TOOL_ID, TOOL_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
