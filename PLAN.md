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
| **M1** 地基 ✅ | Gradle 工程骨架（minSdk 33）、Rust 交叉编译（NDK 27）、JNA 拉起服务 | **已在 API 35 模拟器验证：服务起在 127.0.0.1:<随机端口>，`/login/qr/key` 返回真实酷狗二维码** |
| **M2** 播放闭环 ✅ | Media3 MediaController + 播放页 + 悬浮玻璃底栏 + 迷你播放条 + 真实队列 | 能放出一首歌、后台播、有媒体通知 |
| **M3** 歌词 ✅ | KRC 逐字解析 + 翻译行 + 罗马音 + 卡拉 OK 扫色 | 歌词逐字滚动 |
| **M4** 内容 ✅ | 搜索 / 排行榜与歌单详情 / 收藏 / 历史 / 音质切换 / 三种登录 | 七件套闭环 |
| **M5** 收口 ✅ | 平板断点（侧栏导航 + 播放页两栏）、性能、签名、APK | 通过验收标准 |
| **M6** 加密曲目 ✅ | `.mgg` 解密调查 → 结论不可行 → 改为 UI 如实标记 + 自动跳过 | 用户不会再白点一次 |
| **M7** 概念版权益 ✅ | 每日畅听双签到 + 自动签到 + 签到日历 + 手机验证码登录修复 | 登录后能听整首 |

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

---

## 11. 当前工程状态（M2–M7，2026-10-27）

### 11.1 已实现的模块

```
app/src/main/kotlin/com/dailymusic/app/
├── DailyMusicApp.kt          Application：起 Rust 服务 + 初始化音乐库
├── native/KugouServer.kt     JNA → start_server / stop_server / is_server_running / get_server_port
├── core/Http.kt              HttpURLConnection 的 JSON / bytes 封装（不引 OkHttp）
├── data/
│   ├── Song.kt               归一化模型 + 宽松 JSON 扩展（上游字段名极不统一）
│   ├── Lyrics.kt             KRC / LRC 解析：逐字 + 翻译 + 罗马音
│   ├── KugouApi.kt           所有本机端点封装（搜索/推荐/取址/歌词/榜单/登录）
│   ├── Rank.kt               排行榜 → 歌单
│   ├── Library.kt            收藏 + 历史（JSON 落盘，重启保留）
│   ├── Auth.kt               登录态持久化
│   └── YouthVip.kt           概念版双签到（畅听 + 升级）+ 打卡记录 + 会员到期
├── playback/
│   ├── PlayerManager.kt      持有 MediaController，UI 的唯一状态源
│   ├── PlaybackService.kt    持有 ExoPlayer + MediaSession（服务是播放器所有者）
│   └── PlayUrlStore.kt       解析式数据源：mediaId → 真实音频地址
└── ui/
    ├── RootScreen.kt         主骨架 + 玻璃底栏 / 侧栏 + 迷你播放器
    ├── HomeScreen / SearchScreen / PlaylistScreen / LibraryScreen
    ├── MineScreen / LoginScreen / PlayerScreen
    ├── SignInScreen.kt       每日畅听：签到 + 月历打勾 + 会员到期
    ├── Glass.kt              liquidGlass（miuix-blur 封装）
    ├── Adaptive.kt           宽度断点
    └── Components / AppIcons / RemoteImage
```

### 11.2 实测证据

| 验证项 | 结果 |
|---|---|
| 每日推荐 | ✅ 30 首 + 真实封面 |
| 搜索 + 热搜 | ✅ 结果可播 |
| 排行榜 / 歌单详情 | ✅ 头部 + 播放全部 + 列表 |
| 播放链路 | ✅ MediaController → 服务内 ExoPlayer，`state=PLAYING` |
| 后台播放 | ✅ HOME 后 12 秒 position 持续推进 |
| 前台服务 | ✅ `isForeground=true`，`types=0x2`（mediaPlayback） |
| 媒体通知 | ✅ 3 个动作（上一首/暂停/下一首），`groupKey=media3_group_key` |
| 真实队列 | ✅ 30 首，媒体按键切歌正常 |
| 坏歌自动跳过 | ✅ 加密曲目报错后自动前进并继续播 |
| 逐字歌词 | ✅ 卡拉 OK 扫色按字宽加权，两帧对比确认推进 |
| 翻译 / 罗马音 | ✅ 从 `[language:<base64>]` 单行标签解出，按行下标对齐 |
| 收藏 / 历史 | ✅ 行内心形 + 我的页入口 + 重启保留 |
| 三种登录 | ✅ 扫码（真实二维码）/ 手机号 / Cookie 导入 + 退出登录 |
| 音质切换 | ✅ 128 / 320 / flac / Hi-Res 四档，档位持久化；实际音质以服务端回写为准 |
| 平板断点 | ✅ 800dp → 左侧悬浮玻璃胶囊导航；播放页左右两栏 |
| Release 签名 | ✅ `app-release.apk` 36.9 MB，`CN=Daily Music`，`isForeground=true` 复验通过 |
| 概念版签到（M7） | ✅ `/youth/day/vip` + `/youth/day/vip/upgrade` 双签到链路跑通；假 token 实测返回 `20002` → UI 显示「登录已失效，请重新登录」 |
| 自动签到 | ✅ 冷启动 / 登录成功后各触发一次，同一天只跑一次（`daily_music_youth_vip.auto_sign_day`） |
| 签到日历 | ✅ 当月网格、今天描边、已签填充、`本月已签 n / N 天` |
| 手机号真实错误 | ✅ 输入 `00000000000` 点发码 → 上游 `20010` → 界面显示「手机格式不正确（20010）」（旧版会谎报「已发送」） |
| 账号资料（`/user/detail`） | ✅ 真机 `status=1`：头像 + 昵称 Zxxy + `ID 2509098403`（2026-10-27；此前回 20018 的真因是 query 里空格被编成 `+` 导致 userid 丢失，见 §11.7） |
| 登录态自愈（`/login/token`） | ⚠️ 代码已就位（进「我的」先续期再拉资料）。扫码登录拿不到 `t1`，这类凭证不打续期请求也不误判失效；成功分支需一次手机号/Cookie 登录写入 `t1` 后才能验证 |
| 会员到期 / 打卡记录 | ✅ `/user/vip/detail` 解析出 `畅听 5 天后到期 · 概念 2 天后到期`；`/youth/month/vip/record` 解析出 `本月已签 9 / 31 天` |
| 真实音质 | ✅ 真机四档全通：标准 / 高品质 320k / 无损 FLAC / **Hi-Res**（2026-10-28 补齐后两档，此前 UI 只写了三档所以从没请求过 `high`） |
| 带空格关键词 | ✅ 热搜词 `call your name` 正常返回 30 首（修复前会被编成 `call+your+name`） |

### 11.3 已知限制

| 限制 | 说明 |
|---|---|
| 加密曲目（`.mgg`）不可播 | 部分曲目只下发 `en_tracker_url`。上游 `/audio/proxy` 已被作者禁用（403），客户端未实现解密。实测每日推荐 12 首中约 4 首属于此类，会提示并自动跳过 |
| 不做 R8 | JNA 的 C 符号绑定与 Miuix 的反射点没有 keep 规则，混淆风险大于收益 |
| 歌词面板不是可拖动列表 | 目前按当前行取 5/7 行窗口渲染，没有自由滚动与拖动定位 |
| 无真机验证（旧） | 早期验证在模拟器（API 35，`-gpu host`）完成。**2026-10-27 起已接入真机 b0500920 验收**：账号资料、签到日历、320k 播放、带空格搜索均已实测 |
| 签到 20028 未处理 | 上游风控要求「二次安全验证」（腾讯滑块，`v_type=23`）时，需要在 WebView 里跑 TCaptcha 组件。当前只如实提示「去官方 App 签一次」，不做滑块 |
| 手机号登录未跑通真实成功路径 | 结构上已对齐（含多账号 `info_list` 选择），但没有可用的手机号 + 短信做端到端验证；错误路径已用真实上游响应验证 |
| 扫码登录拿不到 `t1` | `/v2/get_userinfo_qrcode` 不返回续期种子，这类凭证过期后无法自愈，只能重新登录。已做到「不误判成失效」，但没有种子确实续不上 |
| 老凭证无法自愈 | `t1` 是本次才加持久化的；在此之前的登录没有存 `t1`，`/login/token` 缺种子必然被拒（20018）。这类凭证只能重新登录一次，之后才能自动续期 |

### 11.4 踩过的坑（除第 9 节外）

1. **`MediaSessionService` 必须自己持有 ExoPlayer。** UI 直接 `new ExoPlayer` 会导致 session 永不注册、无通知、服务不进前台。
2. **不要手工 `startForegroundService`。** 应用在前台时服务不会立刻 `startForeground`，5 秒后 `ForegroundServiceDidNotStartInTimeException` 闪退。让 `MediaController` 绑定去拉起服务即可。
3. **玻璃要能模糊到东西。** `LayerBackdropNode.draw()` 是先 `drawContent()` 再录制，所以不透明背景必须写在 `layerBackdrop` **之后**；写在外层会录进透明区，模糊采样到透明被当黑色 → 玻璃上出现深色色块。
4. **`Uri.schemeSpecificPart` 带 `//`。** 自定义 scheme 的 mediaId 解析要自己剥掉前导斜杠，否则地址查表永远 miss。
5. **Miuix 0.9.3 的 `FloatingNavigationBarItem` 不读 DisplayMode**（`javap -c` 里没有 `getLocalNavigationBarDisplayMode` 调用），只能自己写 tab 项。
6. **`[language:]` 是单行标签**，不是块起始。当成块起始会把后续所有歌词行吞成翻译。
7. **`/song/url` 的 `album_audio_id` 要传 `MixSongID`**，不是 `Audioid`；只给 hash 会返回 `error_code 31833 illegal key`。
8. **`errcode` 和 `error_code` 是两个字段。** 上游统一用 `error_code`。写成 `errcode` 永远读到 0，于是发短信接口**任何失败都判成功**，用户白等短信。真机踩过，改成读 `status` 才对。
9. **失败时 `data` 会从对象退化成字符串。** `{"status":0,"data":"验证码错误","error_code":20021}` —— 用 `obj("data")` 拿不到东西，得单独兜 `data is String`，否则真实错误信息被吞成通用兜底。
10. **一个手机号可能绑多个账号。** 这时上游**不给 token**，只把候选放在 `data.info_list` / `user_list`，客户端必须让用户选一个、再带 `userid` 重发。只认 token 的写法会把「多账号」误报成「验证码错误」。
11. **签到结果必须落到状态里。** `signIn()` 里早退的几条分支忘了写 `lastOutcome`，表现是「点了签到，界面一点反应都没有」—— 这种「静默成功/静默失败」比报错更难查，所以现在所有分支统一由一个外层 `try/finally` 收敛后赋值。

### 11.5 `.mgg` 加密曲目：调查结论（2026-10-27）

**现象。** 60 首样本（每日推荐 30 + 三个关键词各 10）：

| 类别 | 数量 | 说明 |
|---|---:|---|
| 全档位明文 | 42 | `tracker_url` 有值，正常播 |
| 全档位加密 | 15 | `tracker_url` 恒为空，只有 `en_tracker_url`（`.mgg`） |
| 完全无源 | 3 | 两个列表都空 |

关键点：**加密是全档位一致的** —— 128 / 320 / flac 三档要么全明文要么全加密，
不存在「降档就能拿到明文」的曲目（实测 0/60）。换 `/song/url` 旧端点、加 `ppage_id`、
加 `free_part=1`、补 `album_id` 都拿不到明文地址。

**流格式（实测）。**

- CDN 直出的是**裸密文流**：没有 KGM 的 16 字节魔数头，也没有 QMCv2 的 `QTag` / `musicex` 尾部；
- 密文长度 = 明文音频长度（2833414 B ≈ 235 s @128k）；
- 全文件均为密文，首尾都找不到任何明文特征（MP3 sync / `ID3` / `OggS` / `fLaC`）；
- 响应里带 `info.en_ekey`：base64 960 字符 → 720 字节二进制；`info.en_hash`、URL 里的
  `_en<32hex>` 是内容哈希，不是密钥。

**已验证可用的密码原语**（用 MusicDecrypto 的公开测试向量逐项校验通过）：

| 原语 | 校验方式 | 结果 |
|---|---|---|
| QMC2 `MapCipher` | `mgg-map.key` + `mgg-map.payload.enc` | ✅ 输出以 `OggS` 开头 |
| QMC2 `RC4Cipher(key, 128, 5120)` | `mflac-rc4.key` + payload | ✅ 输出以 `fLaC` 开头 |
| TEA 32 轮（大端） | 零密钥 / 零明文标准向量 `41ea3a0a94baa940` | ✅ 解回全零 |
| `simpleMakeKey(106, 8)` | unlock-music 硬编码常量 `695646382b20150b` | ✅ 一致 |

**卡点。** `en_ekey` 不是公开的 QQ Music ekey 格式。用 unlock-music 的
`deriveKeyV1` / `deriveKeyV2` 处理会在 TEA 的 `zero check` 处**稳定失败**
（V1、V2、原文 ASCII、裸二进制、截断到 512/712 字节等 9 种组合全试过）。
也就是说 Kugou 在 ekey 外又套了一层私有变换，这层只存在于其闭源客户端里。
仓库里能查到的 Kugou 方案（KGM / KGMA / VPR 的 V2 XOR、KGG 的 QMC2）都需要
**文件头里的 16 字节 fileKey** 或**本机 `KGMusicV3.db`**，而流式 `.mgg` 两者都没有。

**决定：不实现解密。** 继续做下去是无界的密钥猜测（unlock-music 已被 DMCA 下架，
没有可对照的实现）。改为把状态如实暴露给用户：

- 播放失败时把该曲目记进 `PlayerManager.encryptedKeys`；
- 列表行据此显示红色「加密」标记，替换时长 —— 用户不会再白点一次；
- 队列里仍然自动跳过，连续 3 首失败就停下，不空转。

**若以后要重启这条线**，需要的是一份「同一首歌 + 它的 `en_ekey` + 已知明文首块」的
三元组，用来反推那层变换；或者 Kugou 客户端里负责解 ekey 的那个 so 的行为。

### 11.6 概念版权益：为什么「只能试听」，以及签到怎么补（2026-10-27）

**先纠正一个误判。** 本项目的接口身份**一直**是酷狗概念版，不是官方版：

```
config.rs:  APP_ID = "3116"   CLIENT_VER = "11440"   SRC_APP_ID = "3116"
request.rs: standard_signature == false → appid 3116 / clientver 11440
```

`3116/11440` 就是概念版（`request.rs` 里另一条注释也印证：标准盐必须配
`1005/20789`，概念版 `3116/11440` 会让长音频章节的 `fail_process` 归 0）。
所以「换成概念版身份」这件事不用做，做了也没用。

**真正的原因是：概念版的权益模型是「按天签到」，不是「买会员」。**

- 没签到 → 账号零权益 → VIP 曲目只给 60 秒试听；
- 签到 → 当天拿到一天畅听 → 整首可听。

MD3Music 之所以能听整首，是因为它把签到做进去了（`autoReceiveVipIfNeeded`
在登录后自动跑）。我们之前只做了登录、没做签到，所以**登录了也还是试听**。

**双签到链路**（缺一不可，和上游 `kugou_provider.dart:_signInOnce` 对齐）：

| 步骤 | 端点 | 作用 |
|---|---|---|
| ① | `POST /youth/day/vip`，`receive_day=yyyy-MM-dd` | 领「畅听 VIP」（`tvip`）一天 |
| ② | `POST /youth/day/vip/upgrade` | 把它升级成「概念版会员」（`svip`） |

只做第一步，官方那边显示「畅听VIP+1天」，音质确实会放开，但概念版专属权益拿不到。
上游早期版本静默吞掉第二步的失败，导致自己显示「概念版会员」而官方只认畅听 ——
这里不重蹈覆辙：**两步分别判成功，失败如实报**（「畅听已领取，但升级概念版失败：…」）。

**几个必须做对的细节：**

1. **`receive_day` 用服务端时间算。** 打 `/server/now` 拿时间戳再格式化；用手机本地
   时间算，时区偏一点就变成「昨天/明天」，上游判无效签到。
2. **签到后必须换 token。** 本地存的是签到前那张凭证，`vip_token` 里没有当天额度。
   不刷新的话表现是「页面显示签到成功，但播歌还是试听」。所以成功后走一次
   `/login/token`，把新的 `token / vip_token / vip_type` 落盘。
3. **「已领取」不算失败。** `131001`（今日已领）、`20030`（已升级过）都归到
   「今日已签到」，UI 显示成已完成而不是红字。
4. **20028 如实说。** 上游风控要求腾讯滑块（`v_type=23`）时，需要 WebView 跑
   TCaptcha；当前没实现，提示「去官方 App 签一次即可」，不假装成功。

**自动签到的落点**：`RootScreen` 的冷启动 `LaunchedEffect`（要等本地服务起来才有端口）
+ 三种登录方式的成功回调。同一天只自动跑一次，记在
`daily_music_youth_vip.auto_sign_day`；失败不记账，下次启动重试。

**顺带修的手机号登录问题**（三条，都是真机暴露的）：

| 现象 | 根因 |
|---|---|
| 号码格式不对也提示「验证码已发送」 | 判的是 `errcode`，上游字段叫 `error_code`，永远读到 0 |
| 登录失败只说「验证码错误或已过期」 | 失败时 `data` 是**字符串**，`obj("data")` 为 null，真实文案被吞 |
| 多账号手机号登录不了 | 上游不给 token，只给 `data.info_list` 候选账号，需要选完带 `userid` 重登 |




### 11.7 账号信息不显示：一个 UI 缺口 + 一张死 token（2026-10-27）

**现象。** 同样的账号，MD3Music 的「我的」显示头像 + 「超级大咕咕」，我们的只显示
「已登录」四个字。

**这是两层问题叠在一起，只修一层看起来都像没修。**

**第一层：UI 从来没拉过 `/user/detail`。** 之前「已登录」是拿 `Auth.isLoggedIn`
直接渲染的 —— 只要 prefs 里有 token 就显示，跟上游是否认这张 token 毫无关系。
补 `Profile.kt`：拉资料、拿昵称/头像/ID，**顺带把登录态校验做了**（`status != 1`
就说明 token 死了）。

**第二层（真根因）：查询参数里的空格被编成了 `+`，userid 在链路上掉了。**

真机 `curl` 直打本地服务（`127.0.0.1:26619`，设备上有 `/system/bin/curl`）：

| 端点 | 结果 |
|---|---|
| `/user/detail` | `{"error_code":20018,"status":0,"data":null}` |
| `/user/cloud/playlist` | `status:1` + 真实歌单 ✅ |
| `/user/vip/detail` | `20010 request params invalid` |
| `/youth/month/vip/record` | `20002` |
| `/login/token` | `20018` |

**同一个 token，有的接口活、有的接口死** —— 这直接排除了「凭证过期」。差别在于
哪些接口依赖 `userid`：`cloud/playlist` 只认 token，所以它一直是对的。

链路是这样的：`Auth.cookieParam()` 返回 `token=…; userid=…; vip_token=…`，
`Http.getJson` 用 `URLEncoder.encode` 拼 query，**空格变成 `+`**：

```
cookie=token%3D53ef…%3B+userid%3D2509098403%3B+vip_token%3D…
                      ↑ 就是这个 + 
```

Rust 侧 `percent_decode_preserve_plus` 按 `decodeURIComponent` 语义实现（`+` 原样
保留，只有 `%20` 才解成空格 —— 这是为了对齐上游 Dart 客户端的 Dio）。于是
`cookie_to_json` 按 `;` 切分后，第二段的 key 变成 **`"+userid"`**：

```rust
for part in s.split(';') {
    let k = arr.next().unwrap_or("").trim();   // "+userid" —— trim 去不掉 '+'
    obj.insert(k.to_string(), json!(v));
}
```

**`userid` / `vip_token` / `t1` 全部丢失**，只剩第一段 `token`。

后果精确对应上面那张表：

- `/user/detail`：body 里 `userid: 0`，但 `p` 是用**真实 token** 做的 RSA
  （`RSA({token, clienttime})`）→ 上游判「签名里的身份 ≠ 请求的身份」→ `20018`
- `/user/vip/detail`：缺 userid → `20010 request params invalid`
- `/youth/month/vip/record`：缺 userid → `20002`

> **20018 有歧义，别只看码。** `blacklist.rs` / `user_preference.rs` 的注释里记过
> 另一条教训：概念版 token 配标准版 `appid=1005` 签名，同样回 20018。这次第三种
> 成因：**token 是对的，只是没送到**。三条路径的码一模一样，只看码必然误判。

**排查中逐条排除的假设**（都做了实证，不是感觉）：

| 假设 | 结论 |
|---|---|
| token 过期了 | ❌ 同一个 token 拉 `/user/cloud/playlist` 返回真实数据 |
| RSA 公钥用错了（标准 vs 概念版） | ❌ 和上游 bundle 逐符号比对：`cryptoRSAEncrypt` = 零右填充 raw RSA，我们 `rsa_raw_encrypt` 完全一致；`eq`/`Rd` 两把公钥字符串也一致 |
| `p` 的明文字段顺序不对 | ❌ `{token, clienttime}` 与 bundle 中 `get_my_info` 模块一字不差 |
| `userid` 该传字符串不是数字 | ❌ 上游 JS 是 `Number(e?.userid \|\| …)`，就是数字 |
| 是 apicache 命中旧响应 | ❌ 重启进程后仍然复现（但顺手补了 `noCache` 的必要性认知） |
| **空格编码** | ✅ 唯一能解释「部分接口活、部分接口死」的假设 |

**修法：一处编码，全线生效。**

```kotlin
// Http.kt
private fun encodeValue(raw: String): String =
    URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
```

同一个 bug 还悄悄污染了**所有带空格的关键词**：`keywords=周杰伦 - 稻香` 会变成
`+`，搜索/歌词搜索拿到的是「周杰伦+-+稻香」。真机验证：热搜词 `call your name`
修复前结果跑偏，修复后正常返回 30 首。

**第三层：`t1` 缺失被误判成「登录态失效」。**
`/login/token` 的种子是 `lite_t1("{t1}|{now}")`，`t1` 缺失时前缀变成空串，
上游**必然**回 20018。而扫码登录（`/v2/get_userinfo_qrcode`）根本不返回 `t1`，
于是每次冷启动都会：签到前自检 → 调 `/login/token` → 20018 → 判死 → 红字
「登录态已失效，请重新登录」，而此刻日历、会员到期、歌单全是好的。

修法：**把「没材料」和「凭证死了」分开**。

- `YouthVip.renewLogin()`：`!Auth.hasRenewSeed()` 直接返回 `true`，不打这个必然
  失败的往返
- 新增 `YouthVip.ensureLoginValid()`：有种子走续期，没种子改用 `/user/detail`
  验活（它是唯一能精确判定 token 死活的接口）

**第四层：`297002` 被当成失败。** 同一天重复升级概念版时上游回
`297002 / 已经领取过升级vip奖励`，早期只认 `20030`，于是每次补签都红字报
「升级概念版失败」，其实权益早就到账。真机抓到的完整双签到码：

```
claim=0/131001   upgrade=0/297002   msg=已经领取过升级vip奖励
```

**真机验收（2026-10-27，小米 23116PN5BC / b0500920，release 包覆盖安装）**

| 项 | 结果 |
|---|---|
| 「我的」页 | 头像 + 昵称 **Zxxy** + `ID 2509098403` + `畅听 5 天后到期 · 概念 2 天后到期` |
| 签到日历 | 本月已签 **9 / 31 天**，今天（27）打勾 |
| 手动双签到 | 灰字「今日已签到（畅听 + 概念版）」，无红字 |
| 播放音质 | **高品质 320k**（未登录时是 128k） |
| 带空格搜索 | `call your name` → 30 首 |
| 冷启动 | 不再出现假的 `20018` 红字 |

**教训。** 「一个接口坏了」和「凭证坏了」是两件完全不同的事。这次之所以绕了远路，
是因为看到 20018 就先信了它的字面意思（「登录已过期」）。真正定位靠的是一张
**对照表**：同一张 token 打不同端点，谁的 `status=1`、谁的不行 —— 差异点就是
线索。另一个教训是：**客户端和服务端的 URL 编码约定必须显式对齐**，
`URLEncoder.encode` 的 `+` 和 `decodeURIComponent` 的 `%20` 是两个世界的语义。



### 11.8 音质对齐：从「只能 320」到 Hi-Res（2026-10-28）

**症状。** 真机最高只到 320k，而 MD3Music 能到 Hi-Res。

**根因不是权益，是客户端只写了三档。** 「我的」页的音质选择器硬编码
`128 / 320 / flac`，选不到 `high`（Hi-Res）——请求根本没发出去过，自然拿不到。
`/song/url/new` 的请求体是固定的 9 档 `qualities` 数组，`quality` 参数只用来
**从响应里挑下标**，所以「没得选」= 「拿不到」。

**第二个问题：降级方式太粗暴。** 原实现是「请求档位拿不到 → 直接跳到 128」，
而酷狗在请求档位不可用时**经常静默返回一个更低音质的链接**（不在 `fail_process`
里标记）。于是选无损可能直接掉到标准，用户只看到「怎么这么糊」。

**改法（逐行对齐上游 `KugouApiClient`）**

| 项 | 上游 | 本工程 |
|---|---|---|
| 档位代号 | `128 / 320 / flac / high / viper_tape` | 同（`KugouApi.Q_*` 常量） |
| 降级链 | `downgradeChain`：`high → flac → 320 → 128` | 同（`playUrl` 内 `downgradeChain`） |
| 实际音质核对 | 服务端回写 `data.quality`，与请求值不一致就继续降 | 同（`PlayUrl.Ok.quality`） |
| 全档位都不匹配 | 退回过程中拿到的最好结果 | 同（`qualityRank` 取最大） |
| 展示 | `labelOf(actual)` | 同（`PlayUrlStore.labelOf`），拿不到才从 CDN `_quNNN_` 反推 |
| 持久化 | SharedPreferences | 同（`dailymusic_player/quality`） |

**为什么 `high` 是 Hi-Res、`viper_tape` 是母带。** 上游 `KugouQuality` 就这么定的
（`hires = 'high'`、`master = 'hi-res'`），而 Rust 侧 `song_url_new::QUALITIES`
的下标 3 是 `high`、6 是 `viper_tape`。名字反直觉，但改成「更合理」的名字会让
下标错位，静默退回标准音质。

**蝰蛇母带没有暴露到 UI。** 上游需要「实验开关 + VIP 等级 101」双条件，开关关闭时
请求侧会钳回 Hi-Res。本工程没有实验开关，所以只做到 Hi-Res；`viper_tape` 的常量
和降级链保留，将来开开关即可用。另外一个隐患：母带档在 `QUALITIES` 下标 6，相邻的
下标 5 是 `viper_atmos`（杜比全景声），空位回退时可能退到 atmos 流，不是想要的格式。

**真机验收（2026-10-28，小米 23116PN5BC / b0500920，release 覆盖安装）**

| 项 | 结果 |
|---|---|
| 音质选择器 | 四档齐全：标准 / 高品质 / 无损 / Hi-Res |
| 选 Hi-Res 播「指纹」 | 播放页右上角 **Hi-Res**，`0:41 / 4:00` 完整播放（不是 30s 试听） |
| 选无损播「翩翩」 | 「当前实际音质」= **无损 FLAC** |
| 选高品质 | **高品质 320k** |
| 标签真实性 | 同一首歌切档位，标签跟着变 —— 读的是服务端回写的 `data.quality`，不是照抄请求值 |
| 持久化 | 选 Hi-Res 后 `force-stop` + 冷启动，仍选中 Hi-Res（端口从 47021 变 10906，确认是新进程） |
| 覆盖安装 | 登录态、签到状态、收藏、历史全部保留 |

**结论：这个账号的权益覆盖到 Hi-Res。** 之前「最高 320」纯粹是客户端没写这两档，
一次请求都没发出去过。

### 11.9 界面回归 mockup：三屏重做 + 歌词名单过滤（2026-10-28）

**背景。** 用户反馈「应用界面和一开始做的 mockup.html 不一样了」。视觉稿
`design/mockup.html` 是 605 行的三屏规格（首页 / 播放页 / 歌词页），前几轮迭代
（尤其音质那轮）把界面越改越偏。这轮按稿子逐项对齐。

**改了什么**

| 屏 | 关键偏差 | 现在 |
|---|---|---|
| 首页 | 大标题字重不足、区块间距 22dp | `.h1` 31sp / 800 / 字距 -1.1sp；`.sec-label` 上 26 下 10 |
| 首页 | 搜索药丸用 `surfaceContainer` 发灰，四宫格是白的 | 统一 `cardBg`（浅色纯白）+ 2dp 淡影 |
| 底栏 | 左右 20dp、距底 14dp、无描边 | 左右 18dp、距底 22dp、1dp 白色描边 |
| 迷你条 | 左右 20dp、间距 10dp、圆角 26dp、主题色圆钮 | 16dp / 14dp / 24dp / 普通图标按钮 |
| 迷你条 | 染色 85% 白，压在白色列表上白压白 | 68% 白 + 14dp 阴影 + 1dp 描边 |
| 播放页 | 雾化渐变均分三档，底部仍是蓝灰 | 按稿子 0% / 46% / 78% 设 stop，78% 以下纯底色 |
| 播放页 | 进度点「白圈 + 蓝芯」 | 纯白 19dp + 一层半透明黑当阴影 |
| 播放页 | 封面 0.78 屏宽（约 280dp） | 0.835（约 300dp，稿子定的是 300） |
| 播放页 | 「正在播放」无字距、歌名 700 | 字距 1.1sp；歌名 800 + 字距 -0.6sp + 行高 29sp |
| 歌词页 | 远行 18sp / 600 | 19sp / 700，行高 28sp |

**玻璃参数统一。** 视觉稿 `.glass` 是 `rgba(255,255,255,.68)` +
`border 1px rgba(255,255,255,.66)` + `box-shadow 0 14px 34px rgba(0,0,0,.14)`。
之前底栏染色只有 40% 白、且没有描边 —— 在纯白列表上完全分不出边界，看起来像
「一块实心白板」。抽成 `glassTint()` / `glassBorder()` 两处共用。

**顺手修掉的真 bug：歌词开头的制作名单。**

酷狗把制作名单塞进歌词流，这首歌前面有 **14 行**：

```
指纹 - 杜宣达                          ← 标题行
作词：周剑光 / 作曲：郑宇界 / 原唱：胡歌 / 编曲：任斌
和声：… / 出品：… / 音乐发行：… / 制作总监：…
母带工程师：郑昊杰
策略营销：武鹏 / 项目统筹：… / 企划营销：… / 项目协力：… / 封面设计：…
```

不裁掉的话，进歌词页第一屏全是人名，用户得往上翻才看得到歌词。

`LyricParser.dropLeadingCredits()` 从头部**连续**裁剪，踩了两个坑 —— 都是
「连续断了就一行都裁不掉」：

1. **第一行是标题行**「歌名 - 歌手」，它不是名单 → 循环第一步就 break。
   所以 `isTitleLine()` 也要算进可跳过集合（靠 `[ti:]` / `[ar:]` 元信息匹配）。
2. **「母带工程师」以「工程师」结尾**，用 `endsWith` 匹配不到「母带」→ 又断一次。
   改成多字标签用 `contains`；单字标签（词 / 曲 / 编）仍只做全等，否则会误伤
   「这首歌的词：…」这类正文。

兜底：裁完至少剩 4 行，否则说明判错了，宁可脏一点也不要变成空歌词。

**另一个值得记的视觉陷阱。** 首页列表最后一行被底栏压住时，心形图标只露出
上半部分（两个圆弧 + 中间凹陷），看起来**非常像一个「v」形箭头**。我一度以为是
某个没找到来源的 `ChevronDown`，滚动列表后它就消失了才确认是遮挡造成的。
修法不是去掉图标，而是把 `bottomSpace` 从「正好等于底栏高度」加了 12dp 呼吸。

**真机验收（b0500920，release 覆盖安装）**

| 项 | 结果 |
|---|---|
| 首页 | `m22-home.png`：31sp 大标题 + 白色搜索药丸 + 四宫格 + 132dp 歌单 + 白色圆角卡片列表 |
| 播放页 | `m23-player.png`：300dp 封面 / 纯白进度点 / 72dp 主题色播放键 / 底部 Hi-Res 徽章 |
| 歌词页 | `m28-lyrics.png`：名单裁干净，第一屏直接是「我们变成一对差点缘分」，当前行居中 + 扫色 |
| 迷你条 | `m31-mine.png`：与底栏分开 14dp，白卡片上有阴影轮廓 |
| 深色 | `m29-dark-home.png` / `m30-dark-player.png`：`#000` 底 + `#1c1c1e` 卡片 + `#5b9dff` 强调色 |

### 11.10 底部四个空图标接上真实功能（2026-10-28）

**背景。** 用户反馈「歌词页和播放页底部的定时、评论、播放列表、更多这几个图标
目前点了没反应」，并明确「蝰蛇母带不做」。

**做了什么**

| 图标 | 实现 | 落点 |
|---|---|---|
| 播放列表 | `QueueSheet`：`LazyColumn` 展示 `PlayerManager.queue`，当前曲主题色 + 暂停图标 | `PlayerSheets.kt` |
| 定时 | `SleepTimerSheet`：15/30/45/60/90 分钟 + 「播完本曲」；进行中显示「关闭定时」 | `SleepTimer.kt` |
| 评论 | `CommentsScreen`：全屏 Dialog，头像 / 昵称 / 日期 / 内容 / 点赞数 | `CommentsScreen.kt` |
| 更多 | `MoreSheet`：收藏、复制歌曲链接、歌曲信息（可切到信息列表） | `PlayerSheets.kt` |

歌词页底部原本稿子里是「列表管理」图标，没有对应功能，换成 `Clock` 做睡眠定时；
顶栏右上角的 `MoreHoriz` 也接上了 `MoreSheet`。

**为什么用 Dialog 而不是在 RootScreen 里传状态。** `Dialog(usePlatformDefaultWidth = false)`
有独立 window，天然盖在播放页/歌词页之上，不用把「哪个弹层开着」的状态往上提。
代价是遮罩和底部卡片要自己画（`SheetScaffold`）。

**四个坑**

1. **`MediaController` 没有 `setPauseAtEndOfMediaItems`。** 那是 ExoPlayer 的
   `@UnstableApi` 方法，加 `@OptIn` 也编不过 —— 控制器上根本没暴露。
   「播完本曲」只能靠 ticker 提前 **350ms** 踩刹车；再晚就到 `STATE_ENDED`，
   队列会自己切下一首，等于没停住。
2. **`/comment/music` 没有 `data` 包裹。** 返回是 `{status, count, list, star_cmts}`，
   `count`/`list` 直接挂顶层。按 `j.obj("data")` 取会永远拿到 null，然后 fallback 成
   「还没有评论」—— 而这首歌实际有 45725 条。参数是 `mixsongid`（= `albumAudioId`，
   不是 file hash）。
3. **`Log.e` 在 release 包里抓不到**（R8 去掉或 tag 被过滤）。排查只能靠截图 + 滚动
   + 切 tab。另外 `uiautomator dump` 对正在播放的界面会报 `could not get idle state`
   —— 进度条一直在动，永远等不到 idle，所以坐标只能靠像素分析。
4. **「下一首播放」对当前曲没有语义。** 「更多」只从播放页/歌词页打开，对象永远是
   当前曲；把它「挪到自己后面」只会把真正的下一首挤到第三位。最后决定：当前曲时
   这一项**直接不显示**，而不是留一个点了没反应的死项。

**顺手修掉的两个「看起来像 bug」的东西**

- **底部胶囊下面漏出半行列表。** 底栏是悬浮玻璃胶囊，下面还留着 22dp + 手势区的
  一截，列表滚到中途时内容会从这一截漏出来，像被裁断的行。修法不是加 `bottomSpace`
  （那只管列表末尾），而是在胶囊背后铺一层渐变：**从距底 86dp 往下**由透明渐变到
  页面底色。渐变的过渡段整段压在胶囊背后看不见，露出来的缝隙已经是纯底色。
  只写「透明→底色」两档会让最靠近胶囊的几行反而最清楚（渐变顶端最透），等于没盖。
- **歌曲信息里的「码率 128 kbps」和正在放的 Hi-Res 打架。** `song.bitrate` 是搜索
  接口给的源文件元数据，固定 128k mp3。改成「音质」显示 `actualQualityLabel`
  （真实播放音质），文件大小那行标签改成「源文件大小」，免得用户拿 3.7 MB 去质问
  「Hi-Res 怎么这么小」。

**真机验收（b0500920，release 覆盖安装）**

| 项 | 结果 |
|---|---|
| 队列 | `f2-queue.png`：30 首，当前曲主题色 + 暂停图标 |
| 定时 | `f3-sleep.png` / `f4-sleep-set.png`：设 15 分钟后左下角出现「14:57」，时钟图标变主题色 |
| 播完本曲 | `g5.png`：5:45 / -0:00 停住，歌名仍是当前曲（没自动跳下一首），定时徽章自动消失 |
| 评论 | `f6-comments.png`：45725 条真实数据 |
| 更多 | `i5.png`：收藏 / 复制歌曲链接 / 歌曲信息（当前曲时无「下一首播放」） |
| 复制链接 | 粘贴到搜索框验证，剪贴板里是 `https://www.kugou.com/song/#hash=…&album_id=…` |
| 歌曲信息 | `j3.png`：音质 Hi-Res / 源文件大小 3.7 MB / Hash / AlbumAudioId |
| 歌词页 | 队列 `f10-lyrics-queue.png`、定时 `f11-lyrics-sleep.png`、更多 `j2.png` 全部可用 |
| 底栏缝隙 | `i2.png`：胶囊下方已是纯底色，不再漏列表行 |

### 11.11 播放页竖排重算 + 首页四宫格落地（2026-10-29）

**播放页「控件太靠上」。** 根因是竖排只有一个 `weight(1f)`，夹在播放键和底部工具条之间。
真机 411×914dp 比视觉稿 392×818 高出近百 dp，多出来的高度全灌进那一段 ——
上半屏挤、下半屏一大片空，看着就是控件靠上。

改成 `BoxWithConstraints` + 按比例分摊：`extra = maxHeight - 780dp`，
四段留白 = 视觉稿基准（34/28/40/32）+ `extra × .20/.16/.22/.18`，
最后一段仍留 `weight(1f)` 兜底。基准必须用视觉稿的 34/28/40/32 而不是原来手写的
10/24/20/22 —— 后者本身就把控件顶高了 24dp。

**首页四宫格。** 之前：每日推荐只滚列表、排行榜直接进第一个榜、歌单广场跳搜索页、
识曲空实现。现在四个都有落地页，导航用 `RootScreen` 里的 `sealed interface SubPage`
（互斥，不用四个 Boolean）。

| 入口 | 页面 | 接口 |
|---|---|---|
| 每日推荐 | `DailyScreen` | `/everyday/recommend` |
| 排行榜 | `RankListScreen` → `PlaylistScreen` | `/rank/list` |
| 歌单广场 | `PlaylistSquareScreen` → `PlaylistDetailScreen` | `/top/playlist` → `/playlist/track/all` |
| 识曲 | `RecognizeScreen` | 录音 8s → `/audio/match` |

返回键优先级调整为 `openRank` 先于 `subPage`，否则「排行榜 → 某个榜」返回会一路跳回首页。

**识曲的四个约束**

1. `AudioRecord` 用 `MIC` 而不是 `VOICE_RECOGNITION`：后者开 AGC/降噪，频谱被改得指纹匹配不上。
2. 16kHz 采集 → 8kHz 必须**窗口均值**降采样，直接抽点会混叠。
3. 去直流 + 增益归一化到 25000；振幅 < 30 判静音，不上传（省一次必然失败的请求）。
4. 识别结果经常**没有 hash**，只有 `album_audio_id` + 歌名歌手 → 回退「歌手 + 歌名」搜索取第一条。

**接口解析的坑**

- `/playlist/track/all` 曲目有两种形态：`audio_info.hash_128` 嵌套 / 顶层 `hash`，两种都要认。
- `/top/playlist` 列表键可能是 `special_list` / `plist` / `list`。
- 取歌单曲目要 `global_collection_id`，不是 `specialid`。

**状态**：release 编译通过；真机 `b0500920` 掉线，尚未装机验收。

### 11.12 收藏同步到账号「我喜欢」（2026-10-29）

**问题。** `Library.toggleFavorite` 只写 SharedPreferences，与账号完全无关：
换台设备登录后账号「我喜欢」是空的，App 收藏页也只有本机那几首。

**做法。** 新增 `data/FavoriteCloud.kt` —— 点红心时后台写云端，冷启动/登录后拉云端整表合并。

| 动作 | 接口 | 关键点 |
|---|---|---|
| 定位「我喜欢」 | `/user/playlist` | `name == "我喜欢"` 或 `is_def == 2` |
| 拉整表 | `/playlist/track/all/new?listid=` | 私有歌单**不能**用 `global_collection_id` 版本 |
| 加歌 | `/playlist/tracks/add` | `data = 歌名\|hash\|album_id\|album_audio_id`，四段给全 |
| 删歌 | `/playlist/tracks/del` | 必须传歌单记录 `fileid`，传 hash 回 status=1 但歌还在 |

**合并策略 = 并集**，不是云端覆盖本地：云端有本地没有 → 收进来；本地有云端没有 → 留着并补传。
不做「云端没有就删本地」——一次同步失败就会清空用户的收藏，代价太大。

**换账号防串号。** `Library` 记录 `fav_owner_v1 = userid`；owner 与当前账号不一致时
不补传、直接以云端为准，否则会把上一个账号的收藏灌进新账号。

**UI。** 收藏页标题下加状态行（未登录 / 正在同步 / 同步失败 / 已与账号「我喜欢」同步）
+「立即同步」；「更多」面板的收藏项加副标题说明写到哪。

**状态。** release 编译通过；真机 `b0500920` 仍掉线，接口实际返回未在设备上验证。

### 11.13 歌词页对齐 Apple Music（2026-10-29）

| 维度 | 之前 | 现在 |
|---|---|---|
| 远行亮度 | 一律 26% 白 | 按距离三档：1 行 46% / 2 行 24% / 更远 12% |
| 当前行字号 | 25sp（直接改 fontSize） | 固定 19sp + `graphicsLayer` 缩放 1.34×（≈25sp），换行不跳 |
| 高亮基准 | `indexAt`（间奏返回 -1，整页闪一下） | `anchorIndex`（上一句亮到下一句开口） |
| 换行滚动 | 默认 tween | spring（damping .88 / stiffness 210），末尾轻微过冲 |
| 背景 | 静态模糊封面 | 10s 呼吸缩放 1.30↔1.42；暂停时暗雾 0.40→0.58 |
| 点歌词 | 无 | seek 到该行 `startMs`，无 ripple |
| 进度条 | 4dp 细线 | 加 9dp 白点把手 + 左右 `0:42` / `-2:20` |
| 底栏 | 队列+上一首 / 播放 / 下一首+定时 | `[队列] — ⏮ ⏯ ⏭ — [定时]`，播放键 56dp 白圆黑图标 |
| 顶栏 | 返回箭头 | `ChevronDown`（收回去） |
| 进出 | 横向滑入 | `fadeIn + scaleIn(1.05)` |

`LyricUnsung` 从 0.28 提到 0.45：现在只有当前行走扫色，这个常量等于「待唱部分多亮」，
0.28 会把整句读成「唱过的白 + 没唱的灰」。

**状态**：release 编译通过；真机 `b0500920` 仍掉线，未装机目视验收。

### 11.14 歌词动效（WAVE / KTV）+ 首页头像 + 底栏几何（2026-10-31）

**歌词动效。** 歌词页顶栏加了「动效」入口（星星图标），打开一页 16:9 横屏卡片轮播：

| 模式 | 画法 |
|---|---|
| WAVE 动感波光 | 模糊封面 → 青绿径向渐变 → 五圈共用同一相位、各错开 1/5 的白色波纹；歌词居中，背后垫一份 `blur(13.dp)` 的同款文字当光晕 |
| KTV 模式 | 封面画面（`blur(2.dp)`）+ 45% 黑罩 + 歌名水印；歌词贴底逐字扫色 |

- 逐字扫色复用竖屏歌词页的 `sweepBrush`（`private` → `internal`），两个模式基准一致。
- 卡片内自带封面 / 歌名 / 上一首 / 播放 / 下一首 / 收藏，动效页里不用退出去就能控播。
- 左右滑切换，底部圆点 + 「左右滑动切换动效」提示（只有一个手势，不写出来没人试）。

**首页头像。** 40 → 44dp，并且从「Column 底部对齐」改成「和标题同一行、垂直居中」——
之前头像挂在 title + 问候两行的 Column 底部，落在问候语那一行，比标题低 21dp。点击进「我的」。

**底栏几何。** 三处一起改：

1. 默认宽度 0.26 → 0.45（0.26 只够刚好包住两个图标，绷得很紧）；
2. 内边距四边统一 10dp。胶囊高 64 - 上下各 10 = 44，正好等于图标按钮尺寸，
   选中态那颗圆到四边都是 10dp。原来是左右 6dp + 上下 6dp + 4dp 余量 → 圆离左边 6、
   离上下 10，看着像往左上角挤；
3. **中间插槽的 weight 挪到外层 Box 上**。`AnimatedVisibility` 隐藏时把自己测成 0 宽
   （无视 weight 给的固定宽度），Row 按实际宽度依次排布 → 右侧图标紧跟左侧图标，
   胶囊右边空出一大块。中间留一个真实占位的 Box 才能让两颗图标贴住两端。

顺带踩到：在 Row 的 lambda 里包一层 Box 之后，`AnimatedVisibility` 会被解析成
`RowScope.AnimatedVisibility`，编译报「cannot be called in this context with an
implicit receiver」。抽成独立的 `MiniBarSlot` 函数（weight 由调用方传入）即可。

版本 1.0.3（versionCode 5），tag v1.0.3 + GitHub Release 已发，version.json 同步。

### 11.15 滑动手势 / 底栏自动隐藏 / 动效横屏 / 更新多镜像（2026-10-31）

**首页 ↔ 我的 左右滑。** 判断依据是「事件有没有被子节点消费」：在
`awaitEachGesture` 里读 `PointerEventPass.Final`，只要有一段被消费（说明 LazyRow 在横滑）
就不切页。于是列表行、空白区横滑会切页，推荐歌单/模块卡片上横滑仍然是滚那一行。

**底栏 3 秒自动隐藏 + 空闲黑胶。**
- 用 `barTick` 自增重置计时（Boolean true→true 不会重启 `LaunchedEffect`）；
- 根 Box 用 `PointerEventPass.Initial` 只观察 press、不消费，所以任意点击都能唤醒底栏，
  又不会把点击从列表行手里抢走；
- 空闲态从「一行居中的每日一言」改成「旋转黑胶（Canvas）+ 跑马灯短句」，宽度 0.45 → 0.56。
- **坑**：`AnimatedVisibility` 隐藏时连内容一起不组合，空闲态会变成一大块空白 ——
  内容改用 `AnimatedContent` 按 `song != null` 切换。

**歌词动效横屏全屏沉浸。**
- `requestedOrientation = SENSOR_LANDSCAPE` + `WindowInsetsControllerCompat.hide(systemBars())`；
  MainActivity 已声明 `configChanges`，旋转不重建、状态不丢。
- 去掉顶部「歌词动效 · WAVE」标题；操作区（返回/歌名/控制/圆点）5 秒淡出，轻点唤回
  （同样只观察不消费，pager 的横滑不受影响）。
- **WAVE 重做**：上一版是 1~5dp 细圆环（像雷达扫描），改成每条带宽 ≈ 半径 9% 的宽光带，
  三层叠加做柔边；背后加一份 88sp、26% 的「幽灵字」。

**在线更新：8 源并发竞速。**
- `version.json` 从「逐个试、每个 8s 超时」改成同时打 8 个源，谁先回用谁（raw.gitmirror /
  jsdelivr / ghfast / gh-proxy / ghproxy.net / moeyy / llkk / 官方 raw）。
- 下载同样先并发探路（只建连不读），拿最先 200 的那条直接流式写入。
- **坑**：`withTimeoutOrNull { channel.receive() }` 收到 `null` 和超时无法区分，
  往 channel 里送 `null` 会让循环被一次失败直接打断 —— 只送成功结果。

版本 1.0.4（versionCode 6）。
