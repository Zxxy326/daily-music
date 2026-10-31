# Daily Music

一款 HyperOS 风格的在线音乐客户端，用 **Kotlin + Jetpack Compose + Miuix** 构建。

> 当前状态：**M2 – M7 完成**。
> 已跑通：首页每日推荐、搜索、排行榜与歌单详情、点歌播放、真实队列、后台播放 + 媒体通知、
> 逐字歌词（翻译 / 罗马音 / 卡拉 OK 扫色）、收藏与历史（重启保留）、三种登录、音质切换、
> 悬浮玻璃底栏、平板侧栏断点、概念版每日畅听签到（自动 + 手动 + 日历）。

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
| 播放内核 | Media3 ExoPlayer 1.4.1 | `MediaSessionService` + `MediaController` |
| 内嵌服务 | Rust + tiny_http | 复用 md3Music 的酷狗 API 服务 |

## 目录结构

```
app/src/main/kotlin/com/dailymusic/app/
  MainActivity.kt
  DailyMusicApp.kt            Application：起 Rust 服务 + 初始化音乐库
  native/KugouServer.kt       JNA 绑定 Rust 导出的 C 符号
  core/Http.kt                HttpURLConnection 的极简 JSON/bytes 封装
  data/
    Song.kt                   归一化歌曲模型（+ 宽松 JSON 扩展）
    Lyrics.kt                 KRC / LRC 解析（逐字 + 翻译 + 罗马音）
    KugouApi.kt               所有本机服务端点封装
    Rank.kt                   排行榜 / 歌单
    Library.kt                收藏 + 历史（JSON 落盘，重启保留）
    Auth.kt                   登录态持久化
    YouthVip.kt               概念版双签到（畅听 + 升级）+ 打卡记录 + 会员到期
  playback/
    PlayerManager.kt          持有 MediaController，UI 唯一状态源
    PlaybackService.kt        持有 ExoPlayer + MediaSession（服务是播放器所有者）
    PlayUrlStore.kt           解析式数据源：mediaId → 真实音频地址（懒解析）
  ui/
    RootScreen.kt             主骨架 + 玻璃底栏 / 侧栏 + 迷你播放器
    HomeScreen.kt             每日推荐 + 排行榜
    SearchScreen.kt           搜索 + 热搜
    PlaylistScreen.kt         榜单详情
    LibraryScreen.kt          收藏 / 历史
    MineScreen.kt             我的（音质、入口、签到状态、登录态）
    LoginScreen.kt            扫码 / 手机号（含多账号选择）/ Cookie 导入
    SignInScreen.kt           每日畅听：签到 + 月历打勾 + 会员到期
    PlayerScreen.kt           全屏播放页（手机单列 / 平板两栏）
    Glass.kt                  liquidGlass 修饰符（miuix-blur 封装）
    Adaptive.kt               宽度断点
    Components.kt / AppIcons.kt / RemoteImage.kt
  ui/theme/Theme.kt           MiuixTheme + Monet 动态取色
app/src/main/jniLibs/         libkugou_server.so（arm64-v8a / x86_64）
rust/kugou_server/            酷狗 API 服务（AGPL-3.0，见下）
scripts/build.sh              一键：Rust 交叉编译 → 拷 jniLibs → 打 APK
design/                       视觉稿与运行截图
PLAN.md                       决策记录、里程碑、版本矩阵、踩坑清单
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

## 关键实现要点

**播放链路必须是「服务持有播放器」**。`MediaSessionService` 里自建 `ExoPlayer` 并交给
`MediaSession`，UI 侧只拿 `MediaController`。早先直接在 UI 里持 `ExoPlayer`，结果是
session 永不注册、没有媒体通知、服务不进前台。

**地址是懒解析的**。队列里每个 `MediaItem` 的 URI 是 `dailymusic://play/<songKey>`，
真正的 HTTP 地址由 `PlayUrlStore` 在 `ResolvingDataSource` 里现取。这样切歌才够快，
也能在登录/切音质后统一失效重取。

**音质是逐级降级的，不是「拿不到就掉 128」**。四档 `标准 / 高品质 / 无损 / Hi-Res`
（代号 `128 / 320 / flac / high`，`high` 就是 Hi-Res —— 上游 `KugouQuality.hires` 的
取值，改名字会让 Rust 侧 `QUALITIES` 下标错位）。选了高档位时按
`high → flac → 320 → 128` 逐级试，**每一档都核对服务端回写的 `data.quality`**：
酷狗在音源不可用时经常静默返回更低音质的链接，不核对就会「选了 Hi-Res 实际在放 320」。
全部对不上时退回过程中拿到的最好结果。界面上的「当前实际音质」读的就是这个回写值，
所以它永远是真的。

**玻璃要能模糊到东西**。`LayerBackdropNode.draw()` 是先 `drawContent()` 再录制，
所以不透明背景必须写在 `layerBackdrop` **之后**（内层）。写在外层会导致录制进透明区，
模糊采样到透明被当黑色，玻璃上出现深色色块。

**加密曲目（.mgg）不可播，约占 1/4**。部分曲目所有音质档位都只下发 `en_tracker_url`
（DRM 流），换档位 / 换端点 / 加 `ppage_id` 都拿不到明文地址。实测 60 首样本：
42 首全明文、15 首全档位加密、3 首无源。失败一次后列表里会给该曲目标红色「加密」标记，
队列里自动跳过。调查结论见 `PLAN.md` §11.5。

**登录了还只能试听？不是身份问题，是没签到。** 这套接口用的是**酷狗概念版**身份
（appid `3116` / clientver `11440`，见 `rust/kugou_server/src/config.rs`）。概念版的权益
模型是「每天签到领一天畅听」，不是买会员 —— 不签到账号就是零权益，VIP 曲目只给 60 秒。
所以 `YouthVip` 做了完整双签到：`/youth/day/vip`（领畅听）→ `/youth/day/vip/upgrade`
（升级概念版）。`receive_day` 用 `/server/now` 的服务端日期算，成功后还会走一次
`/login/token` 换新凭证 —— 不换的话页面显示签到成功、播歌还是试听。

**「我的」页显示真实账号**。以前只渲染「已登录」四个字 —— 那是拿 `Auth.isLoggedIn`
判断的，只要本地有 token 就显示，跟上游认不认毫无关系。现在 `Profile` 会拉
`/user/detail` 拿昵称/头像/ID，顺带校验登录态：`status != 1` 就在卡片上打红字
「登录态已失效，请重新登录」，整张卡可点直接去登录页。

**token 过期是静默的，靠 `t1` 才能自愈。** 酷狗概念版的 token 会过期，而
`/login/token` 续期的种子是 `lite_t1("{t1}|{now}")` —— `t1` 只在登录响应里给一次。
以前没存它，于是形成死局：token 过期 → 续期缺种子 → 永远续不上 → 只能重登，而重登
又不存 `t1`。现在登录成功即持久化 `t1`，进「我的」时先续期再拉资料，过期能自动换新。
**注意：本次改动之前登录的老凭证没有 `t1`，需要重新登录一次才能进入自愈循环。**
扫码登录（`/v2/get_userinfo_qrcode`）本身不返回 `t1`，这类凭证过期后无法自愈 ——
但也不会再被误判成「登录态失效」（见下一条）。

**URL 编码的 `+` 和 `%20` 不是一回事（踩过的大坑）。** `Auth.cookieParam()` 产出
`token=…; userid=…; vip_token=…`，如果用 `URLEncoder.encode` 直接拼 query，空格
会变成 `+`；而 Rust 侧的解码按 `decodeURIComponent` 语义实现（`+` 原样保留，只有
`%20` 才是空格，这样才能和上游 Dart 客户端的 Dio 对齐）。结果 `cookie_to_json` 按
`;` 切分后第二段的 key 变成 `"+userid"` —— **`userid` / `vip_token` 全丢**，只剩
第一段 `token`。上游于是回 `20018`（签名里的身份 ≠ 请求的身份），看起来完全像
「token 过期」。修法只有一行：`URLEncoder.encode(x).replace("+", "%20")`
（见 `core/Http.kt` 的 `encodeValue`）。同一个 bug 也会污染所有带空格的搜索关键词。
`20018` 因此有**三种**成因：token 真过期、签发身份不一致、以及**token 没送到**。

## 已知约束

- `compileSdk` 必须是 37：Miuix 全部版本（0.9.0–0.9.4）的 aar-metadata 都写着 `minCompileSdk=37`
- AGP 8.11 官方最高推荐 compileSdk 是 36，用 37 依赖 `android.suppressUnsupportedCompileSdk=37`
- 设备需 API 33+ 才能跑玻璃模糊效果
- 加密格式（`.mgg`）曲目不可播，自动跳过
- 签到遇到上游滑块风控（`error_code=20028`）时无法完成，会提示去官方 App 签一次
- `20018` 有歧义：既是「登录过期」，也是「签发身份与请求身份不一致」，还是
  「token 没送到」（query 编码把 `; ` 变成 `;+`，userid 掉了）。排查顺序：
  ① 同一张 token 打 `/user/cloud/playlist` 是否 `status=1`（活了就说明 token 没问题）
  → ② 确认受影响的接口是否依赖 `userid`/`vip_token`（依赖的话看 cookie 拼装）
  → ③ 最后才怀疑凭证本身

## 许可证与免责声明

- `rust/kugou_server/` 来自 [md3Music](https://github.com/zzyoxml/md3Music)，**AGPL-3.0**，原始许可证见 `rust/kugou_server/LICENSE-AGPL.txt`。本项目作为其衍生作品，整体按 AGPL-3.0 分发。
- 项目对接的是**非官方**酷狗接口，涉及的音乐内容版权归原权利人所有。仅供个人学习与自用，**请勿用于商业用途或公开分发**。
- 使用本项目产生的一切后果由使用者自行承担。
