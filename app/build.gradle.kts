plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin，无需 org.jetbrains.kotlin.android；Compose 编译器插件版本与 Kotlin 一致（见根 build.gradle.kts）
    id("org.jetbrains.kotlin.plugin.compose")
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
        versionCode = 40
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 用 debug 签名，保证没有 keystore 时 APK 也能直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // ffmpeg 可执行文件以 libffmpeg.so 的形式放在 jniLibs 里，
        // 必须在安装时解压到 nativeLibraryDir 才能被 exec（Android 10+ 禁止执行 filesDir 里的文件）。
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
