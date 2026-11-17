import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * TV 端独立应用。
 *
 * 和手机版是两个 APK：包名不同（com.dailymusic.tv），各自装各自的。
 * 共用 core 模块里的接口层和播放层，UI 完全独立 —— TV 是 10 英尺界面，
 * 布局密度、焦点规则、字号和手机版没有任何可复用之处。
 */

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.dailymusic.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dailymusic.tv"
        minSdk = 33
        targetSdk = 37
        versionCode = 9
        versionName = "1.0.8"

        // 32/64 位各出一个独立包：老盒子装 32 位，新电视/盒子装 64 位。
    }


    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // 每个 ABI 单独一个 APK：32 位包不塞 arm64 的 so，体积更小，
    // 也避免 64 位 so 在 15+ 系统上的 16KB 分页告警牵连 32 位设备。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Rust 服务（酷狗接口的本地实现）和手机版是同一份产物，直接复用。
    sourceSets["main"].jniLibs.srcDirs("../app/src/main/jniLibs")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose：只用 foundation + ui，不引 Miuix（手机那套组件的尺寸/圆角在电视上完全不合适）
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
