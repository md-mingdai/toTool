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
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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

/** A two-column label/value row used by the device tools. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 3,
        )
    }
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
        SectionLabel("硬件与系统")
        items.forEach { (k, v) -> InfoRow(k, v) }
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
        SectionLabel("电池")
        val s = snapshot
        if (s == null) {
            Text("正在读取电池信息…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            InfoRow("电量", "${s.percent}%")
            InfoRow("状态", if (s.isCharging) "${s.statusText}（接通电源）" else s.statusText)
            InfoRow("健康", s.healthText)
            InfoRow("电池类型", s.technology)
            InfoRow("温度", "${String.format(Locale.US, "%.1f", s.temperatureC)} ℃")
            InfoRow("电压", if (s.voltageMv > 0) "${s.voltageMv} mV" else "未知")
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
        SectionLabel("网络")
        items.forEach { (k, v) -> InfoRow(k, v) }
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
        SectionLabel("可用传感器（${items.size} 项）")
        if (items.isEmpty()) {
            Text("未检测到任何传感器", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            items.forEach { (k, v) -> InfoRow(k, v) }
        }
    }
}

/* ------------------------------- 18. 水平仪 --------------------------------- */

@Composable
fun SpiritLevelTool(padding: PaddingValues) {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    if (sensor == null) {
        Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("设备没有加速度传感器", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        return
    }

    var roll by remember { mutableStateOf(0f) }   // 左右倾斜角（度）
    var pitch by remember { mutableStateOf(0f) }  // 前后倾斜角（度）

    DisposableEffect(sensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    // 加速度计读数的单位是 m/s²，必须换算成角度再显示（原来直接当“度”显示是错的）
                    val x = event.values[0].toDouble()
                    val y = event.values[1].toDouble()
                    val z = event.values[2].toDouble()
                    roll = Math.toDegrees(kotlin.math.atan2(x, kotlin.math.sqrt(y * y + z * z))).toFloat()
                    pitch = Math.toDegrees(kotlin.math.atan2(y, kotlin.math.sqrt(x * x + z * z))).toFloat()
                }
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }

    val isLevel = kotlin.math.abs(roll) < 1.5f && kotlin.math.abs(pitch) < 1.5f
    val bubbleColor = if (isLevel) MiuixTheme.colorScheme.primary else WarnColor
    val ringColor = MiuixTheme.colorScheme.outline
    val targetColor = MiuixTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(300.dp)) {
            val radius = size.minDimension / 2f
            // outer ring
            drawCircle(
                color = ringColor,
                radius = radius,
                center = center,
                style = Stroke(width = 4f),
            )
            // center target
            drawCircle(
                color = targetColor,
                radius = radius * 0.18f,
                center = center,
                style = Stroke(width = 3f),
            )
            // crosshair
            drawLine(ringColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 1.5f)
            drawLine(ringColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = 1.5f)
            // bubble: clamp to the ring
            val maxAngle = 20f // 倾斜 20° 时气泡到达边缘
            val cx = (roll / maxAngle).coerceIn(-1f, 1f) * radius * 0.85f
            val cy = -(pitch / maxAngle).coerceIn(-1f, 1f) * radius * 0.85f // invert so forward tilt moves bubble down
            drawCircle(
                color = bubbleColor,
                radius = radius * 0.16f,
                center = Offset(center.x + cx, center.y + cy),
            )
        }
        Spacer(Modifier.height(0.dp))
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (isLevel) "已水平 ✓" else "请调整至水平",
                style = MiuixTheme.textStyles.headline2,
                color = if (isLevel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "左右 ${String.format(Locale.US, "%+.1f", roll)}°   前后 ${String.format(Locale.US, "%+.1f", pitch)}°",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
