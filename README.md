# Daily Music

一款 HyperOS 风格的在线音乐客户端，用 **Kotlin + Jetpack Compose + Miuix** 构建。

> 当前状态：**M1 地基完成**。工程可编译、可安装、内嵌 Rust 服务已在模拟器跑通。

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 8.14.3 | |
| AGP | 8.11.0 | |
| Kotlin / Compose 编译器 | 2.4.21 | |
| compileSdk | 37 | Miuix 的 aar-metadata 硬性要求 |
| minSdk | 33 | 玻璃模糊（`miuix-blur`）要求 |
| Miuix | 0.9.3 | 0.9.4 需要 AGP ≥ 9.1，暂不可用 |
| 播放内核 | Media3 ExoPlayer | M2 接入 |
| 内嵌服务 | Rust + tiny_http | 复用 md3Music 的酷狗 API 服务 |

## 目录结构

```
app/                        Android 应用（单模块）
  src/main/kotlin/com/dailymusic/app/
    MainActivity.kt
    native/KugouServer.kt   JNA 绑定 Rust 导出的 C 符号
    ui/theme/Theme.kt       MiuixTheme + Monet 动态取色
    ui/screen/HomeScreen.kt 当前为 M1 验证页
  src/main/jniLibs/         libkugou_server.so（arm64-v8a / x86_64）
rust/kugou_server/          酷狗 API 服务（AGPL-3.0，见下）
scripts/build.sh            一键：Rust 交叉编译 → 拷 jniLibs → 打 APK
design/                     视觉稿与运行截图
PLAN.md                     决策记录、里程碑、版本矩阵、踩坑清单
```

## 构建

前置：JDK 17、Android SDK（platform 37、build-tools 35）、NDK 27、Rust（含 4 个 android target）。

```bash
# 一键构建（Rust + APK）
./scripts/build.sh

# 只打 APK（.so 已就位时）
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

> **注意**：Rust 交叉编译在受限沙箱中会卡死（clang 子进程无法启动），必须在正常权限下执行。

## 已知约束

- `compileSdk` 必须是 37：Miuix 全部版本（0.9.0–0.9.4）的 aar-metadata 都写着 `minCompileSdk=37`
- AGP 8.11 官方最高推荐 compileSdk 是 36，用 37 依赖 `android.suppressUnsupportedCompileSdk=37`
- 现有 AVD 需 API 33+ 才能跑玻璃模糊效果

## 许可证与免责声明

- `rust/kugou_server/` 来自 [md3Music](https://github.com/zzyoxml/md3Music)，**AGPL-3.0**，原始许可证见 `rust/kugou_server/LICENSE-AGPL.txt`。本项目作为其衍生作品，整体按 AGPL-3.0 分发。
- 项目对接的是**非官方**酷狗接口，涉及的音乐内容版权归原权利人所有。仅供个人学习与自用，**请勿用于商业用途或公开分发**。
- 使用本项目产生的一切后果由使用者自行承担。
