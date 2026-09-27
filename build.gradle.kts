// AGP 9 内置 Kotlin：默认 KGP 2.2.10，此处显式升级到 Kotlin 2.4.20（官方迁移指南做法）；
// KSP 与 KGP 一起提升（Room 编译期代码生成需要）
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
