// Top-level build file.
// Miuix 0.9.4 需要：AGP 9.x + Kotlin 2.4.x + Compose 1.12（compileSdk 37）。
// AGP 9 自带 Kotlin 支持（built-in Kotlin），因此不再声明 org.jetbrains.kotlin.android 插件。
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
