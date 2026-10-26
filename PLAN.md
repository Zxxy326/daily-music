# Daily Music — 实施方案与决策记录

> 一款 HyperOS 风格的在线音乐客户端。
> 参考项目：[zzyoxml/md3Music](https://github.com/zzyoxml/md3Music)（Flutter + Rust，AGPL-3.0）
> UI 组件：[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)（Compose Multiplatform，Apache-2.0）

---

## 0. 一句话

用 **Kotlin + Jetpack Compose + 官方 Miuix** 重写一个 HyperOS 风格的在线音乐客户端，只复用上游的 **Rust 酷狗 API 服务**。

---

## 1. 结论先行：方案中途换过道（重要）

最初的设想是「fork md3Music（Flutter），把 UI 换成 MiuiX」。**这条路已作废**，原因是三个硬事实：

| 事实 | 数据 | 后果 |
|---|---|---|
| 官方 Miuix 是 Compose Multiplatform 库 | Kotlin 2.4.21 / Compose 1.12.2 | Flutter 工程无法引用 |
| 上游 UI 深度绑定 Material | 260 文件 / 148,501 行 = 代码量 **69%** | 换 UI 框架 = 重写 UI 层 |
| 上游用了独立包 `material_ui: 1.7.0` + `m3e_core` | 256 文件引用 material_ui，104 文件引用 m3e | 不是换主题能解决的 |

所以真正的选择只有两个：留在 Flutter 用**移植版** `flutter_miuix`（64 star、单人维护），或者换 **Compose** 用官方 Miuix。你选了后者。

**这意味着：这不是改造，是新写一个 App。** 上游的 216,060 行 Dart 全部作废，唯一继承的是 17,875 行 Rust。

---

## 2. 决策记录

| # | 问题 | 结论 |
|---|---|---|
| 1 | 技术栈 | 官方 Miuix + Jetpack Compose，**纯 Android 工程**（不做 KMP 多平台骨架） |
| 2 | 平台范围 | 手机 + 平板**同包自适应**；TV 不做 |
| 3 | 包名 / 显示名 | `com.dailymusic.app` / **Daily Music** |
| 4 | 上游资产 | 只取 `kugou_api_server/rust`（17,875 行 / 50 模块）；Flutter 树不入库 |
| 5 | 仓库 | 新建 `daily-music`，保留上游 AGPL-3.0 与署名；上游本地留一份作参考 |
| 6 | 播放内核 | **Media3 ExoPlayer** + MediaSessionService |
| 7 | USB 独占输出 | **预留 AudioSink 接口，第一版不实现**（需真机 + DAC 才能验证） |
| 8 | 第一版功能 | 核心七件套：搜索 / 播放 / 逐字歌词 / 歌单详情 / 收藏 / 播放历史 / 音质切换，**含扫码登录** |
| 9 | 视觉 | 写代码前先出三屏高保真稿确认方向 |
| 10 | 发布 | **仅自用，不公开发布** |
| 11 | 验收 | 功能闭环可验证（能装、能登录、能搜、能连播、歌词滚动、内存不涨） |
| 12 | Miuix 依赖 | **Maven Central 锁版本**（不 vendor 源码） |
| 13 | 登录方式 | **三种都做**：扫码 / 手机号 / Cookie 导入 |
| 14 | 歌词深度 | **逐字 + 翻译 + 罗马音一起移植** |
| 15 | 收藏与历史 | **复用上游 SQLite schema** |
| 16 | 底栏形态 | **悬浮液态玻璃底栏**（iOS 风格，非贴底标签栏） |

### 2.1 悬浮玻璃底栏带来的硬约束

`miuix-blur` 模块的 `build.gradle.kts` 里写死 **`minSdk = 33`**（Android 13）。
一旦引入该模块做真实背景模糊，整个 App 的 `minSdk` 必须 ≥ 33，否则 manifest merger 直接失败。

- 结论：**`minSdk = 33`**，放弃 Android 12 及以下机型（2026 年可接受）
- 模拟器 `vp_phone` 的 system image 是 **android-30**，**跑不了玻璃效果** —— 要么装 API 33+ 的 image，要么真机验证
- 降级方案：`< 33` 机型用半透明纯色面（无模糊），但这版直接不覆盖

---

## 3. 架构

```
daily-music/
├── app/                                  # 单模块 Android 工程
│   └── src/main/kotlin/com/dailymusic/
│       ├── ui/theme/                     # MiuixTheme + Monet 动态取色 + 排版
│       ├── ui/component/                 # 复用组件（歌曲行、封面、空态）
│       ├── ui/screen/home/               # 首页 / 搜索
│       ├── ui/screen/player/             # 播放页（含迷你播放条）
│       ├── ui/screen/lyrics/             # 逐字歌词
│       ├── ui/screen/playlist/           # 歌单详情
│       ├── ui/screen/library/            # 收藏 / 历史
│       ├── player/                       # Media3 ExoPlayer + MediaSessionService
│       │   └── sink/                     # USB 独占预留接口（空实现）
│       ├── data/api/                     # Ktor/OkHttp → 127.0.0.1:<port>
│       ├── data/repo/                    # 仓库层 + Room（收藏/历史）
│       └── native/                       # JNA 绑定 Rust
├── rust/kugou_server/                    # 上游 Rust（保留 LICENSE + 署名）
└── docs/
```

### 3.1 Rust 服务的接入方式（关键细节）

上游 Rust 的 JNI 导出符号是**写死包名**的：

```
Java_com_md3music_premium_KugouApiService_nativeStartNode
```

照搬会逼我们把 Kotlin 类放到 `com.md3music.premium` 下，很难看。但同一个 crate 还导出了四个**与类名无关的 C 符号**：

```rust
start_server(port: c_int, data_dir: *const c_char) -> c_int
stop_server()
is_server_running() -> c_int
get_server_port() -> c_int
```

**方案：用 JNA 直接调这四个 C 符号**，绕开 JNI 包名耦合，Rust 侧一行不用改。

### 3.2 数据流

```
Compose UI  →  Repository  →  OkHttp/Ktor  →  127.0.0.1:<port>  →  Rust (kugou_server)  →  酷狗 API
                  ↓
              Room（收藏 / 历史 / 设置）
```

播放链路：`Repository 取 song_url → Media3 ExoPlayer → MediaSessionService → UI 状态（StateFlow）`

---

## 4. 里程碑

| 阶段 | 内容 | 完成标志 |
|---|---|---|
| **M0** 视觉稿 ✅ | 三屏高保真 HTML（`design/mockup.html`），含悬浮玻璃底栏 | 你确认视觉方向 |
| **M1** 地基 ✅ | Gradle 工程骨架（minSdk 33）、Rust 交叉编译（NDK 27）、JNA 拉起服务 | **已在 API 35 模拟器验证：服务起在 127.0.0.1:17150，`/login/qr/key` 返回真实酷狗二维码** |
| **M2** 播放闭环 | Media3 + 播放页 + 悬浮玻璃底栏 + 迷你播放条 | 能放出一首歌 |
| **M3** 歌词 | KRC 逐字解析 + 翻译行 + 罗马音（从上游 Dart 引擎移植） | 歌词逐字滚动 |
| **M4** 内容 | 搜索 / 歌单 / 收藏 / 历史 / 音质切换 / 三种登录入口 | 七件套闭环 |
| **M5** 收口 | 平板断点、性能、签名、APK | 通过验收标准 |

---

## 5. 风险清单（不粉饰）

| 风险 | 说明 | 应对 |
|---|---|---|
| **Miuix 官方标注 experimental** | README 明写「APIs may change without notice」 | 锁死版本号；遇破坏性变更再 vendor 源码 |
| **酷狗 API 法律风险** | 非官方接口，涉及版权内容分发 | 仅自用不发布（已定） |
| **AGPL-3.0 传染** | Rust 部分为 AGPL，衍生作品需 AGPL | 不分发即无义务；仓库仍保留 LICENSE 与上游署名 |
| **无真机** | 只有 API 30 模拟器（`vp_phone`、`tvdesk_tv`） | 音频链路、解码性能、Monet 取色需真机复验 |
| **USB 独占无法验证** | 需外接 DAC | 只留接口，不写实现 |
| **逐字歌词要重写** | 上游 `apple_lyrics` 是 176 KB Dart 引擎 | M3 单独排期，不混进 M2 |
| **NDK 版本不一致** | 上游要 28.2，本机是 27.0 | 用 27 编译 Rust，已验证可用 |
| **无 Material 兜底** | 一旦 Miuix 缺组件（如 Segmented Control）没有现成替代 | 缺什么自己组合实现，记入组件缺口表 |
| **玻璃效果验不了** | `miuix-blur` 要 minSdk 33，现有 AVD 只有 android-30 image | M1 阶段装 API 33+ system image，或真机验证 |

---

## 6. 验收标准

APK 装到模拟器/真机后，逐条验证：

- [ ] 扫码 / 手机号 / Cookie 三种登录入口均可进入
- [ ] 搜索关键词能出结果
- [ ] 点歌能播放，连续播 10 首不崩
- [ ] 逐字歌词随播放滚动，不漂移；翻译与罗马音同步
- [ ] 音质切换生效（标准 / 高质 / 无损）
- [ ] 收藏与历史落库，重启后仍在
- [ ] 悬浮底栏玻璃模糊生效，滚动时内容从玻璃后穿过
- [ ] 平板断点下导航切换为侧栏，布局不错位
- [ ] 连续播放 30 分钟，内存不持续增长

---

## 7. 已确认（2026-10-26）

| 问题 | 结论 |
|---|---|
| Miuix 依赖方式 | Maven Central 锁版本 |
| 登录方式 | 三种都做（扫码 / 手机号 / Cookie 导入） |
| 歌词深度 | 逐字 + 翻译 + 罗马音一起移植 |
| 收藏与历史 | 复用上游 SQLite schema（`play_records` 表 + `favorites_repository` 的存储结构） |

---

## 8. 本地现状

- 上游代码已浅克隆到 `.tmp/upstream/`（约 300 MB），供查阅，未纳入版本管理
- 工具链就绪：JDK 17 Corretto、Android SDK 36/35、NDK 27.0、Rust 1.98、两个 AVD
- 视觉稿：`design/mockup.html`（可交互，右上角切换深色），静态预览 `design/final-light.png` / `final-dark.png`
- 工作区 `D:\expert-system\Love listening music\` 目前只有方案与视觉稿，工程代码尚未创建

---

## 9. 工具链版本矩阵（实测通过，2026-10-26）

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | 8.14.3 | 复用本机 `D:/dev/gradle-home` 缓存 |
| AGP | 8.11.0 | |
| Kotlin / Compose 编译器插件 | 2.4.21 | |
| compileSdk | **37** | 见下方说明，绕不过去 |
| targetSdk | 35 | |
| minSdk | 33 | 玻璃模糊所需 |
| Miuix | **0.9.3** | |
| JNA | 5.19.1（aar） | 调 Rust 的 C 符号 |
| Android SDK Platform | `platforms;android-37.0` | 目录名带 `.0`（Android 17 起引入次版本号） |

**踩过的坑（都验证过，别再试）**：

1. **Miuix 全部版本（0.9.0–0.9.4）的 `aar-metadata` 都写着 `minCompileSdk=37`**
   —— 没有「降版本换低 compileSdk」这条路，必须装 API 37 平台。
2. **0.9.4 额外要求 AGP ≥ 9.1**，因为它拉 androidx Compose runtime 1.12；
   0.9.3 拉 Compose 1.11.1（minAGP 8.6），所以**锁 0.9.3 才能在 AGP 8.11 上跑**。
3. AGP 8.11 的「最高推荐 compileSdk」是 36，用 37 需要
   `gradle.properties` 里加 `android.suppressUnsupportedCompileSdk=37`。
4. `FloatingNavigationBar` / `FloatingNavigationBarItem` 在 0.9.0 就有，不必为它升版本。

**一个必须记住的构建坑**：Rust 交叉编译在受限沙箱里会**永久卡死**（`clang.exe` CPU 时间为 0、无子进程，卡 40 分钟无产物）。
放开权限后同一份代码 **32 秒**编完。`scripts/build.sh` 必须用正常权限执行。

---

## 10. 当前工程状态（M1 完成）

```
daily-music/
├── app/                                  # 已可编译运行
│   └── src/main/
│       ├── kotlin/com/dailymusic/app/
│       │   ├── MainActivity.kt
│       │   ├── native/KugouServer.kt     # JNA 绑定 Rust 的 4 个 C 符号
│       │   └── ui/theme/Theme.kt         # MiuixTheme + Monet 动态取色
│       │   └── ui/screen/HomeScreen.kt   # M1 验证页（后续替换为真首页）
│       ├── jniLibs/{arm64-v8a,x86_64}/libkugou_server.so
│       └── res/                          # 自适应图标 + 主题
├── rust/kugou_server/                    # 上游 AGPL-3.0，保留 LICENSE
├── scripts/build.sh                      # 一键 Rust + APK
├── gradlew / gradle/wrapper/             # Gradle 8.14.3
└── design/                               # 三屏视觉稿
```

**M1 实测证据**：

| 验证项 | 结果 |
|---|---|
| APK 编译 | ✅ `app-debug.apk` 26.8 MB（含双 ABI .so 后约 40 MB） |
| 安装启动 | ✅ 模拟器 `daily_phone`（API 35） |
| Miuix 主题渲染 | ✅ 标题/正文/按钮均为 Miuix 组件 |
| JNA 加载 .so | ✅ 无 `UnsatisfiedLinkError` |
| Rust 服务启动 | ✅ `127.0.0.1:17150`（随机端口） |
| HTTP 连通 | ✅ `/` → `Cannot GET /`（404，符合预期） |
| 真实 API | ✅ `/login/qr/key` → 返回 base64 PNG 登录二维码 |



