# ffmpeg/：从源码编译内置的 FFmpeg

toTool 的「格式转换」和「执行原始 ffmpeg 命令」都靠安装包里内置的 FFmpeg 工作。
以前用的是别人编好的预编译包（只有 arm64-v8a），现在改成**构建时从源码编译**：

- 覆盖 Android 的四种 CPU 架构：`arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`，**每个架构各打一个 APK**
- **GPLv3 构建**：启用 GPL 组件（x264）并选择 GPLv3，不启用 nonfree 组件
- FFmpeg 的库和外部库全部**静态链接**进一个可执行文件，只依赖系统自带的库

| 文件 | 作用 |
| --- | --- |
| `build.sh` | 编译脚本：下载源码 → 编外部库 → 编 FFmpeg → 瘦身 → 校验 |
| `versions.env` | FFmpeg、各个库、NDK 的版本，以及来源校验值（提交哈希 / SHA-256） |
| `../.github/workflows/build.yml` | CI：四个架构并行，每个架构编 FFmpeg → 打这个架构的 APK |
| `../app/src/main/assets/licenses/FFMPEG.txt` | App 里「设置 → FFmpeg」显示的许可说明（含 GPL 与各外部库许可信息） |

## 它是怎么工作的

`libffmpeg.so` 虽然叫 `.so`，其实是个可执行文件（PIE）。它放在 `app/src/main/jniLibs/<abi>/` 下，
`packaging.jniLibs.useLegacyPackaging = true` 让系统安装时把它解压到 `nativeLibraryDir`
（Android 10 起，只有这个目录里的文件允许被执行），App 再用 `ProcessBuilder` 把它当命令行程序启动。
所以每个架构打成独立的 APK，APK 里只带这个架构的那一份（手机基本都是 `arm64-v8a`）。

## 在 CI 里编译（日常用法）

什么都不用做，推送代码后 `Build APK` 工作流会为四个架构各开一个任务（并行），每个任务依次：

1. 还原这个架构编好的 `libffmpeg.so`（缓存）；没有缓存就装好固定版本的 NDK，运行 `build.sh <abi>` 编出来
2. 把它放进 `app/src/main/jniLibs/<abi>/`
3. `./gradlew :app:assembleRelease -PabiFilter=<abi>` 打出只含这个架构的 APK，检查里面确实带着 `libffmpeg.so`
4. 上传 APK（名字是 `toTool-v<版本>-<提交>-<架构>`）

要点：

- **Artifacts 里只有 APK**，不会出现 libffmpeg 之类的中间产物（编好的 FFmpeg 只放在 Actions 缓存里）。
- **有缓存**：缓存键是 `build.sh` 和 `versions.env` 的哈希。这两个文件没改，就直接复用上次编好的
  `libffmpeg.so`（只做一次校验），平时改应用代码的提交不会重新编 FFmpeg。第一次、或者改了这两个文件之后，
  每个架构大约要 10～25 分钟（取决于 runner 的核数）。FFmpeg 编完会马上存缓存，之后 Gradle 步骤万一失败，
  重跑也不用再编一遍。
- 四个架构互不影响：某个架构编失败，其它架构照常出 APK（整个流程会标红）。
  推送 `v*` 标签的发布任务要求四个 APK 全部成功才会执行，并把四个 APK 一起附到 Release 上。
- 失败时会上传 `ffmpeg-logs-<abi>`（各组件的 configure / make 完整输出和 `config.log`）和
  `build-reports-<abi>`，排查时先看它们。

## 在本地编译

需要 Linux（含 WSL）和下面这些工具：`bash`（4+）、`git`、`curl`、`tar`、`make`、`cmake`、`pkg-config`、
`nasm`（只有 x86_64 要用）、本机 C 编译器（FFmpeg 交叉编译时要先编几个本机小工具）、`readelf`、`strings`，
以及 Android NDK（r23 或更新，`versions.env` 里固定的版本是 `28.2.13676358`）。

```bash
# 装 NDK（或者在 Android Studio 的 SDK Manager 里装），然后指给脚本；不设置时会到 $ANDROID_HOME/ndk/ 下找
sdkmanager "ndk;28.2.13676358"
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358"

ffmpeg/build.sh arm64-v8a   # 只编一个架构（手机基本都是它）
ffmpeg/build.sh all         # 四个架构依次编
```

- 产物：`ffmpeg/out/<abi>/libffmpeg.so`，旁边的 `build-info.txt` 记录了版本、configure 参数、依赖的系统库。
- 默认编完会顺手复制到 `app/src/main/jniLibs/<abi>/libffmpeg.so`，接着 `./gradlew assembleDebug` 就能打进 APK
  （这个目录已经在 `.gitignore` 里）。不想复制就 `FFMPEG_INSTALL=0`。
- 只想打某一个架构的 APK（和 CI 一样），加上 `-PabiFilter=<abi>`：
  `./gradlew :app:assembleRelease -PabiFilter=arm64-v8a`。不加就把 `jniLibs` 里有的架构都打进同一个 APK，会比较大。
- 只想调试 App、不需要 FFmpeg 时，什么都不用编：Gradle 在构建日志里会提醒缺哪些架构，
  这些架构上格式转换和 ffmpeg 命令会提示不可用，其它功能不受影响。
- `ffmpeg/build.sh host` 用本机编译器编一份能在本机直接运行的版本，只用来快速验证配置和编码器
  （脚本会自己做一次 MP3 / Ogg / Opus / AAC / FLAC / mpeg4 的编码解码自测），不会进 APK。
- `ffmpeg/build.sh verify <abi|all|host>` 只校验已经编好的产物。
- 其它环境变量：`FFMPEG_WORK_DIR`（中间文件，默认 `ffmpeg/work`）、`FFMPEG_OUT_DIR`、`JOBS`，见 `build.sh` 开头。

编译中断后重新运行会接着用已经编好的外部库（每个库有 stamp 文件，脚本或版本改了就自动作废）；
想从头来过，删掉 `ffmpeg/work/` 即可。

## 各架构的编译参数

| ABI | 工具链 | 说明 |
| --- | --- | --- |
| `arm64-v8a` | `aarch64-linux-android28-clang` | `--arch=aarch64 --cpu=armv8-a`，NEON 汇编优化 |
| `armeabi-v7a` | `armv7a-linux-androideabi28-clang` | `--arch=arm --cpu=armv7-a --enable-neon`，`-mfloat-abi=softfp` |
| `x86_64` | `x86_64-linux-android28-clang` | `--x86asmexe=nasm`，汇编优化 |
| `x86` | `i686-linux-android28-clang` | `--disable-asm`：32 位 x86 的手写汇编会产生 text relocation，Android 不允许，所以关掉（只是慢一些） |

所有架构共用：`--enable-small`（按体积优化，见下面的「体积」）；目标 API 28（与 `minSdk` 一致，FFmpeg 的 iconv 需要 28+）；
`-pie`、`-Wl,-z,max-page-size=16384`（Android 15+ 的 16 KB 页大小设备要求 LOAD 段按 16 KB 对齐）；
`--enable-jni --enable-mediacodec`（用 NDK 的 AMediaCodec 做硬件编解码，命令行里不需要 JavaVM）；
`--disable-autodetect`（不随机器环境自动探测外部库，用到什么就显式启用，保证每次编出来的东西一样）。

## 许可证

- configure 使用 `--enable-gpl --enable-version3 --disable-nonfree`；FFmpeg 二进制是 **GPL version 3 or later**。
- 外部库：**LAME**（MP3 编码，LGPL v2.0+）、**Opus**（BSD-3-Clause）、**libvorbis / libogg**（BSD-3-Clause）、
  **x264**（H.264 编码，GPL-2.0+）、**libvpx**（VP8/VP9 编解码，BSD-3-Clause）。
- 没有启用 libx265、libaom、SVT-AV1、libfdk-aac 等体积很大或 nonfree 的组件；H.264/VP8/VP9 由 x264/libvpx 提供，
  mpeg4 仍是兜底编码器。
- `build.sh` 编完会自动检查（`verify_binary`，交叉编译出的二进制在这台机器上跑不了，所以检查的是文件本身）：
  ELF 架构 / PIE / 16 KB 对齐 / 只依赖系统库；二进制里必须有 `GPL version 3 or later`、不能有 nonfree 字样；
  configuration 必须启用 `--enable-gpl` / `--enable-version3`，不能启用 `--enable-nonfree`；
  `--enable-lib*` 必须在白名单（`ALLOWED_LIBS`）里。
- App 里「设置 → FFmpeg」可以看到许可说明、源码地址和替换方法；本目录的脚本 + `versions.env`
  固定的版本，就是可以重新编出同样二进制的“对应源码”。升级版本之后，记得同步改
  `app/src/main/assets/licenses/FFMPEG.txt` 里的版本号与外部库列表。

## 升级 FFmpeg 或外部库

只需要改 `versions.env`：

```bash
# FFmpeg 的标签对应哪个提交（取带 ^{} 的那一行，也就是标签指向的提交）
git ls-remote --tags https://github.com/FFmpeg/FFmpeg.git 'n8.1.3*'
```

把标签和提交哈希一起改掉。脚本拉取源码后会核对哈希，对不上（标签被改动、仓库被替换）会直接报错。
LAME 没有官方 git 仓库，用 tar 包 + SHA-256 固定（并列了几个镜像）。
改完 `versions.env` 后，CI 的缓存键会变，自动重新编译。

## 以后想加别的编码器

编码器需要同时满足体积和许可证要求：

1. `versions.env` 里加版本、来源和校验值
2. `build.sh` 里仿照 `build_x264` / `build_vpx` 写构建函数，在 `build_abi` 里调用，
   并在 `build_ffmpeg` 的 configure 参数里加 `--enable-libxxx`
3. 把库名加进 `ALLOWED_LIBS`，把许可证文本补进 `FFMPEG.txt`
4. 在 `MediaFormats.kt` 的 `FORMAT_SPECS` 里，这个格式需要的编码器名字已经写好了
   （格式转换页会探测 ffmpeg 里有哪些编码器，有才显示对应格式）

当前已经启用 x264（H.264）和 libvpx（VP8/VP9）。x265 / AV1 软编体积较大，默认不启用；
如果确实需要，建议先测量每个 ABI 的 APK 增量再决定。

## 体积

每个架构的 `libffmpeg.so` 在 APK 里是压缩存放的。下面是 arm64 交叉编译的实测（用 GNU 工具链代替 NDK 编的，
NDK 的结果会略有出入；压缩后的数字是按 deflate 估的，约等于装进 APK 后的体积）：

| 构建 | 原始 | 压缩后 |
| --- | --- | --- |
| 以前的预编译包（`--enable-small`，没有 LAME / Opus / Vorbis） | 15.5 MB | 8.1 MB |
| 现在（`--enable-small`，带 LAME / Opus / Vorbis） | 17.2 MB | 8.3 MB |
| 改成按速度优化（`FFMPEG_SMALL=0`，`-O2`） | 19.8 MB | 9.9 MB |

所以默认用 `--enable-small`（`versions.env` 里的 `FFMPEG_SMALL=1`），体积和以前基本一样，代价是转码速度略慢。
想要更快就改成 0，或者本地 `FFMPEG_SMALL=0 ffmpeg/build.sh <abi>` 试一下。

每个架构的 APK 只带自己的那一份，所以单个 APK 和以前差不多大；如果把四个架构塞进同一个 APK，
就会多出三份（三十多 MB），这也是 CI 里每个架构各打一个 APK 的原因。需要再瘦身时可以：

- 用 `--disable-encoder=…` / `--disable-decoder=…` 去掉用不到的编解码器（会让「执行原始 ffmpeg 命令」少一些能力）
- 去掉不需要的架构：`build.yml` 的 `matrix.abi` 和 `build.sh` 的 `ALL_ABIS`（`x86` / `x86_64` 基本只在模拟器上用到）

## 常见问题

- **找不到 NDK**：设置 `ANDROID_NDK_ROOT`，或者把 NDK 装到 `$ANDROID_HOME/ndk/<版本>/`。
- **configure 失败**：脚本会把出错组件的 `config.log` 复制到 `ffmpeg/work/<abi>/logs/`，从里面找原因。
- **下载失败**：FFmpeg 和 Xiph 的库走 GitHub（FFmpeg 另有 git.ffmpeg.org 备用），LAME 有三个镜像，
  任何一个下载成功且校验通过即可。
- **想看这个二进制到底带了什么**：App 里「执行原始 ffmpeg 命令」点「版本信息」「编码器」，
  或者看 `ffmpeg/out/<abi>/build-info.txt`。
