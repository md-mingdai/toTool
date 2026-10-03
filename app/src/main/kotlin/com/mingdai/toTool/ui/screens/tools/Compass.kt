package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mingdai.toTool.R
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.ui.components.displayStyle

/* 指南针 (Compass) — 直接照抄 MBCompass/MBCompass 实现 */

class Azimuth(rawDegrees: Float) {
    init {
        if (!rawDegrees.isFinite()) {
            throw IllegalArgumentException("Azimuth should be finite $rawDegrees")
        }
    }

    val degrees = wrapAzimuth(rawDegrees)
    val roundedDegrees = wrapAzimuth(rawDegrees.roundToInt().toFloat())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Azimuth
        return degrees == other.degrees
    }

    override fun hashCode(): Int = degrees.hashCode()
    fun add(degrees: Float) = Azimuth(this.degrees + degrees)
    fun wrapAzimuth(angleInDegrees: Float): Float = (angleInDegrees + 360f) % 360f
}

@Composable
fun CompassTool(padding: PaddingValues) {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    var azimuth by remember { mutableStateOf(Azimuth(0f)) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val listener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                val rotationMatrix = FloatArray(9)
                val adjustedMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val rotation = @Suppress("DEPRECATION") (
                    context.getSystemService(Context.WINDOW_SERVICE)
                        as? android.view.WindowManager
                )?.defaultDisplay?.rotation ?: Surface.ROTATION_0

                when (rotation) {
                    Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, adjustedMatrix)
                    Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedMatrix)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedMatrix)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                        rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedMatrix)
                }

                SensorManager.getOrientation(adjustedMatrix, orientationAngles)
                val toDegree = ((Math.toDegrees(orientationAngles[0].toDouble()).toFloat() + 360f) % 360f)
                azimuth = Azimuth(toDegree)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
    }

    DisposableEffect(listener, rotationSensor, lifecycleOwner) {
        if (rotationSensor != null) {
            if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED) {
                sensorManager.registerListener(listener, rotationSensor!!, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (rotationSensor != null) {
                        sensorManager.registerListener(listener, rotationSensor!!, SensorManager.SENSOR_DELAY_FASTEST)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> sensorManager.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorManager.unregisterListener(listener)
        }
    }

    if (rotationSensor == null) {
        Box(
            modifier = Modifier.padding(padding).fillMaxWidth().height(300.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.rotation_sensor_not_available),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }

    val targetRotation = azimuth.roundedDegrees
    val initialTargetRotation = remember { targetRotation }
    var previousRotation by remember { mutableStateOf(initialTargetRotation) }
    var adjustedRotation by remember { mutableStateOf(initialTargetRotation) }

    LaunchedEffect(targetRotation) {
        val diff = targetRotation - previousRotation
        adjustedRotation += when {
            diff > 180f -> diff - 360f
            diff < -180f -> diff + 360f
            else -> diff
        }
        previousRotation = targetRotation
    }

    val animatedRotation by animateFloatAsState(
        targetValue = -adjustedRotation,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "CompassRotation",
    )

    ToolBody(padding) {
        CompassView(
            degree = targetRotation,
            direction = directionFromAzimuth(targetRotation),
            animatedRotation = animatedRotation,
        )
    }
}

@Composable
private fun CompassView(
    degree: Float,
    direction: String,
    animatedRotation: Float,
) {
    val primary = MiuixTheme.colorScheme.primary
    val roseTint = MiuixTheme.colorScheme.onSurface

    Box(
        modifier = Modifier.fillMaxWidth().height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.mbcompass_rose),
                contentDescription = stringResource(R.string.compass_rose),
                modifier = Modifier
                    .size(280.dp)
                    .graphicsLayer { rotationZ = animatedRotation },
                colorFilter = ColorFilter.tint(roseTint),
            )
            Canvas(Modifier.fillMaxSize()) {
                val center = size.center
                val r = size.minDimension / 2f - 2f
                val topY = center.y - r
                drawLine(
                    color = primary,
                    start = Offset(center.x - 12f, topY - 14f),
                    end = Offset(center.x, topY + 6f),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = primary,
                    start = Offset(center.x, topY + 6f),
                    end = Offset(center.x + 12f, topY - 14f),
                    strokeWidth = 3.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            String.format(Locale.US, "%.0f°", degree),
            style = displayStyle(36.sp),
            color = primary,
        )
        Text(direction, style = MiuixTheme.textStyles.headline2)
    }
    SectionLabel("提示")
    Text(
        "缓慢转动设备，方向传感器稳定后读数更准确；机身内的金属物可能造成偏差。",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

private fun directionFromAzimuth(azimuth: Float): String = when (azimuth) {
    in 22.5f..<67.5f -> "东北 NE"
    in 67.5f..<112.5f -> "东 E"
    in 112.5f..<157.5f -> "东南 SE"
    in 157.5f..<202.5f -> "南 S"
    in 202.5f..<247.5f -> "西南 SW"
    in 247.5f..<292.5f -> "西 W"
    in 292.5f..<337.5f -> "西北 NW"
    else -> "北 N"
}