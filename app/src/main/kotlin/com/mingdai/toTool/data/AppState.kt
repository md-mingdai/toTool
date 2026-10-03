package com.mingdai.toTool.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
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
