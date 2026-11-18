<div align="center">

<img src="docs/icon.svg" width="104" alt="toTool" />

# toTool

一款面向 Android 的本地工具箱：47 个日常、文本、计算、设备与图片工具，加上格式转换、条码处理、方程求解、函数绘图和隔空闪传。界面采用 Miuix（HyperOS 风格），所有处理都在本机完成，不收集数据、不需要账号。

[![Author](https://img.shields.io/badge/Author-mingdai-green)](https://github.com/md-mingdai) [![Repo](https://img.shields.io/badge/github-repo-blue?logo=github)](https://github.com/md-mingdai/toTool) [![License](https://img.shields.io/badge/License-GPL--3.0-red)](./LICENSE) [![Version](https://img.shields.io/badge/Version-1.2.0-blue)](https://github.com/md-mingdai/toTool/actions)

[功能](#功能) · [实现](#大致实现) · [隐私与权限](#隐私与权限) · [许可证](#许可证)

</div>

## 功能

### 隔空闪传

- 手机作为局域网文件服务器，自动选择可用端口，电脑浏览器或扫码进入文件管理器。
- 浏览手机存储与照片、视频、应用等分类，支持搜索、上传下载、文件夹打包下载、分页、排序、批量操作和远程删除；上传前可在传输队列中确认文件。图片可直接查看，视频和音频可直接播放，1 MiB 以内的文本和源代码可直接预览，Markdown 会渲染显示。
- 配对码配合 token 保持会话；配对码可固定，也可在每次配对成功后自动轮换。
- 可选端到端加密：文件内容使用 AES-256-GCM，密钥不随文件请求传输。
- 网页支持 PJAX 导航、任务进度和断连提示，最近传输里可以删除已上传到手机的文件；手机端分为「连接 / 已接收 / 剪贴板」，已接收文件可删除，剪贴板双向同步。

### 条码处理

- 生成与扫描：QR、汉信码、Data Matrix、PDF 417、Code 49，以及 Code 128 / 39 / 93、ITF、EAN-13 / EAN-8、UPC-A、Codabar。
- 相机实时取景或相册图片识别，支持多码跟踪和横放 / 竖放一维码。
- 汉信码与 Code 49 的编解码在项目内实现，生成时会做编码 → 解码自检。

### 方程求解与函数绘图

- 支持一元一次、二次及可化为多项式的分式方程，给出精确根、定义域和增根提示；无法精确求解时回退数值扫描。
- 方程输入器采用 Desmos 风格；函数绘图支持多曲线、缩放平移、点按取值和渐近线断开。

### 格式转换

- 基于内置 FFmpeg，图片、音频、视频格式互转，支持预设与自定义参数。
- 原始 FFmpeg 命令模式，支持输入占位符、实时日志和进度。
- 输出保存到「下载/toTool」，文件较多时自动打包。

### 内置工具

| 分类 | 工具 |
| --- | --- |
| 日常工具 | 条码处理、颜色选择器、秒表、手电筒 / SOS、分贝仪、尺子、指南针、量角器、简易画板、时间屏幕、比赛记分牌 |
| 文本工具 | 字符统计、大小写转换、密码生成、UUID、Base64、哈希、JSON 格式化、URL 编解码 |
| 计算工具 | 单位换算、进制转换、百分比、日期计算、贷款计算、方程求解、函数绘图 |
| 设备工具 | 设备信息、电池、网络、传感器、水平仪、金属探测器、提取 APK、振动器、安装 APKS / XAPK / APKM、自定义快捷方式、Shizuku Shell、提取壁纸、视频壁纸、屏幕坏点检测、隔空闪传 |
| 图片工具 | 图片压缩、黑白滤镜、像素化、文字水印、毛玻璃、圆角矩形 |

- 收藏、搜索、桌面快捷方式、紧凑模式和统一工具外壳。
- 亮色 / 暗色 / 跟随系统 / 动态取色 / 自定义种子色。

## 大致实现

- 单 APK，Jetpack Compose + Miuix，统一管理页面转场、方向锁和沉浸式系统栏。
- 条码使用 ZXing、CameraX 和项目内的汉信码 / Code 49 编解码器；实时扫描分为快慢路径并跟踪多个码框。
- 方程求解使用任意精度有理数、多项式和根式运算；无法精确求解时转为数值扫描。
- FFmpeg 以 `libffmpeg.so` 形式按 CPU 架构打包，由 CI 从源码交叉编译。
- 隔空闪传网页位于 `web/`，使用 Vite + React + TypeScript；CI 只把 `web/dist` 放进 APK。
- 手机端内嵌 Ktor 服务器，提供静态页面和同源 API；文件通过「所有文件访问权限」读写，上传默认保存到 `Download/toTool/FlashDrop/`。
- 配对码由手机端校验并限速；加密密钥由配对码和随机 salt 派生。网页优先使用 WebSocket 长连接，不支持或断开时回退到 HTTP 探活。

## 隐私与权限

所有功能都在本机完成，不包含统计、广告或账号系统。隔空闪传只在同一局域网内提供网页服务，文件不经过云端。

| 权限 | 用途 |
| --- | --- |
| `CAMERA` / `RECORD_AUDIO` | 条码扫描、手电筒、分贝仪 |
| `VIBRATE` | 振动工具与触感反馈 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 网络信息工具；隔空闪传局域网服务 |
| `QUERY_ALL_PACKAGES` | 提取 APK、展示已安装应用 |
| `REQUEST_INSTALL_PACKAGES` | 安装 APKS / XAPK / APKM |
| 媒体读取权限 | 格式转换读取用户选择的媒体 |
| `MANAGE_EXTERNAL_STORAGE` | 读取真实壁纸；隔空闪传浏览 / 上传 / 下载手机文件 |

> 隔空闪传是局域网 HTTP 服务，配对码和接口请求不经过 TLS；请只在可信 Wi-Fi 下使用。

## 许可证

GPL-3.0。第三方组件与字体许可位于 `app/src/main/assets/licenses/`。
