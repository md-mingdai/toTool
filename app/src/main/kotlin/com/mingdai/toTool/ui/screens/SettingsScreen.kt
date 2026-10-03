package com.mingdai.toTool.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.mingdai.toTool.R
import com.mingdai.toTool.data.LocalDisplayState
import com.mingdai.toTool.data.LocalThemeState
import com.mingdai.toTool.data.LocalUnitLabelState
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.ColorWheelPicker
import com.mingdai.toTool.ui.theme.ThemeMode
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun SettingsScreen(navController: NavHostController) {
    val themeState = LocalThemeState.current
    val display = LocalDisplayState.current
    val unitLabel = LocalUnitLabelState.current
    val context = LocalContext.current

    val version = remember {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    }

    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var showColorDialog by remember { mutableStateOf(false) }

    val themeOptions = listOf(
        ThemeMode.SYSTEM to R.string.theme_system,
        ThemeMode.CUSTOM to R.string.theme_custom,
        ThemeMode.LIGHT to R.string.theme_light,
        ThemeMode.DARK to R.string.theme_dark,
        ThemeMode.DYNAMIC to R.string.theme_dynamic,
    )

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()),
        ) {
            SmallTitle(text = stringResource(R.string.settings_theme))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                themeOptions.forEach { (mode, labelRes) ->
                    val enabled = mode != ThemeMode.DYNAMIC || supportsDynamic
                    RadioButtonPreference(
                        title = stringResource(labelRes),
                        selected = themeState.mode == mode,
                        onClick = if (enabled) ({ themeState.set(mode) }) else null,
                        enabled = enabled,
                    )
                }
                if (themeState.mode == ThemeMode.CUSTOM) {
                    ArrowPreference(
                        title = stringResource(R.string.pick_color),
                        onClick = { showColorDialog = true },
                    )
                }
            }

            SmallTitle(text = stringResource(R.string.settings_display))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = display.compact,
                    onCheckedChange = { display.set(it) },
                    title = stringResource(R.string.settings_compact),
                    summary = stringResource(R.string.settings_compact_desc),
                )
                SwitchPreference(
                    checked = unitLabel.useAbbr,
                    onCheckedChange = { unitLabel.set(it) },
                    title = stringResource(R.string.settings_unit_abbr),
                    summary = stringResource(R.string.settings_unit_abbr_desc),
                )
            }

            SmallTitle(text = stringResource(R.string.settings_about))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                AboutRow(stringResource(R.string.about_developer), "mingdai")
                AboutRow(stringResource(R.string.about_version), version)
                AboutRow("字体", "Onest · OFL 1.1")
            }
            // 给底部手势条留出空间
            Column(modifier = Modifier.padding(bottom = 24.dp)) {}
        }
    }

    AppDialog(
        show = showColorDialog,
        title = stringResource(R.string.theme_custom),
        onDismiss = { showColorDialog = false },
        confirmText = stringResource(R.string.action_done),
        dismissText = null,
    ) {
        ColorWheelPicker(
            hue = themeState.hue,
            saturation = themeState.saturation,
            value = themeState.value,
            onHueChange = { themeState.updateHue(it) },
            onSaturationChange = { themeState.updateSaturation(it) },
            onValueChange = { themeState.updateValue(it) },
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    BasicComponent(
        title = label,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
    )
}
