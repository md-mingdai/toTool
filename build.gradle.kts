// Top-level build file.
//
// Miuix 0.9.x 需要：AGP 9.x + Kotlin 2.4.x + Compose 1.12（compileSdk 37）。
// AGP 9 自带 Kotlin 支持（built-in Kotlin），所以模块里不再应用 org.jetbrains.kotlin.android 插件。
//
// 但 AGP 9 内置的 Kotlin Gradle 插件（KGP）只有 2.2.10；Miuix 的二进制元数据是 Kotlin 2.4，
// 低版本编译器读不了（报 "compiled with an incompatible version of Kotlin"）。
// 官方的升级办法就是在顶层 buildscript 里显式声明更高的 KGP：
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // Compose 编译器插件的版本必须与 Kotlin 一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
