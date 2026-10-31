import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名信息放在 keystore.properties（不入库）；缺失时自动退回 debug 签名，
// 保证任何一台机器 clone 下来都能直接 assembleRelease。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.dailymusic.app"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.dailymusic.app"
        minSdk = 33
        targetSdk = 35
        // 1.0.0 首个可日常使用；1.0.1 修点击区直角与弹层系统栏；
        // 1.0.2 播放页手势 / 底栏歌词 / 在线更新；1.0.3 歌词动效 + 首页头像 + 底栏几何；
        // 1.0.4 滑动手势 / 底栏自动隐藏 + 黑胶 / 动效横屏沉浸 / 更新多镜像竞速。
        versionCode = 6
        versionName = "1.0.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

    buildTypes {
        release {
            // 不开 R8：JNA 的 C 符号绑定、Miuix 的 Compose 反射点都没写 keep 规则，
            // 混淆收益远小于「装上去跑不起来」的风险。自用场景不差这几 MB。
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // miuix-blur：AGSL runtime shader 实现的真实背景模糊，悬浮玻璃底栏靠它。
    // 它要求 API 33+，这就是本应用 minSdk = 33 的唯一原因。
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.3")

    // 播放内核。锁 1.4.1：本机 Gradle 缓存里就是这个版本，且与 compileSdk 37 / AGP 8.11 兼容。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // JNA：直接调用 Rust 导出的 C 符号，绕开 JNI 包名耦合
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
