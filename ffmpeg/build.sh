#!/usr/bin/env bash
# =============================================================================
# 从源码交叉编译 FFmpeg（纯 LGPL），产出要打进 APK 的 libffmpeg.so。
#
# libffmpeg.so 其实是个可执行文件：FFmpeg 自己的全部库（libavcodec 等）和下面这几个
# 外部库——LAME（MP3）、Opus、Vorbis、Ogg——都静态链接进去，只动态依赖 Android 系统自带的
# libc / libm / libdl / libz / libandroid / libmediandk。它放在 jniLibs/<abi>/ 下，
# 安装时被解压到 nativeLibraryDir，应用再用 ProcessBuilder 启动它
# （Android 10+ 只有这个目录里的文件允许被执行）。
#
# 许可证：configure 不带 --enable-gpl / --enable-nonfree / --enable-version3，
# 所以整个 libffmpeg.so 是 LGPL-2.1+。不要往里加 libx264 / libx265 / libxvid / libfdk-aac 这类库。
# 编完会自动检查（见 verify_binary），configuration 里出现不在白名单里的外部库就报错。
#
# 用法：
#   ffmpeg/build.sh arm64-v8a      编一个 ABI（armeabi-v7a / x86_64 / x86 同理）
#   ffmpeg/build.sh all            依次编这四个 ABI
#   ffmpeg/build.sh host           用本机编译器编一份能在本机直接运行的版本，
#                                  只用来快速验证配置和编码器，不会进 APK
#   ffmpeg/build.sh verify <abi>   只校验已经编好的产物（ELF 格式、16 KB 对齐、LGPL）
#
# 环境变量（都可选）：
#   ANDROID_NDK_ROOT   NDK 目录（r23 或更新）。不设置时到 $ANDROID_HOME/ndk/ 下找
#   FFMPEG_WORK_DIR    中间文件目录，默认 ffmpeg/work
#   FFMPEG_OUT_DIR     产物目录，默认 ffmpeg/out，产物是 <FFMPEG_OUT_DIR>/<abi>/libffmpeg.so
#   FFMPEG_INSTALL     默认 1：编完把 libffmpeg.so 顺手复制到 app/src/main/jniLibs/<abi>/，
#                      这样接着 ./gradlew assembleDebug 就能打进 APK；设成 0 则不复制（CI 里就是 0）
#   FFMPEG_JNILIBS_DIR 上面复制的目标目录，默认 app/src/main/jniLibs
#   JOBS               并行编译数，默认 CPU 核数
#
# 需要 Linux（含 WSL）和：bash 4+、git、curl、tar、make、cmake、pkg-config、nasm（x86_64 用）、
# 本机 C 编译器（FFmpeg 交叉编译时要编几个本机小工具）、readelf、strings。
# 版本号、来源和校验值都在 versions.env 里。详细说明见 ffmpeg/README.md。
# =============================================================================

set -Eeuo pipefail
trap 'echo "build.sh：第 ${LINENO} 行出错（退出码 $?）" >&2' ERR

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ffmpeg/versions.env
source "$HERE/versions.env"

ALL_ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
WORK_ROOT="${FFMPEG_WORK_DIR:-$HERE/work}"
OUT_ROOT="${FFMPEG_OUT_DIR:-$HERE/out}"
JNILIBS_ROOT="${FFMPEG_JNILIBS_DIR:-$HERE/../app/src/main/jniLibs}"
SRC_ROOT="$WORK_ROOT/src"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"

# libffmpeg.so 允许动态依赖的系统库（都是 NDK 稳定 API，所有 Android 设备上都有）。
SYSTEM_LIBS="libc.so libm.so libdl.so libz.so liblog.so libandroid.so libmediandk.so"
# configuration 里允许出现的外部库。出现别的 --enable-lib* 就判定失败，
# 防止以后有人顺手加了个 GPL 库却没发现。
ALLOWED_LIBS="libmp3lame libopus libvorbis"
# Android 15 起有 16 KB 页大小的设备，可执行文件的 LOAD 段必须按 16 KB 对齐。
PAGE_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

# 脚本或版本一改，之前编好的外部库就作废（见 stamp_*）。
SCRIPT_HASH="$(cat "$HERE/build.sh" "$HERE/versions.env" | sha256sum | cut -c1-12)"

# --------------------------------- 小工具 -------------------------------------

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m警告:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

group_start() {
    if [ "${GITHUB_ACTIONS:-}" = true ]; then echo "::group::$*"; else log "$*"; fi
}
group_end() {
    if [ "${GITHUB_ACTIONS:-}" = true ]; then echo "::endgroup::"; fi
}

usage() {
    sed -n '2,/^# ====/p' "$HERE/build.sh" | sed 's/^# \{0,1\}//' | sed '$d'
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

in_dir() { local d="$1"; shift; ( cd "$d" && "$@" ); }

retry() {   # retry <次数> <命令…>
    local n="$1" i
    shift
    for ((i = 1; i <= n; i++)); do
        if "$@"; then return 0; fi
        if [ "$i" -lt "$n" ]; then warn "第 $i 次失败，5 秒后重试：$*"; sleep 5; fi
    done
    return 1
}

# 运行一条命令，输出写进日志文件；失败时打出日志末尾，并把这个组件的 config.log 之类留一份方便排查。
# 步骤名约定为 <组件>-<阶段>，比如 ffmpeg-configure、lame-build，组件对应 $BUILD_DIR/<组件>。
run() {
    local name="$1" comp f flat
    shift
    comp="${name%%-*}"
    local logfile="$LOG_DIR/$name.log"
    log "$name"
    if ! "$@" >"$logfile" 2>&1; then
        echo "------ $name 失败，日志末尾（完整日志：$logfile）------" >&2
        tail -n 60 "$logfile" >&2
        for f in "$BUILD_DIR/$comp/config.log" "$BUILD_DIR/$comp/ffbuild/config.log" \
                 "$BUILD_DIR/$comp/CMakeFiles/CMakeError.log" "$BUILD_DIR/$comp/CMakeFiles/CMakeOutput.log"; do
            [ -f "$f" ] || continue
            flat="${f#"$BUILD_DIR"/}"
            cp -f "$f" "$LOG_DIR/${flat//\//_}"
            echo "------ $f（末尾 40 行）------" >&2
            tail -n 40 "$f" >&2
        done
        die "$name 失败"
    fi
}

stamp_file() { echo "$PREFIX/.stamp-$1-$SCRIPT_HASH"; }
stamp_ok()   { [ -f "$(stamp_file "$1")" ]; }
stamp_set()  { : >"$(stamp_file "$1")"; }

# ---------------------------------- 下载源码 ----------------------------------

clone_fresh() { rm -rf "$3"; git -c advice.detachedHead=false clone -q --depth 1 --branch "$2" "$1" "$3"; }

# fetch_git <名字> <标签> <提交哈希> <git 地址列表>  → 源码目录放在 FETCH_RESULT
fetch_git() {
    local name="$1" tag="$2" commit="$3" urls="$4"
    local dir="$SRC_ROOT/$name-$tag" url got
    FETCH_RESULT="$dir"
    if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" = "$commit" ]; then
        return 0
    fi
    mkdir -p "$SRC_ROOT"
    for url in $urls; do
        log "下载 $name $tag：$url"
        if retry 3 clone_fresh "$url" "$tag" "$dir"; then
            got="$(git -C "$dir" rev-parse HEAD)"
            [ "$got" = "$commit" ] ||
                die "$name $tag 的提交哈希对不上（期望 $commit，实际 $got），拒绝使用"
            return 0
        fi
        warn "$url 下载失败，换下一个地址"
    done
    die "$name $tag 下载失败"
}

# fetch_tarball <名字> <版本> <sha256> <镜像列表>  → 解压后的目录放在 FETCH_RESULT
fetch_tarball() {
    local name="$1" version="$2" sha="$3" urls="$4"
    local dir="$SRC_ROOT/$name-$version" tgz="$SRC_ROOT/$name-$version.tar.gz" url
    FETCH_RESULT="$dir"
    [ -f "$dir/.fetched" ] && return 0
    mkdir -p "$SRC_ROOT"
    if [ ! -f "$tgz" ] || [ "$(sha256_of "$tgz")" != "$sha" ]; then
        rm -f "$tgz"
        for url in $urls; do
            log "下载 $name $version：$url"
            if retry 3 curl -fsSL --connect-timeout 20 --max-time 600 -o "$tgz.part" "$url" &&
               [ "$(sha256_of "$tgz.part")" = "$sha" ]; then
                mv "$tgz.part" "$tgz"
                break
            fi
            warn "下载失败或 SHA-256 不符，换下一个镜像"
            rm -f "$tgz.part"
        done
    fi
    [ -f "$tgz" ] || die "$name $version：所有镜像都下载失败（或 SHA-256 不符）"
    rm -rf "$dir"
    mkdir -p "$dir"
    tar -xzf "$tgz" -C "$dir" --strip-components=1
    : >"$dir/.fetched"
}

fetch_sources() {
    group_start "下载源码"
    fetch_git ffmpeg "$FFMPEG_TAG" "$FFMPEG_COMMIT" "$FFMPEG_GIT_URLS"; FFMPEG_SRC="$FETCH_RESULT"
    fetch_tarball lame "$LAME_VERSION" "$LAME_SHA256" "$LAME_URLS";     LAME_SRC="$FETCH_RESULT"
    fetch_git opus "$OPUS_TAG" "$OPUS_COMMIT" "$OPUS_GIT_URLS";         OPUS_SRC="$FETCH_RESULT"
    fetch_git ogg "$OGG_TAG" "$OGG_COMMIT" "$OGG_GIT_URLS";             OGG_SRC="$FETCH_RESULT"
    fetch_git vorbis "$VORBIS_TAG" "$VORBIS_COMMIT" "$VORBIS_GIT_URLS"; VORBIS_SRC="$FETCH_RESULT"
    group_end
}

# ---------------------------------- 工具链 ------------------------------------

find_ndk() {
    local c sdk newest
    for c in "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK:-}"; do
        if [ -n "$c" ] && [ -d "$c/toolchains/llvm/prebuilt" ]; then echo "$c"; return 0; fi
    done
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] || continue
        if [ -d "$sdk/ndk/$NDK_VERSION/toolchains/llvm/prebuilt" ]; then echo "$sdk/ndk/$NDK_VERSION"; return 0; fi
    done
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
        [ -n "$sdk" ] && [ -d "$sdk/ndk" ] || continue
        newest="$(ls -1 "$sdk/ndk" 2>/dev/null | sort -V | tail -n1)"
        if [ -n "$newest" ] && [ -d "$sdk/ndk/$newest/toolchains/llvm/prebuilt" ]; then
            echo "$sdk/ndk/$newest"; return 0
        fi
    done
    return 1
}

# 下面这些全局变量由 setup_*_toolchain 填好，后面的 build_* 直接用：
#   CC CXX AR RANLIB NM STRIP      编译工具
#   TC_HOST                        autotools 的 --host（空 = 本机编译）
#   DEP_CFLAGS                     外部库的 CFLAGS
#   CMAKE_TARGET_ARGS              外部库用 CMake 编时的目标平台参数
#   FF_TARGET_ARGS / FF_EXTRA_CFLAGS   FFmpeg configure 的目标平台参数
setup_android_toolchain() {
    local abi="$1" host_tag clang_triple abi_args=() arch_cflags=""
    NDK="$(find_ndk)" ||
        die "找不到 Android NDK。请安装 NDK ${NDK_VERSION}（sdkmanager \"ndk;${NDK_VERSION}\"）并设置 ANDROID_NDK_ROOT 指向它。"
    host_tag="$(ls -1 "$NDK/toolchains/llvm/prebuilt" | head -n1)"
    local bin="$NDK/toolchains/llvm/prebuilt/$host_tag/bin"
    local sysroot="$NDK/toolchains/llvm/prebuilt/$host_tag/sysroot"

    case "$abi" in
        arm64-v8a)
            TC_HOST=aarch64-linux-android
            clang_triple=aarch64-linux-android
            abi_args=(--arch=aarch64 --cpu=armv8-a)
            ;;
        armeabi-v7a)
            TC_HOST=arm-linux-androideabi
            clang_triple=armv7a-linux-androideabi
            arch_cflags="-march=armv7-a -mfpu=neon -mfloat-abi=softfp"
            abi_args=(--arch=arm --cpu=armv7-a --enable-neon)
            ;;
        x86_64)
            TC_HOST=x86_64-linux-android
            clang_triple=x86_64-linux-android
            abi_args=(--arch=x86_64 --x86asmexe=nasm)
            ;;
        x86)
            TC_HOST=i686-linux-android
            clang_triple=i686-linux-android
            # 32 位 x86 的手写汇编会产生 text relocation，Android 不允许，所以关掉汇编（只影响速度）。
            abi_args=(--arch=x86 --cpu=i686 --disable-asm)
            ;;
        *) die "不支持的 ABI：$abi（可选：${ALL_ABIS[*]}）" ;;
    esac

    CC="$bin/${clang_triple}${ANDROID_API}-clang"
    CXX="$bin/${clang_triple}${ANDROID_API}-clang++"
    AR="$bin/llvm-ar"; RANLIB="$bin/llvm-ranlib"; NM="$bin/llvm-nm"; STRIP="$bin/llvm-strip"
    [ -x "$CC" ] || die "找不到 $CC。NDK 太旧？需要 r23 或更新的 NDK（只有 clang，没有 GCC）。"
    [ -x "$AR" ] || die "找不到 $AR。NDK 目录不完整？"

    DEP_CFLAGS="-O2 -fPIC $arch_cflags"
    CMAKE_TARGET_ARGS=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi"
        -DANDROID_PLATFORM="android-$ANDROID_API"
        -DANDROID_ARM_NEON=ON
    )
    FF_EXTRA_CFLAGS="$arch_cflags"
    FF_TARGET_ARGS=(
        --enable-cross-compile --target-os=android "${abi_args[@]}"
        --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP"
        --sysroot="$sysroot"
        # MediaCodec 硬件编解码（走 NDK 的 AMediaCodec，命令行里不需要 JavaVM）。android_camera 用不上，
        # 关掉它就不会再依赖 libcamera2ndk.so。
        --enable-jni --enable-mediacodec --disable-indev=android_camera
        --extra-ldexeflags="-pie $PAGE_LDFLAGS"
    )
}

setup_host_toolchain() {
    TC_HOST=""
    CC="${CC:-cc}"; CXX="${CXX:-c++}"
    AR="${AR:-ar}"; RANLIB="${RANLIB:-ranlib}"; NM="${NM:-nm}"; STRIP="${STRIP:-strip}"
    DEP_CFLAGS="-O2 -fPIC"
    CMAKE_TARGET_ARGS=()
    FF_EXTRA_CFLAGS=""
    FF_TARGET_ARGS=()
}

# ---------------------------------- 外部库 ------------------------------------

build_lame() {
    stamp_ok "lame-$LAME_VERSION" && { log "LAME 已编译，跳过"; return 0; }
    local bdir="$BUILD_DIR/lame" conf
    rm -rf "$bdir"; mkdir -p "$bdir"
    conf=("$LAME_SRC/configure" --prefix="$PREFIX" --disable-shared --enable-static --with-pic
          --disable-frontend --disable-decoder --disable-gtktest)
    [ -n "$TC_HOST" ] && conf+=(--host="$TC_HOST")
    run lame-configure in_dir "$bdir" env CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
        CFLAGS="$DEP_CFLAGS" "${conf[@]}"
    run lame-build in_dir "$bdir" make -j"$JOBS"
    run lame-install in_dir "$bdir" make install
    stamp_set "lame-$LAME_VERSION"
}

# cmake_lib <名字> <源码目录> [额外的 cmake 参数…]
cmake_lib() {
    local name="$1" src="$2" bdir
    shift 2
    bdir="$BUILD_DIR/$name"
    rm -rf "$bdir"
    run "$name-configure" cmake -S "$src" -B "$bdir" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_PREFIX_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        "${CMAKE_TARGET_ARGS[@]}" "$@"
    run "$name-build" cmake --build "$bdir" --parallel "$JOBS"
    run "$name-install" cmake --install "$bdir"
}

build_ogg() {
    stamp_ok "ogg-$OGG_TAG" && { log "Ogg 已编译，跳过"; return 0; }
    cmake_lib ogg "$OGG_SRC" -DBUILD_TESTING=OFF -DINSTALL_DOCS=OFF
    stamp_set "ogg-$OGG_TAG"
}

build_vorbis() {
    stamp_ok "vorbis-$VORBIS_TAG" && { log "Vorbis 已编译，跳过"; return 0; }
    # 直接告诉它 libogg 在哪，不依赖 CMake 的 find_package 去搜（Android 工具链会限制搜索范围）
    cmake_lib vorbis "$VORBIS_SRC" -DBUILD_TESTING=OFF \
        -DOGG_INCLUDE_DIR="$PREFIX/include" -DOGG_LIBRARY="$PREFIX/lib/libogg.a"
    stamp_set "vorbis-$VORBIS_TAG"
}

build_opus() {
    stamp_ok "opus-$OPUS_TAG" && { log "Opus 已编译，跳过"; return 0; }
    cmake_lib opus "$OPUS_SRC" -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF \
        -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF
    stamp_set "opus-$OPUS_TAG"
}

# ---------------------------------- FFmpeg ------------------------------------

build_ffmpeg() {
    local bdir="$BUILD_DIR/ffmpeg" args
    rm -rf "$bdir"; mkdir -p "$bdir"
    args=(
        "$FFMPEG_SRC/configure"
        --prefix="$PREFIX/ffmpeg"
        # 许可证：纯 LGPL。这三项本来就是默认值，显式写出来是为了让 configuration 字符串里留下记录。
        --disable-gpl --disable-nonfree --disable-version3
        # 只要 ffmpeg 命令行程序；所有库都静态链接进去
        --enable-static --disable-shared
        --enable-ffmpeg --disable-ffplay --disable-ffprobe
        --disable-doc --disable-htmlpages --disable-manpages --disable-podpages --disable-txtpages
        --disable-debug --disable-stripping
        # 不自动探测外部库，用到什么就显式 --enable-xxx，这样构建结果才不会随机器环境变化
        --disable-autodetect
        --enable-pic --enable-runtime-cpudetect
        --enable-zlib --enable-iconv
        --enable-libmp3lame --enable-libopus --enable-libvorbis
        --pkg-config-flags=--static
        --optflags=-O2
        --extra-cflags="-I$PREFIX/include $FF_EXTRA_CFLAGS"
        --extra-ldflags="-L$PREFIX/lib"
        # libvorbis.pc 里没写 -lm，而静态的 libvorbis.a 要用 sin / cos / exp 这些数学函数，
        # 不加的话 configure 里探测 libvorbis 时会链接失败。
        --extra-libs=-lm
        "${FF_TARGET_ARGS[@]}"
    )
    # 只在 PREFIX 里找外部库的 .pc，不要误用本机（或 NDK 外）装的同名库
    run ffmpeg-configure in_dir "$bdir" env PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH= "${args[@]}"
    run ffmpeg-build in_dir "$bdir" make -j"$JOBS"
}

# 把编好的 ffmpeg 去掉符号表，改名成 libffmpeg.so，并写一份 build-info.txt。
package_output() {
    local bin="$OUT_DIR/libffmpeg.so" ndk_rev="-" conf
    mkdir -p "$OUT_DIR"
    cp -f "$BUILD_DIR/ffmpeg/ffmpeg" "$bin"
    "$STRIP" --strip-all "$bin"
    chmod 755 "$bin"
    [ -n "${NDK:-}" ] && [ -f "$NDK/source.properties" ] &&
        ndk_rev="$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")"
    conf="$(sed -n 's/^#define FFMPEG_CONFIGURATION "\(.*\)"$/\1/p' "$BUILD_DIR/ffmpeg/config.h" | head -n1)"
    {
        echo "ABI:        $ABI"
        echo "FFmpeg:     $FFMPEG_TAG ($FFMPEG_COMMIT)"
        echo "LAME:       $LAME_VERSION"
        echo "Opus:       $OPUS_TAG"
        echo "Ogg:        $OGG_TAG"
        echo "Vorbis:     $VORBIS_TAG"
        echo "NDK:        $ndk_rev (API $ANDROID_API)"
        echo "License:    LGPL version 2.1 or later"
        echo "Size:       $(wc -c <"$bin") bytes"
        echo "SHA-256:    $(sha256_of "$bin")"
        echo "configure:  $conf"
    } >"$OUT_DIR/build-info.txt"
}

# ---------------------------------- 校验 --------------------------------------

pick_tool() {   # pick_tool <名字…>：返回第一个存在的命令
    local t
    for t in "$@"; do
        if command -v "$t" >/dev/null 2>&1; then echo "$t"; return 0; fi
    done
    return 1
}

bin_strings() {
    if command -v strings >/dev/null 2>&1; then strings -a "$1"; else LC_ALL=C grep -a -o '[[:print:]]\{6,\}' "$1"; fi
}

# verify_binary <abi> <文件>：检查 ELF 格式、16 KB 对齐、系统库依赖，以及许可证。任何一项不对就报错。
verify_binary() {
    local abi="$1" bin="$2" ok=1
    local readelf
    readelf="$(pick_tool readelf llvm-readelf)" || die "校验需要 readelf（binutils）"
    fail() { echo "  ✘ $*"; ok=0; }
    pass() { echo "  ✔ $*"; }

    [ -f "$bin" ] || die "找不到 $bin"
    log "校验 $abi：$bin"

    local size hdr dyn seg strs
    size="$(wc -c <"$bin")"
    hdr="$("$readelf" -h "$bin")"
    dyn="$("$readelf" -d "$bin" 2>/dev/null || true)"
    seg="$("$readelf" -lW "$bin")"
    strs="$(mktemp)"
    bin_strings "$bin" >"$strs"

    # ---- ELF（只对 Android 产物检查）----
    if [ "$abi" != host ]; then
        local machine interp
        case "$abi" in
            arm64-v8a)   machine='AArch64';  interp=/system/bin/linker64 ;;
            armeabi-v7a) machine='ARM';      interp=/system/bin/linker ;;
            x86_64)      machine='X86-64';   interp=/system/bin/linker64 ;;
            x86)         machine='80386';    interp=/system/bin/linker ;;
            *) die "未知 ABI：$abi" ;;
        esac
        if grep -Eq "Machine:[[:space:]]+.*${machine}\$" <<<"$hdr"; then pass "架构是 $machine"; else fail "架构不是 $machine"; fi
        if grep -Eq 'FLAGS_1.*PIE' <<<"$dyn"; then pass "PIE 可执行文件"; else fail "不是 PIE（Android 不允许运行非 PIE 的动态可执行文件）"; fi
        if grep -Fq "[Requesting program interpreter: $interp]" <<<"$seg"; then pass "动态链接器 $interp"; else fail "动态链接器不是 $interp"; fi

        local lib
        while read -r lib; do
            case " $SYSTEM_LIBS " in
                *" $lib "*) ;;
                *) fail "依赖了不该依赖的库：$lib" ;;
            esac
        done < <(sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' <<<"$dyn")
        pass "动态依赖：$(sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' <<<"$dyn" | tr '\n' ' ')"

        local align bad=0
        while read -r align; do
            if (( align < 16384 )); then bad=1; fi
        done < <(awk '$1=="LOAD"{print $NF}' <<<"$seg")
        if [ "$bad" = 0 ]; then pass "LOAD 段按 16 KB 对齐"; else fail "LOAD 段对齐小于 16 KB，16 KB 页大小的设备上会无法运行"; fi
    fi

    # ---- 许可证 ----
    if grep -Fq 'LGPL version 2.1 or later' "$strs"; then pass "许可证：LGPL version 2.1 or later"; else fail "二进制里找不到 \"LGPL version 2.1 or later\""; fi
    if grep -Eq '(^|[^L])GPL version|LGPL version 3|nonfree and unredistributable' "$strs"; then
        fail "二进制里出现了 GPL / LGPLv3 / nonfree 的许可证字样"
    else
        pass "没有 GPL / nonfree 字样"
    fi
    if grep -Eq -- '--enable-(gpl|nonfree|version3)' "$strs"; then fail "configuration 里有 --enable-gpl / --enable-nonfree / --enable-version3"; else pass "configuration 没有 gpl / nonfree / version3"; fi
    local libs l
    libs="$(grep -o -- '--enable-lib[a-z0-9_]*' "$strs" | sort -u | sed 's/^--enable-//' || true)"
    for l in $libs; do
        case " $ALLOWED_LIBS " in
            *" $l "*) ;;
            *) fail "configuration 里有不在白名单里的外部库：$l（白名单：$ALLOWED_LIBS）" ;;
        esac
    done
    pass "外部库：$(echo "$libs" | tr '\n' ' ')"

    if (( size > 2000000 )); then pass "大小 $((size / 1024)) KB"; else fail "文件只有 $size 字节，不正常"; fi
    rm -f "$strs"
    [ "$ok" = 1 ] || die "$abi 的产物没有通过校验"
}

# 只有 host 版本能在本机运行：检查编码器，并实际编码、解码几个文件。
selftest_host() {
    local bin="$1" t enc e
    t="$(mktemp -d)"
    log "自测（host）"
    "$bin" -hide_banner -version | head -n 3
    enc="$("$bin" -hide_banner -encoders)"
    for e in libmp3lame libopus libvorbis aac flac mpeg4; do
        grep -Eq "^ [VAS][.A-Z]{5} +$e " <<<"$enc" || die "缺少编码器：$e"
    done
    local sine=(-f lavfi -i "sine=frequency=440:duration=1")
    "$bin" -hide_banner -loglevel error "${sine[@]}" -c:a libmp3lame -q:a 2 "$t/a.mp3"
    "$bin" -hide_banner -loglevel error "${sine[@]}" -c:a libvorbis -q:a 5 "$t/a.ogg"
    "$bin" -hide_banner -loglevel error "${sine[@]}" -c:a libopus -b:a 96k "$t/a.opus"
    "$bin" -hide_banner -loglevel error "${sine[@]}" -c:a aac -b:a 128k "$t/a.m4a"
    "$bin" -hide_banner -loglevel error "${sine[@]}" -c:a flac "$t/a.flac"
    "$bin" -hide_banner -loglevel error -f lavfi -i "testsrc=size=320x240:rate=15:duration=1" \
        -c:v mpeg4 -q:v 3 -pix_fmt yuv420p "$t/v.mp4"
    for e in a.mp3 a.ogg a.opus a.m4a a.flac v.mp4; do
        [ -s "$t/$e" ] || die "生成的文件是空的：$e"
        "$bin" -hide_banner -v error -i "$t/$e" -f null - || die "生成的文件无法解码：$e"
        echo "  ✔ $e（$(wc -c <"$t/$e") 字节）"
    done
    rm -rf "$t"
}

# --------------------------------- 主流程 -------------------------------------

build_abi() {
    ABI="$1"
    BUILD_ROOT="$WORK_ROOT/$ABI"
    PREFIX="$BUILD_ROOT/prefix"
    BUILD_DIR="$BUILD_ROOT/build"
    LOG_DIR="$BUILD_ROOT/logs"
    OUT_DIR="$OUT_ROOT/$ABI"
    mkdir -p "$PREFIX" "$BUILD_DIR" "$LOG_DIR" "$OUT_DIR"

    case "$(uname -s)" in Linux) ;; *) die "只支持在 Linux（含 WSL）上编译" ;; esac
    local t
    for t in git curl tar make cmake pkg-config sha256sum; do
        command -v "$t" >/dev/null 2>&1 || die "缺少命令：$t"
    done

    if [ "$ABI" = host ]; then
        setup_host_toolchain
    else
        setup_android_toolchain "$ABI"
        if [ "$ABI" = x86_64 ]; then command -v nasm >/dev/null 2>&1 || die "x86_64 需要 nasm（apt install nasm）"; fi
    fi

    log "编译 $ABI（FFmpeg $FFMPEG_TAG，$JOBS 个并行任务）"
    log "工作目录：$BUILD_ROOT"
    fetch_sources

    group_start "外部库：LAME / Ogg / Vorbis / Opus"
    build_lame
    build_ogg
    build_vorbis
    build_opus
    group_end

    group_start "FFmpeg"
    build_ffmpeg
    group_end

    package_output
    verify_binary "$ABI" "$OUT_DIR/libffmpeg.so"
    if [ "$ABI" = host ]; then selftest_host "$OUT_DIR/libffmpeg.so"; fi

    log "完成：$OUT_DIR/libffmpeg.so（$(( $(wc -c <"$OUT_DIR/libffmpeg.so") / 1024 )) KB）"
    if [ "$ABI" != host ] && [ "${FFMPEG_INSTALL:-1}" != 0 ]; then
        mkdir -p "$JNILIBS_ROOT/$ABI"
        cp -f "$OUT_DIR/libffmpeg.so" "$JNILIBS_ROOT/$ABI/libffmpeg.so"
        log "已复制到 $JNILIBS_ROOT/$ABI/libffmpeg.so（不想复制就设 FFMPEG_INSTALL=0）"
    fi
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            echo "### FFmpeg ($ABI)"
            echo '```'
            cat "$OUT_DIR/build-info.txt"
            echo '```'
        } >>"$GITHUB_STEP_SUMMARY"
    fi
}

main() {
    local cmd="${1:-}" abi
    case "$cmd" in
        ""|-h|--help|help)
            usage
            ;;
        verify)
            shift
            [ $# -gt 0 ] || die "用法：build.sh verify <abi|all|host>"
            local targets=("$@")
            [ "${targets[0]}" = all ] && targets=("${ALL_ABIS[@]}")
            for abi in "${targets[@]}"; do
                verify_binary "$abi" "$OUT_ROOT/$abi/libffmpeg.so"
            done
            ;;
        all)
            for abi in "${ALL_ABIS[@]}"; do build_abi "$abi"; done
            ;;
        host|arm64-v8a|armeabi-v7a|x86_64|x86)
            build_abi "$cmd"
            ;;
        *)
            usage
            die "不认识的参数：$cmd"
            ;;
    esac
}

# 直接执行时才跑主流程；被 source 进来（比如写测试）时只加载函数。
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
