import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chatgpt2api.imageapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.chatgpt2api.imageapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "3.0.4"
    }

    // 固定 debug 签名：用仓库外的固定 keystore 替代 Android SDK 默认动态生成
    // 的 ~/.android/debug.keystore。这样所有构建机（本地、CI、服务器 docker）
    // 出来的 APK 都用同一份签名，用户能直接覆盖升级，不会触发"签名不一致"。
    //
    // 路径解析顺序：
    //  1. settings.gradle.kts 同级目录的 keystore.properties 中 storeFile（绝对/相对路径均可）
    //  2. 环境变量 FOLIO_DEBUG_KEYSTORE
    //  3. 兜底使用 SDK 默认 debug.keystore（与历史行为一致，仅本机首次开发可用）
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
    val configuredStorePath = (keystoreProps.getProperty("storeFile")
        ?: System.getenv("FOLIO_DEBUG_KEYSTORE"))?.trim().orEmpty()
    val resolvedStoreFile: File? = when {
        configuredStorePath.isBlank() -> null
        File(configuredStorePath).isAbsolute -> File(configuredStorePath)
        else -> rootProject.file(configuredStorePath)
    }
    signingConfigs {
        getByName("debug") {
            if (resolvedStoreFile != null && resolvedStoreFile.exists()) {
                storeFile = resolvedStoreFile
                storePassword = keystoreProps.getProperty("storePassword") ?: "android"
                keyAlias = keystoreProps.getProperty("keyAlias") ?: "androiddebugkey"
                keyPassword = keystoreProps.getProperty("keyPassword") ?: "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
