plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin，无需 org.jetbrains.kotlin.android；Compose 编译器插件版本与 Kotlin 一致（见根 build.gradle.kts）
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 架构 ------------------------------------------------------------------------
// 内置的 ffmpeg 是个可执行文件，以 libffmpeg.so 的名字放在 src/main/jniLibs/<abi>/，
// 由 ffmpeg/build.sh 从源码编译（不提交进仓库），每个架构一份、各约十几 MB。
// 所以发布时每个架构各打一个 APK：加上 -PabiFilter=<abi> 就只打这一个架构（CI 里就是这样打的），
// 不传的话，jniLibs 里有哪些架构就都打进同一个 APK（本地调试用，会比较大）。
val ffmpegAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val abiFilter: String = providers.gradleProperty("abiFilter").orNull?.trim().orEmpty()
if (abiFilter.isNotEmpty() && abiFilter !in ffmpegAbis) {
    throw GradleException("abiFilter 只能是 ${ffmpegAbis.joinToString()} 之一，现在是“$abiFilter”")
}

android {
    namespace = "com.mingdai.toTool"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mingdai.toTool"
        minSdk = 28
        targetSdk = 36
        versionCode = 46
        versionName = "1.1.6"

        // 只打一个架构的 APK（见上面 abiFilter 的说明）；不限定时包含全部架构
        if (abiFilter.isNotEmpty()) {
            ndk {
                abiFilters += abiFilter
            }
        }
    }

    // 签名：CI（GitHub Actions）通过环境变量传入固定的 release 密钥，
    // 这样每次构建的签名一致，新版 APK 才能直接覆盖安装旧版。
    // 没有配置时回退到 debug 签名，保证本地/未配置 Secrets 时 APK 也能安装。
    val ciKeystore = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    if (ciKeystore != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }

    // 个人工具 App：不要让 lintVital 把出 APK 的流程卡住
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // ffmpeg 可执行文件以 libffmpeg.so 的形式放在 jniLibs/<abi>/ 里（由 ffmpeg/build.sh 从源码编译，
        // 不提交进仓库），必须在安装时解压到 nativeLibraryDir 才能被 exec（Android 10+ 禁止执行 filesDir 里的文件）。
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt"
            )
        }
    }
}

// ---- libffmpeg.so 检查 ------------------------------------------------------------
// 缺哪个架构的 libffmpeg.so，那个架构的设备上格式转换和 ffmpeg 命令就用不了。
// 这里只在构建日志里提醒，不拦截构建：只改界面代码时没必要先把 FFmpeg 编一遍。
val checkFfmpegLibs by tasks.registering {
    group = "verification"
    description = "检查 src/main/jniLibs/<abi>/libffmpeg.so 是否齐全"
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
    val abis = if (abiFilter.isEmpty()) ffmpegAbis else listOf(abiFilter)
    doLast {
        val missing = abis.filterNot { jniLibsDir.file("$it/libffmpeg.so").asFile.isFile }
        if (missing.isNotEmpty()) {
            logger.warn(
                "警告：src/main/jniLibs 里缺少这些架构的 libffmpeg.so：${missing.joinToString()}。" +
                    "这些架构的设备上，格式转换和 ffmpeg 命令将不可用。" +
                    "编译方法见 ffmpeg/README.md（先运行 ffmpeg/build.sh <abi>），或者直接用 GitHub Actions 构建。"
            )
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkFfmpegLibs) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Compose 基础库（不再使用 Material3）
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // 仅用作矢量图标数据源（ImageVector），与 Material3 组件库无关
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Miuix（小米 HyperOS 风格的 Compose 组件库）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")

    implementation("com.google.zxing:core:3.5.3")

    // 条码扫描：CameraX 负责相机预览与逐帧分析
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Shizuku：「执行 Shell 指令」工具通过它以 shell / root 权限运行命令
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
