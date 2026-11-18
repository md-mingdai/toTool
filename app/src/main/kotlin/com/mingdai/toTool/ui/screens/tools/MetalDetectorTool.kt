package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import com.mingdai.toTool.ui.components.displayStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val FIELD_FILTER_ALPHA = 0.24f
private const val NEAR_METAL_DELTA = 8f
private const val STRONG_METAL_DELTA = 30f
private const val GAUGE_MAX_DELTA = 70f
private val CautionColor = Color(0xFFE4A33B)

/**
 * 金属探测器：用磁力计读取环境磁场总强度，相对基准值的偏移越大，通常说明附近铁磁性物体越近。
 * 手机内部的扬声器、振动马达和保护壳磁扣本身也会影响读数，所以进入页面后先记录一次基准。
 */
@Composable
fun MetalDetectorTool(padding: PaddingValues) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val sensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }

    if (sensor == null) {
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "设备没有磁力计，无法使用金属探测器",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }

    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(0f) }
    var magnitude by remember { mutableFloatStateOf(0f) }
    var baseline by remember { mutableFloatStateOf(0f) }
    var calibrated by remember { mutableStateOf(false) }
    var sensorAccuracy by remember { mutableIntStateOf(SensorManager.SENSOR_STATUS_ACCURACY_HIGH) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(sensor, lifecycleOwner) {
        var fx = 0f
        var fy = 0f
        var fz = 0f
        var primed = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                val nx = event.values[0]
                val ny = event.values[1]
                val nz = event.values[2]
                if (!primed) {
                    fx = nx
                    fy = ny
                    fz = nz
                    primed = true
                } else {
                    fx += FIELD_FILTER_ALPHA * (nx - fx)
                    fy += FIELD_FILTER_ALPHA * (ny - fy)
                    fz += FIELD_FILTER_ALPHA * (nz - fz)
                }
                val field = sqrt(fx * fx + fy * fy + fz * fz)
                x = fx
                y = fy
                z = fz
                magnitude = field
                if (!calibrated) {
                    baseline = field
                    calibrated = true
                }
                sensorAccuracy = event.accuracy
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                sensorAccuracy = accuracy
            }
        }
        if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> sensorManager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }

    val delta = magnitude - baseline
    val strength = abs(delta)
    val progress by animateFloatAsState(
        targetValue = (strength / GAUGE_MAX_DELTA).coerceIn(0f, 1f),
        animationSpec = tween(180),
        label = "metal-strength",
    )
    val statusColor = when {
        strength < NEAR_METAL_DELTA -> MiuixTheme.colorScheme.primary
        strength < STRONG_METAL_DELTA -> CautionColor
        else -> WarnColor
    }
    val statusText = when {
        !calibrated -> "正在读取磁场"
        strength < NEAR_METAL_DELTA -> "环境磁场稳定"
        strength < STRONG_METAL_DELTA -> "磁场有变化"
        else -> "检测到较强磁场"
    }

    ToolBody(padding) {
        SectionLabel("金属探测器")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MagneticGauge(
                    progress = progress,
                    color = statusColor,
                    magnitude = magnitude,
                    status = statusText,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Reading(label = "基准", value = String.format(Locale.US, "%.1f μT", baseline))
                    Reading(label = "偏移", value = String.format(Locale.US, "%+.1f μT", delta))
                }
                SecondaryButton(
                    onClick = {
                        baseline = magnitude
                        calibrated = true
                        Toast.makeText(context, "已把当前磁场设为基准", Toast.LENGTH_SHORT).show()
                    },
                    enabled = magnitude > 0f,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新校准基准")
                }
            }
        }

        SectionLabel("磁场分量")
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Reading(label = "X", value = String.format(Locale.US, "%+.1f", x))
                Reading(label = "Y", value = String.format(Locale.US, "%+.1f", y))
                Reading(label = "Z", value = String.format(Locale.US, "%+.1f", z))
            }
        }

        if (sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            Text(
                "磁力计精度较低，建议把手机在空中缓慢画几个 8 字后重新校准。",
                style = MiuixTheme.textStyles.footnote1,
                color = WarnColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        HintText("读数会受到手机内部扬声器、振动马达、保护壳磁扣和附近电子设备影响。请远离这些物体后重新校准，并缓慢移动手机靠近疑似金属。")
    }
}

@Composable
private fun Reading(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            value,
            style = MiuixTheme.textStyles.title4,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MagneticGauge(
    progress: Float,
    color: Color,
    magnitude: Float,
    status: String,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier.size(230.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val radius = size.minDimension / 2f - 6f
            drawCircle(color = colors.secondaryContainer, radius = radius, center = c)
            for (ring in 1..3) {
                drawCircle(
                    color = colors.dividerLine,
                    radius = radius * ring / 3f,
                    center = c,
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }
            drawCircle(
                color = color.copy(alpha = 0.16f + progress * 0.22f),
                radius = radius * (0.24f + progress * 0.68f),
                center = c,
                style = Stroke(width = 10.dp.toPx()),
            )
            drawCircle(
                color = color.copy(alpha = 0.9f),
                radius = radius * 0.07f,
                center = c,
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(c.x - radius * 0.72f, c.y),
                end = Offset(c.x + radius * 0.72f, c.y),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color.copy(alpha = 0.7f),
                start = Offset(c.x, c.y - radius * 0.72f),
                end = Offset(c.x, c.y + radius * 0.72f),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "磁场强度",
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            Text(
                String.format(Locale.US, "%.1f", magnitude),
                style = displayStyle(36.sp),
                color = color,
            )
            Text("μT", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
            Text(
                status,
                style = MiuixTheme.textStyles.footnote1,
                color = color,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
