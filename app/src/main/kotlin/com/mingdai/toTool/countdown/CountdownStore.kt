package com.mingdai.toTool.countdown

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一条倒数日记录。日期只精确到天。 */
data class CountdownEvent(
    val id: Long,
    val title: String,
    val date: LocalDate,
    val repeatYearly: Boolean,
)

/** 结合“今天”解析后的展示状态；repeatYearly 会滚动到下一次。 */
data class CountdownDisplay(
    val event: CountdownEvent,
    val target: LocalDate,
    val days: Long,
)

object CountdownStore {
    private const val PREFS = "countdown_events"
    private const val KEY_EVENTS = "events"

    fun load(context: Context): List<CountdownEvent> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val date = runCatching { LocalDate.parse(item.optString("date")) }.getOrNull()
                        ?: continue
                    val title = item.optString("title").trim()
                    if (title.isEmpty()) continue
                    add(
                        CountdownEvent(
                            id = item.optLong("id", i.toLong() + 1L),
                            title = title,
                            date = date,
                            repeatYearly = item.optBoolean("repeat", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, events: List<CountdownEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("title", event.title)
                    .put("date", event.date.toString())
                    .put("repeat", event.repeatYearly),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
        CountdownWidgetProvider.updateAll(context)
    }

    fun displayFor(event: CountdownEvent, today: LocalDate): CountdownDisplay {
        val target = if (event.repeatYearly) {
            var candidate = event.date.withYear(today.year)
            if (candidate.isBefore(today)) {
                candidate = event.date.withYear(today.year + 1)
            }
            candidate
        } else {
            event.date
        }
        return CountdownDisplay(event, target, ChronoUnit.DAYS.between(today, target))
    }

    /** 最近的未来事件优先；全部是过去事件时，取最近刚过去的一条。 */
    fun nextDisplay(events: List<CountdownEvent>, today: LocalDate): CountdownDisplay? {
        if (events.isEmpty()) return null
        val displays = events.map { displayFor(it, today) }
        return displays.filter { it.days >= 0 }.minByOrNull { it.days }
            ?: displays.maxByOrNull { it.days }
    }

    /** 未来的按临近程度升序，过去的按刚过去的程度紧随其后。 */
    fun sorted(events: List<CountdownEvent>, today: LocalDate): List<CountdownDisplay> {
        val displays = events.map { displayFor(it, today) }
        val upcoming = displays.filter { it.days >= 0 }.sortedBy { it.days }
        val past = displays.filter { it.days < 0 }.sortedByDescending { it.days }
        return upcoming + past
    }

    fun dayLabel(days: Long): String = when {
        days > 0L -> "还有 $days 天"
        days == 0L -> "就是今天"
        else -> "已过 ${-days} 天"
    }
}
