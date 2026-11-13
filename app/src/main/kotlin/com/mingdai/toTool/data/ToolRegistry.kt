package com.mingdai.toTool.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DataArray
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level tool categories shown on the home screen (order matters for display). */
enum class ToolCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    DAILY("日常工具", "Everyday Tools", Icons.Filled.Brightness5),
    TEXT("文本工具", "Text Tools", Icons.Filled.TextFields),
    CALC("计算工具", "Calc Tools", Icons.Filled.Calculate),
    DEVICE("设备工具", "System Tools", Icons.Filled.Devices),
    IMAGE("图片工具", "Image Tools", Icons.Filled.Photo),
}

/** A single tool entry. */
data class Tool(
    val id: String,
    val name: String,
    val desc: String,
    val category: ToolCategory,
    val icon: ImageVector,
)

/** Central registry of all tools; drives home grid, search, and routing. */
object ToolRegistry {
    val all: List<Tool> = listOf(
        // --- 日常工具 ---
        Tool("daily_qr", "条码处理", "二维码、汉信码、Data Matrix、PDF 417、Code 49 与条形码的生成和扫描", ToolCategory.DAILY, Icons.Filled.QrCode),
        Tool("daily_color", "颜色选择器", "RGB 滑块取色与色值", ToolCategory.DAILY, Icons.Filled.Palette),
        Tool("daily_stopwatch", "秒表", "开始/暂停/分段计时", ToolCategory.DAILY, Icons.Filled.Timer),
        Tool("daily_flash", "手电筒", "常亮照明与 SOS 求救闪烁", ToolCategory.DAILY, Icons.Filled.Highlight),
        Tool("daily_decibel", "分贝仪", "实时测量环境噪音分贝", ToolCategory.DAILY, Icons.Filled.RecordVoiceOver),
        Tool("daily_ruler", "尺子", "屏幕宽度为基准的毫米刻度", ToolCategory.DAILY, Icons.Filled.FitScreen),
        Tool("daily_compass", "指南针", "方向传感器实时罗盘", ToolCategory.DAILY, Icons.Filled.NearMe),
        Tool("daily_protractor", "量角器", "滑动调节，点击角度可精确输入（0.1°）", ToolCategory.DAILY, Icons.Filled.Assessment),
        Tool("daily_whiteboard", "简易画板", "手指绘画与橡皮擦", ToolCategory.DAILY, Icons.Filled.Create),
        Tool("daily_clock", "时间屏幕", "全屏大字时钟与日期", ToolCategory.DAILY, Icons.Filled.ScreenShare),
        Tool("daily_scoreboard", "比赛记分牌", "双方比分与计时", ToolCategory.DAILY, Icons.Filled.SportsScore),
        // --- 文本工具 ---
        Tool("text_charcount", "字符统计", "统计字符、单词、行数与字节数", ToolCategory.TEXT, Icons.Filled.Numbers),
        Tool("text_case", "大小写转换", "大写/小写/标题/句子/反转", ToolCategory.TEXT, Icons.Filled.TextFields),
        Tool("text_password", "密码生成", "生成可配置的随机强密码", ToolCategory.TEXT, Icons.Filled.Lock),
        Tool("text_uuid", "UUID 生成", "生成随机 UUID v4", ToolCategory.TEXT, Icons.Filled.Fingerprint),
        Tool("text_base64", "Base64 编解码", "编码或解码 Base64 字符串", ToolCategory.TEXT, Icons.Filled.Code),
        Tool("text_hash", "哈希计算", "MD5 / SHA-1 / SHA-256 / SHA-512", ToolCategory.TEXT, Icons.Filled.DataObject),
        Tool("text_json", "JSON 格式化", "美化或压缩 JSON", ToolCategory.TEXT, Icons.Filled.DataArray),
        Tool("text_url", "URL 编解码", "编码或解码 URL", ToolCategory.TEXT, Icons.Filled.Link),
        // --- 计算工具 ---
        Tool("calc_unit", "单位换算", "长度/重量/温度/面积/体积/速度/数据", ToolCategory.CALC, Icons.Filled.Straighten),
        Tool("calc_base", "进制转换", "二/八/十/十六进制互转", ToolCategory.CALC, Icons.Filled.DataArray),
        Tool("calc_percent", "百分比计算", "增减、占比、变化率", ToolCategory.CALC, Icons.Filled.Percent),
        Tool("calc_date", "日期计算", "作差求间隔，或推算目标日", ToolCategory.CALC, Icons.Filled.Event),
        Tool("calc_loan", "贷款计算", "等额本息月供与利息", ToolCategory.CALC, Icons.Filled.AttachMoney),
        Tool("calc_equation", "方程求解", "输入含一个未知数的方程，数值扫描求实根", ToolCategory.CALC, Icons.Filled.Functions),
        Tool("calc_plot", "函数绘图", "多条曲线、缩放平移与点按取值", ToolCategory.CALC, Icons.Filled.ShowChart),
        // --- 设备工具 ---
        Tool("dev_device", "设备信息", "机型、系统与硬件信息", ToolCategory.DEVICE, Icons.Filled.PhoneAndroid),
        Tool("dev_battery", "电池信息", "电量、状态与温度", ToolCategory.DEVICE, Icons.Filled.BatteryFull),
        Tool("dev_network", "网络信息", "连接类型与本地 IP", ToolCategory.DEVICE, Icons.Filled.Wifi),
        Tool("dev_sensor", "传感器列表", "列出设备可用传感器", ToolCategory.DEVICE, Icons.Filled.Sensors),
        Tool("dev_level", "水平仪", "气泡水平仪与倾角", ToolCategory.DEVICE, Icons.Filled.LinearScale),
        Tool("dev_apk", "提取 APK", "导出已安装应用安装包，AAB 应用导出为 APKS", ToolCategory.DEVICE, Icons.Filled.Android),
        Tool("dev_vibrate", "振动器", "预设模式循环振动，可调强度，手动停止", ToolCategory.DEVICE, Icons.Filled.Vibration),
        Tool("dev_installer", "安装特殊应用", "安装 APKS / XAPK / APKM 等拆分安装包", ToolCategory.DEVICE, Icons.Filled.Apps),
        Tool("dev_shortcut", "自定义快捷方式", "选择目标 Activity 与图标，创建桌面快捷方式", ToolCategory.DEVICE, Icons.Filled.AddToHomeScreen),
        Tool("dev_shell", "执行 Shell 指令", "通过 Shizuku 执行命令或 .sh 脚本，查看日志", ToolCategory.DEVICE, Icons.Filled.Terminal),
        Tool("dev_wallpaper_get", "提取当前壁纸", "读取系统静态壁纸并导出", ToolCategory.DEVICE, Icons.Filled.Wallpaper),
        Tool("dev_wallpaper_video", "桌面视频壁纸", "将视频设为桌面动态壁纸", ToolCategory.DEVICE, Icons.Filled.VideoLibrary),
        Tool("dev_pixelcheck", "屏幕坏点检测", "纯色与点阵逐格排查", ToolCategory.DEVICE, Icons.Filled.GridView),
        // --- 图片工具 ---
        Tool("img_compress", "图片压缩", "降质量或缩小尺寸减小体积", ToolCategory.IMAGE, Icons.Filled.Compress),
        Tool("img_grayscale", "图片黑白滤镜", "一键黑白转换", ToolCategory.IMAGE, Icons.Filled.InvertColors),
        Tool("img_pixelate", "图片像素化", "调整像素块大小马赛克化", ToolCategory.IMAGE, Icons.Filled.GridOn),
        Tool("img_watermark", "图片水印", "平铺文字水印，可指定斜率与透明度", ToolCategory.IMAGE, Icons.Filled.PhotoLibrary),
        Tool("img_blur", "毛玻璃图生成", "高斯模糊加白色蒙层", ToolCategory.IMAGE, Icons.Filled.BlurOn),
        Tool("img_roundrect", "圆角矩形生成", "图片四角圆角裁切", ToolCategory.IMAGE, Icons.Filled.RoundedCorner),
    )

    val byId: Map<String, Tool> = all.associateBy { it.id }

    /**
     * 被合并或改名的旧工具 id → 现在的 id。
     * 老版本留下的收藏、桌面快捷方式里还存着旧 id，用 [resolve] 统一换成新的。
     */
    private val legacyIds: Map<String, String> = mapOf(
        "daily_sos" to "daily_flash", // SOS 手电筒已并入手电筒
    )

    fun resolve(id: String): String = legacyIds[id] ?: id

    fun byCategory(category: ToolCategory): List<Tool> = all.filter { it.category == category }

    fun search(query: String): List<Tool> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.desc.contains(q, ignoreCase = true) ||
                it.category.title.contains(q, ignoreCase = true)
        }
    }
}
