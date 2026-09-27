// AGP 9 内置 Kotlin：默认 KGP 2.2.10，此处显式升级到 Kotlin 2.4.20（官方迁移指南做法）
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
