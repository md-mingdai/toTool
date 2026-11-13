<div align="center">

<img src="docs/icon.svg" width="104" alt="toTool" />

# toTool

一款面向 Android 的本地工具箱：45 个日常、文本、计算、设备与图片工具，加上格式转换、条码处理、方程求解和函数绘图。界面采用 Miuix（HyperOS 风格），所有处理都在本机完成，不收集数据、不需要账号。

[![Author](https://img.shields.io/badge/Author-mingdai-green)](https://github.com/md-mingdai) [![Repo](https://img.shields.io/badge/github-repo-blue?logo=github)](https://github.com/md-mingdai/toTool) [![License](https://img.shields.io/badge/License-GPL--3.0-red)](./LICENSE) [![Version](https://img.shields.io/badge/Version-1.1.6-blue)](https://github.com/md-mingdai/toTool/actions)

[功能](#功能) · [条码处理](#条码处理) · [方程求解](#方程求解) · [函数绘图](#函数绘图) · [构建](#构建) · [项目结构](#项目结构) · [许可证](#许可证)

</div>

## 简介

toTool 是一个单 APK 的 Android 工具箱，最低支持 Android 9（API 28）。它把平时需要装好几个 App 才能完成的小事集中在一起，并且坚持两条原则：

- **本地优先**：条码、方程、图片、音视频、设备信息等全部在本机处理，没有云端接口，也不上传文件。
- **风格统一**：所有页面共用同一套 Miuix 组件、配色和转场；支持亮色 / 暗色 / 跟随系统 / 动态取色 / 自定义种子色。

应用字体为 Onest（SIL OFL 1.1），中文自动回退系统字体；标题、按钮和结果卡片都使用同一套字号层级。

## 功能

### 条码处理

「条码处理」包含三个页面：「二维码处理」支持换色、中央图标与相册导出；「条码生成」支持 QR、汉信码、Data Matrix、PDF 417、Code 49 与常见一维条码；「扫描识别」支持摄像头实时扫描和相册图片识别。

| 类型 | 生成 | 扫描 | 备注 |
| --- | :---: | :---: | --- |
| QR 码 | ✅ | ✅ | 支持 L / M / Q / H 容错 |
| 汉信码（Han Xin） | ✅ | ✅ | GB/T 21049 / ISO/IEC 20830；中文按 UTF-8 二进制模式编码 |
| Data Matrix | ✅ | ✅ | ECC 200 |
| PDF 417 | ✅ | ✅ | 可调 0–8 级纠错 |
| Code 49 | ✅ | ✅ | 堆叠式条码，仅支持 ASCII |
| Code 128 / 39 / 93 | ✅ | ✅ | 常用物流、工业条码 |
| ITF / EAN-13 / EAN-8 / UPC-A / Codabar | ✅ | ✅ | 商品与零售条码 |

- **生成**：选择类型、输入内容、按需调整纠错级别，实时预览，一键保存到相册。
- **扫描**：CameraX 实时预览，或从相册选图；常见格式由 ZXing 识别，汉信码与 Code 49 由项目内移植的解码器识别。
- **汉信码 / Code 49**：编码与解码均在本项目内实现（矩阵排布、Reed–Solomon、载荷解析），生成时会做一次编码 → 解码自检，避免输出读不回来的码。
- 相机扫描使用 Telegram 风格取景框：暗色遮罩、四角括号与扫描线；检测与解码分离，先实时框出候选码，解码完成后在框的几何中心显示自适应大小的「查看」。支持多码同时识别，弹窗只在点「查看」时打开，合法 URL 可直接打开。相册模式会先尝试整图，再尝试中间裁剪。
- 汉信码 / Code 49 的识别采用外接框采样，适合清晰、尽量正对镜头的码；严重透视或倾斜时建议先用相册裁剪校正。汉信码中文按 UTF-8 二进制模式生成与识别。

### 方程求解

支持把一元一次、二次以及可化为多项式的分式方程解成**精确形式**：

- 标准形式化简：系数化为互质整数、首项为正、分式约分。
- 根保留分数与最简根号：`x^2 = 2` → `±√2`；`x^2 + x + 1 = 0` → `-1/2 ± (√3/2)i`。
- 显示判别式、方程类型、定义域限制与**增根**（分母为零的根会单独列出并舍去）。
- 含 `sin`、`cos`、`ln`、`√`、`π`、`e` 等非多项式内容，或三次以上方程时，自动回退到数值扫描，只显示实根。
- 输入器为 Desmos 风格：大号公式显示区、可横向滚动的函数键（`sin(`、`cos(`、`ln(`、`√(`、`π`、`e`、`^2`…）和 4×5 数学键盘（数字、括号、四则运算、`=`、退格、光标移动）。

### 函数绘图

- 多条 `y = f(x)` 曲线，自动分配颜色，可显示 / 隐藏、编辑和删除。
- 双指缩放、拖动平移、点按取值；点按后显示各函数在该 x 处的数值。
- 自适应 1 / 2 / 5 网格、坐标轴刻度、渐近线断开、弧度 / 角度切换。
- 表达式支持省略乘号（`2x`、`x(x+1)`）和常见函数、常数。

### 格式转换

独立标签页，基于 FFmpeg：

- 图片、音频、视频格式互转，常用预设与自定义参数。
- 「FFmpeg 命令」模式可直接执行原始命令：输入文件用 `{in1}`…`{in8}` 引用，自动补 `-nostdin / -hide_banner / -y`，实时日志与进度、可随时停止。
- 输出自动保存到「下载/toTool」，超过 50 个文件自动打包为 zip。
- FFmpeg 在 CI 中从源码交叉编译，**纯 LGPL**，四种 CPU 架构各一份。

### 45 个内置工具

| 分类 | 工具 |
| --- | --- |
| 日常工具 | 条码处理、颜色选择器、秒表、手电筒 / SOS、分贝仪、尺子、指南针、量角器、简易画板、时间屏幕、比赛记分牌 |
| 文本工具 | 字符统计、大小写转换、密码生成、UUID、Base64、哈希、JSON 格式化、URL 编解码 |
| 计算工具 | 单位换算、进制转换、百分比、日期计算、贷款计算、方程求解、函数绘图 |
| 设备工具 | 设备信息、电池、网络、传感器、水平仪、提取 APK、振动器、安装 APKS / XAPK / APKM、自定义快捷方式、Shizuku Shell、提取壁纸、视频壁纸、屏幕坏点检测 |
| 图片工具 | 图片压缩、黑白滤镜、像素化、文字水印、毛玻璃、圆角矩形 |

还有：

- **收藏与搜索**：星标常用工具，首页搜索按名称、描述或分类过滤。
- **桌面快捷方式**：每个工具可单独添加到主屏幕，图标与圆角可配置。
- **紧凑模式**：图标 + 名称的密集列表，适合工具较多的用户。
- **统一的工具外壳**：返回、收藏、添加到主屏幕都在同一位置；横屏 / 沉浸式工具由路由统一处理窗口状态和返回动画。

## 隐私与权限

所有功能都在本机完成。应用不包含统计、广告或账号系统，不会把文件、条码内容或设备信息发送到网络。

| 权限 | 用途 |
| --- | --- |
| `CAMERA` | 条码扫描、手电筒 |
| `RECORD_AUDIO` | 分贝仪 |
| `VIBRATE` | 振动器、触感反馈 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 网络信息工具；不用于上传数据 |
| `QUERY_ALL_PACKAGES` | 提取已安装应用的 APK |
| `REQUEST_INSTALL_PACKAGES` | 安装 APKS / XAPK / APKM |
| 媒体读取权限 | 格式转换读取用户选择的图片 / 音频 / 视频 |
| `MANAGE_EXTERNAL_STORAGE` | 提取系统壁纸（Android 13+ 读取真实壁纸所需） |

保存条码、图片或转换结果时通过 MediaStore 写入系统「图片 / 下载」目录；卸载应用不会删除用户已导出的文件。

## 构建

### 环境

- JDK 21（CI 使用 Temurin 21；AGP 9.4.1 + Kotlin 2.4.20）
- Android SDK：compileSdk 37、minSdk 28、targetSdk 36
- Gradle Wrapper（仓库自带）

### 构建 APK

```bash
# 全部架构打成一个 APK（本地调试用）
./gradlew assembleRelease

# 只打某个架构（CI 使用；可选 arm64-v8a / armeabi-v7a / x86_64 / x86）
./gradlew assembleRelease -PabiFilter=arm64-v8a
```

签名通过环境变量注入（`KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）；未配置时回退 debug 签名，APK 仍可安装。

### FFmpeg

`libffmpeg.so` 不提交进仓库，由 CI 从源码交叉编译。本地要使用格式转换功能时：

```bash
bash ffmpeg/build.sh arm64-v8a
# 产物在 ffmpeg/out/<abi>/libffmpeg.so，构建脚本会自动放进 jniLibs
```

版本、校验值与编译参数集中在 `ffmpeg/versions.env`。缺少 `libffmpeg.so` 时仍可构建 APK，只是对应架构上的格式转换不可用（Gradle 会在日志里提示）。

### CI

`.github/workflows/build.yml` 在每次推送到 `main` 时：

1. 按四种 ABI 还原 / 编译 FFmpeg（缓存命中时直接复用）；
2. 为每个 ABI 单独打一个 Release APK；
3. 校验 APK 中包含该架构的 `libffmpeg.so`；
4. 上传 APK 为构建产物。

## 项目结构

```
toTool/
├── app/src/main/kotlin/com/mingdai/toTool/
│   ├── MainActivity.kt
│   ├── data/                     # 工具注册表、收藏、偏好设置
│   │   ├── ToolRegistry.kt       # 首页网格 / 搜索 / 路由的唯一数据源
│   │   └── Prefs.kt
│   ├── math/                     # 精确方程与数值求解
│   │   ├── MathExpression.kt     # 单变量表达式解析、求值、数值根扫描
│   │   ├── Rational.kt           # 任意精度有理数
│   │   ├── Polynomial.kt         # 多项式四则、除法、GCD、格式化
│   │   ├── Surd.kt               # A + B√k 根式与复根格式化
│   │   ├── ExactEquation.kt      # 有理式解析、精确求根、增根检验
│   │   └── EquationSolver.kt     # 数值扫描 + 二分 / 黄金分割
│   ├── barcode/                  # 条码生成与识别
│   │   ├── BarcodeType.kt        # 类型、示例、纠错选项
│   │   ├── BarcodeRenderer.kt    # ZXing 编码 + Bitmap 渲染
│   │   ├── BarcodeScanner.kt     # ZXing + 汉信码 / Code 49 调度
│   │   ├── hanxin/               # 汉信码矩阵 / 载荷 / 采样解码
│   │   └── code49/               # Code 49 编码 / 解码 / 采样
│   └── ui/
│       ├── components/           # MiuixKit、数学键盘、通用卡片、窗口工具
│       ├── screens/
│       │   ├── HomeScreen.kt
│       │   ├── FormatConversionScreen.kt
│       │   ├── ToolRouter.kt
│       │   └── tools/            # 各工具页面（BarcodeTool、EquationSolverTool、
│       │                         # FunctionPlotTool、图片 / 设备 / 日常工具等）
│       ├── theme/                # Miuix 主题、Onest 字体
│       ├── nav/                  # 页面转场与预测性返回
│       └── wallpaper/            # 视频动态壁纸服务
├── app/src/main/assets/licenses/ # 字体、FFmpeg、条码实现的第三方许可
├── ffmpeg/                       # FFmpeg 交叉编译脚本与版本表
├── docs/icon.svg                 # README 图标
├── .github/workflows/build.yml
├── LICENSE                       # GPL-3.0
└── README.md
```

## 实现要点

### 条码引擎

- 常见格式直接使用 ZXing 的 `MultiFormatWriter` / `MultiFormatReader`；扫描时启用 `TRY_HARDER` 与反色识别。
- 汉信码与 Code 49 使用项目内 Kotlin 实现：
  - 汉信码：84 个版本、4 级纠错、掩码评估、结构信息 Reed–Solomon、数字 / 文本 / UTF-8 二进制载荷；
  - Code 49：Full ASCII、数字压缩、行校验与 X/Y/Z 符号校验、2–8 行自动选择。
- 图像识别：对二值图取暗像素外接框，汉信码用四角 7×7 定位图形估算模块大小并采样为 N×N，Code 49 按 70 模块固定宽度逐行采样；支持 90° / 180° / 270° 与行列翻转。
- 生成汉信码 / Code 49 后会立即用项目解码器回读，校验通过才显示预览。

### 方程求解

- 系数用 `BigInteger` 有理数保存，避免 `0.1`、`1/3` 的浮点误差。
- 把方程两边化为有理函数 `P(x)/Q(x)`，约分后按分子次数分类：
  - 一次：直接移项；
  - 二次：判别式 + 提取平方因子，得到分数 / 最简根号 / 共轭复根；
  - 分式方程：用**原始分母**验根，剔除增根并给出定义域提示；
  - 高次或含函数：交给数值扫描（二分 + |f| 局部极小值细化）。
- 输入器使用只读 `BasicTextField` + 自绘数学键盘：不弹出系统输入法，插入位置跟随光标，支持左右移动和清空。

### 绘图与界面

- 绘图使用 Compose Canvas：网格步长取 1 / 2 / 5 × 10ⁿ，坐标轴标签随平移缩放更新；相邻采样点跨渐近线时断开折线。
- 双指缩放围绕手势中心进行，拖动平移保持单位比例；点按位置换算回世界坐标显示函数值。
- 页面转场、方向锁、沉浸式系统栏由 `ToolRouter` 和 `nav/` 统一管理；横屏工具返回时与转场同时恢复竖屏，避免先看到横屏首页。

## 第三方组件

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | HyperOS 风格 Compose 组件 | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) | QR / Data Matrix / PDF 417 / 一维条码 | Apache-2.0 |
| [Zint](https://github.com/zint/zint) / [CodeGlyphX](https://github.com/EvotecIT/CodeGlyphX) | 汉信码、Code 49 实现参考 | BSD-3-Clause / Apache-2.0 |
| FFmpeg | 格式转换 | LGPL-2.1+（关闭 GPL 组件） |
| Onest | 界面字体 | SIL OFL 1.1 |

完整许可见 `app/src/main/assets/licenses/`。本项目的汉信码 / Code 49 代码在移植时保留了原项目的版权与许可声明。

## 许可证

Copyright © 2026 mingdai

本项目以 **GNU General Public License v3.0** 发布，详见 [LICENSE](./LICENSE)。

你可以自由使用、修改和分发，但分发修改后的版本时需要以同一许可证开放源代码。第三方组件仍遵循各自的许可证。

---

如果这个项目对你有帮助，欢迎点一个 Star。
