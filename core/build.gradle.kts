plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * 手机版和 TV 版共用的「内核」：接口层 + 播放层。
 *
 * 这两层完全不依赖 UI（data / playback 里没有任何 com.dailymusic.app.ui 的引用），
 * 所以能整体抽出来给两个 APK 用。Kotlin 包名保持 `com.dailymusic.app.data` /
 * `com.dailymusic.app.playback` 不变 —— 这样两边已有的 import 一行都不用改。
 */
android {
    namespace = "com.dailymusic.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 播放内核。版本必须和 app 模块保持一致，否则 media3 的类会重复。
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // JNA：直接调用 Rust 导出的 C 符号
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    // PlayerManager / Library 等用 mutableStateOf 暴露状态给 UI，只需要 runtime，不需要编译器插件
    implementation("androidx.compose.runtime:runtime:1.11.1")
}
