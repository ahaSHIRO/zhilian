plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 编译 Kotlin 源码，无需 kotlin-android 插件；
    // Compose 编译器插件仍需单独应用
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
            // 个人分发：用 debug 签名（与已装 debug 版同签名，可覆盖安装保留数据）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // 批次 Schema 单一来源在 docs/schema/，经 Copy 任务复制进 build 内目录作为 assets，
        // 避免引用项目外路径破坏配置缓存
        getByName("main").assets.srcDir(
            layout.buildDirectory.dir("generated/batchSchema").get().asFile
        )
    }
}

// 把权威 Schema 复制进构建目录供 assets 打包（仅 schema 本体，BatchSchemaValidator 只读该文件）
val copyBatchSchema = tasks.register<Copy>("copyBatchSchema") {
    from(rootProject.file("docs/schema/batch-v1.schema.json"))
    into(layout.buildDirectory.dir("generated/batchSchema"))
}

// AGP 不感知 sourceSets 中任务输出的依赖。Schema copy 挂在 preBuild 根任务上——
// 各变体的 mergeAssets 与 release 的 lintVital 流水线（generateReleaseLintVitalReportModel 等）
// 都传递依赖 preBuild，一处声明全覆盖（曾只在 mergeAssets/lintVitalAnalyze 挂依赖，
// release 构建仍报 lintVital 任务组隐式依赖缺失）
tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn(copyBatchSchema) }

ksp {
    // Room 导出 Schema 便于迁移审查与测试（当前 v2，4 张表：questions /
    // answer_records / processed_batches / pending_duplicates）
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.json.schema.validator)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.androidx.documentfile)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
