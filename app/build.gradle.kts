plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 编译 Kotlin 源码，无需 kotlin-android 插件；
    // Compose 编译器插件仍需单独应用
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.baiyin.zhilian"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.baiyin.zhilian"
        // 发布定案：跟随主力机（已核实 Android 17 = API 37，见 README）；当前为模拟器
        // 联调临时值（MuMu 为 Android 15/API 35；31 是 Material You 动态取色最低线）
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
