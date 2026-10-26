import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dailymusic.app"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.dailymusic.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Miuix（HyperOS 风格 Compose 组件库）
    // 锁 0.9.3：0.9.4 依赖 Compose 1.12 + materialkolor 5.0.1，强制 AGP 9.1 / compileSdk 37；
    // 0.9.3 依赖 Compose 1.11.1 + materialkolor 4.1.1，minCompileSdk 35 / minAGP 8.6，落在现有工具链内。
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")

    // JNA：直接调用 Rust 导出的 C 符号，绕开 JNI 包名耦合
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
