package com.mingdai.toTool.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mingdai.toTool.ui.theme.ThemeMode

/**
 * Lightweight persistence wrapper backed by SharedPreferences.
 * Stores the favorite tool-id set and the chosen theme mode.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun favorites(): Set<String> =
        sp.getStringSet(KEY_FAV, emptySet())?.toSet() ?: emptySet()

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

    private companion object {
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
