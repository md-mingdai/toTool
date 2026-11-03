package com.mingdai.toTool.ui.screens.tools

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.ToolBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mingdai.toTool.ui.components.WarnColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.copyText
import com.mingdai.toTool.ui.components.rememberHaptics
import com.mingdai.toTool.ui.components.tabular
import top.yukonga.miuix.kmp.basic.Card

/**
 * 设备工具里的“名称 – 值”条目。长按复制该条目的值（带触感反馈和提示）。
 * 按下时背景淡淡高亮，让人知道这一行是可操作的。
 */
@Composable
private fun InfoRow(label: String, value: String) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressBg by animateColorAsState(
        targetValue = if (pressed) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        label = "infoRowPress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(pressBg)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onLongClick = {
                    haptics.confirm()
                    copyText(context, value, label)
                },
            )
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.38f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 4,
            modifier = Modifier.weight(0.62f),
        )
    }
}

/** 一组条目：标题 + 白色卡片（条目之间用细线分隔）+ 底部操作提示。 */
@Composable
private fun InfoGroup(title: String, rows: List<Pair<String, String>>) {
    SectionLabel(title)
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.dividerLine),
                )
            }
            InfoRow(k, v)
        }
    }
    HintText("长按任意条目可复制其内容")
}

/* ------------------------------- 14. 设备信息 ------------------------------- */

@Composable
fun DeviceInfoTool(padding: PaddingValues) {
    val context = LocalContext.current
    val items = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = context.resources.displayMetrics
        val abis = Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty().ifEmpty { "未知" }
        buildList {
            add("品牌" to Build.BRAND)
            add("厂商" to Build.MANUFACTURER)
            add("型号" to Build.MODEL)
            add("设备名" to Build.DEVICE)
            add("产品" to Build.PRODUCT)
            add("Android 版本" to (Build.VERSION.RELEASE ?: "未知"))
            add("SDK 版本" to Build.VERSION.SDK_INT.toString())
            add("安全补丁" to (Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotEmpty() } ?: "未知"))
            add("构建号" to Build.DISPLAY)
            add("ABI" to abis)
            add("屏幕分辨率" to "${dm.widthPixels} × ${dm.heightPixels}")
            add("屏幕密度" to "${dm.densityDpi}dpi (×${dm.density})")
            add("总内存" to "${mem.totalMem / 1024L / 1024L / 1024L} GB")
            add("可用内存" to "${mem.availMem / 1024L / 1024L / 1024L} GB")
            add("时区" to TimeZone.getDefault().id)
            add("语言" to Locale.getDefault().toDisplayName())
        }
    }
    ToolBody(padding) {
        InfoGroup("硬件与系统", items)
    }
}

private fun Locale.toDisplayName(): String {
    val l = if (this == Locale.getDefault()) this else this
    return "${l.language}-${l.country}"
}

/* ------------------------------- 15. 电池信息 ------------------------------- */

private data class BatterySnapshot(
    val percent: Int,
    val statusText: String,
    val healthText: String,
    val technology: String,
    val temperatureC: Float,
    val voltageMv: Int,
    val isCharging: Boolean,
)

private fun parseBattery(intent: Intent): BatterySnapshot {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (scale > 0 && level >= 0) level * 100 / scale else 0
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
    val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
    val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val tech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty().ifEmpty { "未知" }
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val statusText = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "未知"
        else -> "未知"
    }
    val healthText = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "失效"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
        BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
        else -> "未知"
    }
    return BatterySnapshot(
        percent = percent,
        statusText = statusText,
        healthText = healthText,
        technology = tech,
        temperatureC = if (temp >= 0) temp / 10f else 0f,
        voltageMv = voltage,
        isCharging = charging,
    )
}

@Composable
fun BatteryTool(padding: PaddingValues) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<BatterySnapshot?>(null) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent != null) snapshot = parseBattery(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // ACTION_BATTERY_CHANGED is a system sticky broadcast; the initial value is returned.
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (sticky != null) snapshot = parseBattery(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    ToolBody(padding) {
        val s = snapshot
        if (s == null) {
            SectionLabel("电池")
            Text("正在读取电池信息…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            InfoGroup(
                "电池",
                listOf(
                    "电量" to "${s.percent}%",
                    "状态" to if (s.isCharging) "${s.statusText}（接通电源）" else s.statusText,
                    "健康" to s.healthText,
                    "电池类型" to s.technology,
                    "温度" to "${String.format(Locale.US, "%.1f", s.temperatureC)} ℃",
                    "电压" to if (s.voltageMv > 0) "${s.voltageMv} mV" else "未知",
                ),
            )
        }
    }
}

/* ------------------------------- 16. 网络信息 ------------------------------- */

private fun localIpv4(): String? {
    return runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces.filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            Collections.list(ni.inetAddresses).filterIsInstance<Inet4Address>()
        }.firstOrNull { !it.isLinkLocalAddress }?.hostAddress
    }.getOrNull()
}

@Composable
fun NetworkTool(padding: PaddingValues) {
    val context = LocalContext.current
    val items = remember {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val type = when {
                caps == null -> "无连接"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                else -> "其他"
            }
            buildList {
                add("在线" to if (hasInternet) "是" else "否")
                add("连接类型" to type)
                add("本地 IPv4" to (localIpv4() ?: "获取失败"))
            }
        }.getOrDefault(buildList {
            add("在线" to "未知")
            add("连接类型" to "未知")
            add("本地 IPv4" to "获取失败")
        })
    }
    ToolBody(padding) {
        InfoGroup("网络", items)
    }
}

/* ------------------------------- 17. 传感器列表 ----------------------------- */

private val sensorTypes = listOf(
    Sensor.TYPE_ACCELEROMETER to "加速度计",
    Sensor.TYPE_GYROSCOPE to "陀螺仪",
    Sensor.TYPE_MAGNETIC_FIELD to "磁力计",
    Sensor.TYPE_LIGHT to "光线传感器",
    Sensor.TYPE_PROXIMITY to "距离传感器",
    Sensor.TYPE_PRESSURE to "气压计",
    Sensor.TYPE_RELATIVE_HUMIDITY to "湿度传感器",
    Sensor.TYPE_AMBIENT_TEMPERATURE to "环境温度",
    Sensor.TYPE_GRAVITY to "重力传感器",
    Sensor.TYPE_LINEAR_ACCELERATION to "线性加速度",
    Sensor.TYPE_ROTATION_VECTOR to "旋转向量",
    Sensor.TYPE_STEP_COUNTER to "计步器",
    Sensor.TYPE_STEP_DETECTOR to "步数检测",
    Sensor.TYPE_GAME_ROTATION_VECTOR to "游戏旋转向量",
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to "地磁旋转向量",
    Sensor.TYPE_SIGNIFICANT_MOTION to "显著运动",
)

@Composable
fun SensorListTool(padding: PaddingValues) {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val items = remember {
        sensorTypes.mapNotNull { (type, name) ->
            sm.getDefaultSensor(type)?.let { name to "${it.name} · ${it.vendor}" }
        }
    }
    ToolBody(padding) {
        if (items.isEmpty()) {
            SectionLabel("可用传感器")
            Text("未检测到任何传感器", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            InfoGroup("可用传感器（${items.size} 项）", items)
        }
    }
}

/* ------------------------------- 18. 水平仪 --------------------------------- */

/** 低通滤波系数：越小越平滑、越迟钝。0.15 在 ~50 Hz 采样下约等于 110 ms 的时间常数。 */
private const val LEVEL_FILTER_ALPHA = 0.15f

/** 倾斜多少度时小球碰到边缘。 */
private const val LEVEL_MAX_ANGLE = 20f

/**
 * 水平仪。
 *
 * 小球“一闪一闪”有三个原因，这里逐个处理：
 * 1. 加速度计的原始读数带噪声，小球位置每帧都在抖 → 对读数做低通滤波，并改用 ~50 Hz 的采样（原来 ~16 Hz，一顿一顿）。
 * 2. 是否水平用 1.5° 一刀切，角度在阈值上下抖动时，小球颜色（蓝 ↔ 橙）和文字来回翻转 → 改成带回差的判断：
 *    进入水平要 < 1.0°，离开水平要 > 1.8°，中间保持原状态；颜色变化也加了渐变。
 * 3. 每个传感器事件都让整个页面重组 → 角度放在 FloatState 里，只在绘制阶段读取，事件只触发小球重绘；
 *    文字用 derivedStateOf 取 0.1° 精度，数值没变就不重组。
 */
@Composable
fun SpiritLevelTool(padding: PaddingValues) {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // 重力传感器系统已做过融合滤波，优先用；没有再退回原始加速度计
    val sensor = remember {
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    if (sensor == null) {
        Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("设备没有加速度传感器", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        return
    }

    var roll by remember { mutableFloatStateOf(0f) }   // 左右倾斜角（度，已平滑）
    var pitch by remember { mutableFloatStateOf(0f) }  // 前后倾斜角（度，已平滑）
    var level by remember { mutableStateOf(false) }    // 是否处于“水平”状态（带回差）

    DisposableEffect(sensor) {
        var fx = 0f
        var fy = 0f
        var fz = 0f
        var primed = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (!primed) {
                    // 第一帧直接取值，避免小球从 0 慢慢“爬”过去
                    fx = x; fy = y; fz = z
                    primed = true
                } else {
                    fx += LEVEL_FILTER_ALPHA * (x - fx)
                    fy += LEVEL_FILTER_ALPHA * (y - fy)
                    fz += LEVEL_FILTER_ALPHA * (z - fz)
                }
                // 读数单位是 m/s²，必须换算成角度再显示
                val r = Math.toDegrees(atan2(fx.toDouble(), sqrt((fy * fy + fz * fz).toDouble()))).toFloat()
                val p = Math.toDegrees(atan2(fy.toDouble(), sqrt((fx * fx + fz * fz).toDouble()))).toFloat()
                roll = r
                pitch = p
                val tilt = hypot(r, p)
                level = if (level) tilt < 1.8f else tilt < 1.0f
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    val rollText by remember { derivedStateOf { String.format(Locale.US, "%+.1f", roll) } }
    val pitchText by remember { derivedStateOf { String.format(Locale.US, "%+.1f", pitch) } }

    val colors = MiuixTheme.colorScheme
    val bubbleColor by animateColorAsState(
        targetValue = if (level) colors.primary else WarnColor,
        animationSpec = tween(durationMillis = 220),
        label = "bubbleColor",
    )
    val ringColor = colors.outline
    val guideColor = colors.dividerLine
    val targetColor = colors.primary
    val plateColor = colors.secondaryContainer

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(300.dp)) {
            val radius = size.minDimension / 2f
            val c = center
            // 底盘
            drawCircle(color = plateColor, radius = radius, center = c)
            // 参考圈 + 十字线
            drawCircle(color = guideColor, radius = radius * 0.66f, center = c, style = Stroke(width = 1.5f))
            drawCircle(color = guideColor, radius = radius * 0.33f, center = c, style = Stroke(width = 1.5f))
            drawLine(guideColor, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), strokeWidth = 1.5f)
            drawLine(guideColor, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), strokeWidth = 1.5f)
            // 外圈
            drawCircle(color = ringColor, radius = radius - 2f, center = c, style = Stroke(width = 4f))
            // 中心靶环：小球进到这里就是水平
            drawCircle(color = targetColor, radius = radius * 0.18f, center = c, style = Stroke(width = 3f))
            // 小球（位置在这里读状态 → 只触发重绘，不触发重组）
            val bubbleX = (roll / LEVEL_MAX_ANGLE).coerceIn(-1f, 1f) * radius * 0.82f
            val bubbleY = -(pitch / LEVEL_MAX_ANGLE).coerceIn(-1f, 1f) * radius * 0.82f
            val bc = Offset(c.x + bubbleX, c.y + bubbleY)
            val br = radius * 0.16f
            drawCircle(color = bubbleColor.copy(alpha = 0.92f), radius = br, center = bc)
            drawCircle(color = Color.White.copy(alpha = 0.28f), radius = br * 0.42f, center = Offset(bc.x - br * 0.3f, bc.y - br * 0.32f))
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (level) "已水平 ✓" else "请调整至水平",
                style = MiuixTheme.textStyles.headline2,
                color = if (level) colors.primary else colors.onSurfaceVariantSummary,
            )
            Text(
                text = "左右 $rollText°   前后 $pitchText°",
                style = MiuixTheme.textStyles.footnote1.tabular(),
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}
