package com.mingdai.toTool.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mingdai.toTool.ui.theme.ThemeMode

/** 快捷方式图标圆角（px）：默认值、上限、换算基准。上限正好是基准的一半，即正圆。 */
const val SHORTCUT_RADIUS_DEFAULT = 16
const val SHORTCUT_RADIUS_MAX = 48
const val SHORTCUT_RADIUS_BASE_PX = 96

/** 「FFmpeg 命令」：输入框内容、单条历史命令的长度上限，以及历史命令最多保留几条。 */
const val FFMPEG_COMMAND_MAX_CHARS = 8000
const val FFMPEG_HISTORY_MAX = 20

/**
 * Lightweight persistence wrapper backed by SharedPreferences.
 * Stores the favorite tool-id set and the chosen theme mode.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取收藏；旧版本里已被合并/改名的工具 id 会自动换成新的 id。 */
    fun favorites(): Set<String> =
        (sp.getStringSet(KEY_FAV, emptySet()) ?: emptySet())
            .map { ToolRegistry.resolve(it) }
            .toSet()

    fun setFavorites(ids: Set<String>) {
        sp.edit().putStringSet(KEY_FAV, ids).apply()
    }

    /** Toggle a tool id in the favorites set; returns the new set. */
    fun toggleFavorite(id: String): Set<String> {
        val current = favorites().toMutableSet()
        if (!current.add(id)) current.remove(id)
        setFavorites(current)
        return current
    }

    fun themeMode(): ThemeMode {
        val stored = sp.getString(KEY_THEME, null)
            ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(stored) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        sp.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun customHue(): Float = sp.getFloat(KEY_CUSTOM_HUE, 0f)

    fun setCustomHue(value: Float) {
        sp.edit().putFloat(KEY_CUSTOM_HUE, value).apply()
    }

    fun customSaturation(): Float = sp.getFloat(KEY_CUSTOM_SAT, 0.8f)

    fun setCustomSaturation(value: Float) {
        sp.edit().putFloat(KEY_CUSTOM_SAT, value).apply()
    }

    fun customValue(): Float = sp.getFloat(KEY_CUSTOM_VAL, 0.55f)

    fun setCustomValue(value: Float) {
        sp.edit().putFloat(KEY_CUSTOM_VAL, value).apply()
    }

    fun compactMode(): Boolean = sp.getBoolean(KEY_COMPACT, true)

    fun setCompactMode(value: Boolean) {
        sp.edit().putBoolean(KEY_COMPACT, value).apply()
    }

    fun unitLabelMode(): Boolean = sp.getBoolean(KEY_UNIT_ABBR, false)

    fun setUnitLabelMode(value: Boolean) {
        sp.edit().putBoolean(KEY_UNIT_ABBR, value).apply()
    }

    /** 触感反馈总开关（列表边缘、长按复制等）。 */
    fun hapticsEnabled(): Boolean = sp.getBoolean(KEY_HAPTICS, true)

    fun setHapticsEnabled(value: Boolean) {
        sp.edit().putBoolean(KEY_HAPTICS, value).apply()
    }

    /** 进入工具页时保持屏幕常亮。 */
    fun keepScreenOn(): Boolean = sp.getBoolean(KEY_KEEP_ON, false)

    fun setKeepScreenOn(value: Boolean) {
        sp.edit().putBoolean(KEY_KEEP_ON, value).apply()
    }

    /** 紧凑模式下，分类默认展开还是收起。 */
    fun categoriesExpanded(): Boolean = sp.getBoolean(KEY_CAT_EXPANDED, true)

    fun setCategoriesExpanded(value: Boolean) {
        sp.edit().putBoolean(KEY_CAT_EXPANDED, value).apply()
    }

    /**
     * 工具添加到桌面时，快捷方式图标的圆角半径（px，按 [SHORTCUT_RADIUS_BASE_PX] 边长的图标换算）。
     */
    fun shortcutCornerRadius(): Int =
        sp.getInt(KEY_SHORTCUT_RADIUS, SHORTCUT_RADIUS_DEFAULT).coerceIn(0, SHORTCUT_RADIUS_MAX)

    fun setShortcutCornerRadius(value: Int) {
        sp.edit().putInt(KEY_SHORTCUT_RADIUS, value.coerceIn(0, SHORTCUT_RADIUS_MAX)).apply()
    }

    /** 格式转换页「FFmpeg 命令」输入框里的内容：离开页面时保存，回来时恢复。 */
    fun ffmpegDraft(): String = sp.getString(KEY_FFMPEG_DRAFT, "") ?: ""

    fun setFfmpegDraft(value: String) {
        sp.edit().putString(KEY_FFMPEG_DRAFT, value.take(FFMPEG_COMMAND_MAX_CHARS)).apply()
    }

    /** 执行过的 FFmpeg 命令，最近的排在最前，最多 [FFMPEG_HISTORY_MAX] 条。 */
    fun ffmpegHistory(): List<String> =
        (sp.getString(KEY_FFMPEG_HISTORY, "") ?: "")
            .split(HISTORY_SEPARATOR)
            .filter { it.isNotBlank() }

    /** 记下一条执行过的命令（已有的相同命令移到最前），返回更新后的列表。 */
    fun addFfmpegHistory(command: String): List<String> {
        val clean = command.trim().replace(HISTORY_SEPARATOR, ' ').take(FFMPEG_COMMAND_MAX_CHARS)
        if (clean.isEmpty()) return ffmpegHistory()
        val next = (listOf(clean) + ffmpegHistory().filter { it != clean }).take(FFMPEG_HISTORY_MAX)
        sp.edit().putString(KEY_FFMPEG_HISTORY, next.joinToString(HISTORY_SEPARATOR.toString())).apply()
        return next
    }

    fun clearFfmpegHistory() {
        sp.edit().remove(KEY_FFMPEG_HISTORY).apply()
    }

    private companion object {
        const val KEY_FFMPEG_DRAFT = "ffmpeg_draft"
        const val KEY_FFMPEG_HISTORY = "ffmpeg_history"

        /** 历史命令之间的分隔符：命令里可能有换行、空格，用一个不会出现在命令里的控制字符隔开。 */
        const val HISTORY_SEPARATOR = '\u0001'
        const val KEY_SHORTCUT_RADIUS = "shortcut_corner_radius"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_CAT_EXPANDED = "categories_expanded"
        const val NAME = "tooly_prefs"
        const val KEY_FAV = "favorite_ids"
        const val KEY_THEME = "theme_mode"
        const val KEY_CUSTOM_HUE = "custom_hue"
        const val KEY_CUSTOM_SAT = "custom_sat"
        const val KEY_CUSTOM_VAL = "custom_val"
        const val KEY_COMPACT = "compact_mode"
        const val KEY_UNIT_ABBR = "unit_label_abbr"
    }
}
