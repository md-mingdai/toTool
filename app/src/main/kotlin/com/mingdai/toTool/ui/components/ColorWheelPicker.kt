package com.mingdai.toTool.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ColorWheelPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxSize = with(density) { 190.dp.roundToPx() }
    val colorBitmap = remember(value, pxSize) { createHueRingBitmap(pxSize, value) }
    val colorBitmapCompose = remember(colorBitmap) { colorBitmap.asImageBitmap() }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(190.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier.size(190.dp)
            ) {
                drawImage(colorBitmapCompose)
                val c = Offset(size.width / 2f, size.height / 2f)
                val outerR = size.width / 2f - 4f
                val ringWidth = outerR * 0.35f
                val strokeCenterR = outerR - ringWidth / 2f
                val angle = (hue - 90f) * Math.PI / 180.0
                val dotX = (c.x + strokeCenterR * cos(angle).toFloat())
                val dotY = (c.y + strokeCenterR * sin(angle).toFloat())
                drawCircle(color = Color.White, radius = 12f, center = Offset(dotX, dotY))
                drawCircle(color = Color.Black, radius = 12f, center = Offset(dotX, dotY), style = Stroke(4f), alpha = 0.3f)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentColor = Color.hsv(hue, saturation, value)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(currentColor, androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, MiuixTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = String.format(
                    "#%02X%02X%02X",
                    (currentColor.red * 255).toInt(),
                    (currentColor.green * 255).toInt(),
                    (currentColor.blue * 255).toInt(),
                ),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(10.dp))

        SliderRow(label = "色相", value = hue, valueRange = 0f..360f,
            onValueChange = onHueChange,
            displayValue = "${hue.toInt()}°")
        SliderRow(label = "饱和度", value = saturation, valueRange = 0f..1f,
            onValueChange = onSaturationChange,
            displayValue = "%.0f%%".format(saturation * 100f))
        SliderRow(label = "亮度", value = value, valueRange = 0f..1f,
            onValueChange = onValueChange,
            displayValue = "%.0f%%".format(value * 100f))
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    displayValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(10.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = displayValue,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun createHueRingBitmap(size: Int, value: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val c = size / 2f
    val outerR = c - 4f
    val ringWidth = outerR * 0.35f
    val strokeCenterR = outerR - ringWidth / 2f

    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }
    val rectF = RectF(
        c - strokeCenterR, c - strokeCenterR,
        c + strokeCenterR, c + strokeCenterR
    )

    val colors = IntArray(361)
    val positions = FloatArray(361)
    for (i in 0..360) {
        val hue = ((i + 90) % 360).toFloat()
        colors[i] = AndroidColor.HSVToColor(floatArrayOf(hue, 1f, value))
        positions[i] = i / 360f
    }
    paint.shader = SweepGradient(c, c, colors, positions)
    canvas.drawArc(rectF, 0f, 360f, false, paint)

    return bitmap
}
