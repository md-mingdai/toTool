package com.mingdai.toTool.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mingdai.toTool.ui.theme.ThemeMode

/**
 * Observable favorites backed by [Prefs]. Provided app-wide via [LocalFavorites].
 */
class FavoritesState(private val prefs: Prefs) {
    var favorites: Set<String> by mutableStateOf(prefs.favorites())
        private set

    fun isFavorite(id: String): Boolean = id in favorites

    fun toggle(id: String) {
        favorites = prefs.toggleFavorite(id)
    }

    /** 清空全部收藏。 */
    fun clear() {
        prefs.setFavorites(emptySet())
        favorites = emptySet()
    }
}

/** Observable theme-mode holder so Settings can mutate the theme at runtime. */
class ThemeState(
    initial: ThemeMode,
    initialHue: Float,
    initialSat: Float,
    initialVal: Float,
    private val prefs: Prefs,
) {
    var mode: ThemeMode by mutableStateOf(initial)
        private set
    var hue: Float by mutableStateOf(initialHue)
        private set
    var saturation: Float by mutableStateOf(initialSat)
        private set
    var value: Float by mutableStateOf(initialVal)
        private set

    fun set(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        this.mode = mode
    }

    fun updateHue(hue: Float) {
        prefs.setCustomHue(hue)
        this.hue = hue
    }

    fun updateSaturation(sat: Float) {
        prefs.setCustomSaturation(sat)
        this.saturation = sat
    }

    fun updateValue(val_: Float) {
        prefs.setCustomValue(val_)
        this.value = val_
    }
}

/** Observable compact-display toggle backed by [Prefs]. */
class DisplayState(private val prefs: Prefs) {
    var compact: Boolean by mutableStateOf(prefs.compactMode())
        private set

    fun set(value: Boolean) {
        prefs.setCompactMode(value)
        compact = value
    }

    fun toggle() = set(!compact)
}

/** Observable unit-abbreviation display toggle backed by [Prefs]. */
class UnitLabelState(private val prefs: Prefs) {
    var useAbbr: Boolean by mutableStateOf(prefs.unitLabelMode())
        private set

    fun set(value: Boolean) {
        prefs.setUnitLabelMode(value)
        useAbbr = value
    }

    fun toggle() = set(!useAbbr)
}

/** 交互 / 行为类设置：触感反馈、工具页常亮、分类默认展开、快捷方式圆角。 */
class BehaviorState(private val prefs: Prefs) {
    var haptics: Boolean by mutableStateOf(prefs.hapticsEnabled())
        private set
    var keepScreenOn: Boolean by mutableStateOf(prefs.keepScreenOn())
        private set
    var expandCategories: Boolean by mutableStateOf(prefs.categoriesExpanded())
        private set
    /** 工具添加到桌面时，快捷方式图标的圆角半径（px，按 96px 边长的图标换算）。 */
    var shortcutCornerRadius: Int by mutableIntStateOf(prefs.shortcutCornerRadius())
        private set

    // 注意：这里不能叫 setHaptics / setKeepScreenOn / setExpandCategories / setShortcutCornerRadius——
    // 上面的属性（即使 setter 是 private）也会生成同名的 JVM 方法，会触发 "Platform declaration clash"。
    fun updateHaptics(value: Boolean) {
        prefs.setHapticsEnabled(value)
        haptics = value
    }

    fun updateKeepScreenOn(value: Boolean) {
        prefs.setKeepScreenOn(value)
        keepScreenOn = value
    }

    fun updateExpandCategories(value: Boolean) {
        prefs.setCategoriesExpanded(value)
        expandCategories = value
    }

    fun updateShortcutCornerRadius(value: Int) {
        val clamped = value.coerceIn(0, SHORTCUT_RADIUS_MAX)
        if (clamped == shortcutCornerRadius) return
        prefs.setShortcutCornerRadius(clamped)
        shortcutCornerRadius = clamped
    }

    /** 恢复默认值。 */
    fun reset() {
        updateHaptics(true)
        updateKeepScreenOn(false)
        updateExpandCategories(true)
        updateShortcutCornerRadius(SHORTCUT_RADIUS_DEFAULT)
    }
}

val LocalBehaviorState = staticCompositionLocalOf<BehaviorState> {
    error("BehaviorState not provided")
}

val LocalPrefs = staticCompositionLocalOf<Prefs> {
    error("Prefs not provided")
}

val LocalFavorites = staticCompositionLocalOf<FavoritesState> {
    error("FavoritesState not provided")
}

val LocalThemeState = staticCompositionLocalOf<ThemeState> {
    error("ThemeState not provided")
}

val LocalDisplayState = staticCompositionLocalOf<DisplayState> {
    error("DisplayState not provided")
}

val LocalUnitLabelState = staticCompositionLocalOf<UnitLabelState> {
    error("UnitLabelState not provided")
}

/** Convenience accessor used by screens that need the favorites state. */
@Composable
fun rememberFavorites(): FavoritesState = LocalFavorites.current
