package com.dailymusic.app.data

import android.util.Log
import com.dailymusic.app.AppBootstrap
import com.dailymusic.app.native.KugouServer
import com.dailymusic.app.core.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗接口封装。
 *
 * 全部走本机 Rust 服务（`KugouServer` 启动的 tiny_http），不直连酷狗 ——
 * 签名/加密/设备注册都在 Rust 侧做完了。
 *
 * 几个必须记住的约束（都是实测出来的，不是猜的）：
 * 1. `/song/url` 和 `/song/url/new` 必须同时给 `hash` + `album_audio_id`，
 *    而 `album_audio_id` 是搜索结果的 `MixSongID`，**不是** `Audioid`。
 *    给错就是 `_errno: 6 / audio is not found` 或 `error_code: 31833 illegal key`。
 * 2. 匿名（未登录）只能拿到 128k。要 320/flac 必须登录后带 vip_token。
 * 3. 歌词是两跳：`/search/lyric` 拿 id + accesskey，再 `/lyric` 下载。
 *    `krctype == 1` 才是逐字 KRC，否则退回 LRC。
 * 4. `/song/url`（旧接口，走 v5/url）实测对匿名请求**恒定 502**，只能作为
 *    理论上的兜底保留，真正能用的只有 `/song/url/new`。
 * 5. Rust 服务不保存 cookie，登录态必须由客户端每次请求带 `cookie` 参数
 *    （见 [Auth.cookieJson] 与 [p]）。
 */
/** 播放地址解析结果。区分「没版权」和「只有加密流」两种失败，好给用户不同的提示。 */
sealed interface PlayUrl {
    /**
     * @param quality 上游回写的**实际**音质（`128` / `320` / `flac` / `high` / `viper_tape`）。
     *   不是请求值 —— 选了 Hi-Res 但音源只有无损时，这里会是 `flac`，UI 据此如实显示。
     */
    data class Ok(val url: String, val quality: String = "") : PlayUrl

    /** 只拿到 .mgg 加密流，客户端尚未实现解密。 */
    data object Encrypted : PlayUrl

    /** 上游直接没给可用地址（无版权 / 需登录 / 下架）。 */
    data object Unavailable : PlayUrl
}

/** 扫码 / 短信登录的轮询结果。 */
sealed interface LoginState {
    /** 还没人扫。 */
    data object Waiting : LoginState

    /** 已扫码，等用户在手机上确认。 */
    data object Scanned : LoginState

    /** 二维码过期，需要重新生成。 */
    data object Expired : LoginState

    data class Success(
        val token: String,
        val userId: String,
        val vipToken: String?,
        val vipType: Int,
        /** 续期用的设备凭证；扫码登录通常没有。 */
        val t1: String? = null,
    ) : LoginState

    data class Failed(val message: String) : LoginState
}

object KugouApi {

    // 音质代号。取值必须与上游 `KugouQuality` 完全一致，否则 Rust 侧
    // `QUALITIES` 下标对不上，会静默退回第一档（标准）。
    // 注意 `high` 是 Hi-Res、`viper_tape` 是蝰蛇母带 —— 名字反直觉但上游就这么定的。
    const val Q_128 = "128"
    const val Q_320 = "320"
    const val Q_FLAC = "flac"
    const val Q_HIRES = "high"
    const val Q_VIPER_TAPE = "viper_tape"

    /** 等本地服务起来再拼 baseUrl，避免首帧就打到 :0 端口。 */
    private suspend fun base(): String {
        AppBootstrap.await()
        return "http://127.0.0.1:${KugouServer.port}"
    }

    private val lyricCache = HashMap<String, Lyrics>()

    /**
     * 给所有请求挂上登录 cookie。
     * Rust 侧不保存会话，漏掉这个参数的后果是「登录成功了但还是匿名音质」。
     */
    private fun p(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val cookie = Auth.cookieParam() ?: return mapOf(*pairs)
        return mapOf(*pairs) + ("cookie" to cookie)
    }

    /** 首次请求前必须注册设备，否则上游给非法 dfid。幂等，Rust 侧有本地缓存。 */
    suspend fun ensureDevice(): Boolean {
        // 设备注册偶发超时（模拟器上实测过 25s 后返回失败，但同一接口用 curl 打
        // 是 38ms 就回）。一次失败就报「本地服务未就绪」代价太大，重试一次。
        repeat(2) { attempt ->
            val j = Http.getJson(base(), "/register/dev", timeoutMs = 12_000)
            if (j != null && j.int("status") == 1) return true
            if (attempt == 0) kotlinx.coroutines.delay(800)
        }
        return false
    }

    /** 每日推荐，首页主列表。 */
    suspend fun recommend(): List<Song> {
        val j = Http.getJson(base(), "/everyday/recommend", params = p(), timeoutMs = 25_000)
            ?: return emptyList()
        val list = j.obj("data")?.arr("song_list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let { Song.fromRecommend(it) }?.let { add(it) }
            }
        }
    }

    suspend fun search(keywords: String, page: Int = 1, pageSize: Int = 30): List<Song> {
        val j = Http.getJson(
            base(), "/search",
            params = p("keywords" to keywords, "page" to page, "pagesize" to pageSize),
        ) ?: return emptyList()
        val list = j.obj("data")?.arr("lists") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let { Song.fromSearch(it) }?.let { add(it) }
            }
        }
    }

    suspend fun hotKeywords(): List<String> {        val j = Http.getJson(base(), "/search/hot", params = p()) ?: return emptyList()
        val groups = j.obj("data")?.arr("list") ?: return emptyList()
        val out = ArrayList<String>()
        outer@ for (i in 0 until groups.length()) {
            val words = groups.optJSONObject(i)?.arr("keywords") ?: continue
            for (k in 0 until words.length()) {
                val word = words.optJSONObject(k)?.str("keyword").orEmpty()
                if (word.isNotBlank()) out += word
                if (out.size >= 12) break@outer
            }
        }
        return out.distinct().take(12)
    }

    /** 榜单目录。返回 55 个左右，首页只展示前若干个。 */
    suspend fun rankList(): List<Rank> {
        val j = Http.getJson(base(), "/rank/list", params = p(), timeoutMs = 25_000) ?: return emptyList()
        val list = j.obj("data")?.arr("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let { Rank.from(it) }?.let { add(it) }
            }
        }
    }

    /** 榜单曲目。`/rank/audio` 的曲目在 `data.songlist`。 */
    suspend fun rankSongs(rankId: Long, page: Int = 1, pageSize: Int = 60): List<Song> {
        val j = Http.getJson(
            base(), "/rank/audio",
            params = p("rankid" to rankId, "page" to page, "pagesize" to pageSize),
            timeoutMs = 25_000,
        ) ?: return emptyList()
        val list = j.obj("data")?.arr("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.let { Song.fromRank(it) }?.let { add(it) }
            }
        }
    }

    /**
     * 取播放地址。逐级降级，与上游 `KugouApiClient.getBestSongUrl` 一致。
     *
     * 为什么要降级链而不是「拿不到就掉到 128」：上游在请求音质不可用时**经常静默返回
     * 一个更低音质的链接**（不在 `fail_process` 里标记）。所以「拿到 URL」不等于
     * 「拿到请求的音质」—— 必须核对服务端回写的 `data.quality`，不一致就继续往下一档试。
     * 全都对不上时退回过程中拿到的最好结果，绝不把 Hi-Res 请求直接变成 128。
     *
     * 返回 [PlayUrl.Encrypted] 的情况：酷狗对部分曲目只下发 `info.en_tracker_url`（.mgg 加密流），
     * `tracker_url` 为空。上游服务端把 `/audio/proxy`（解密代理）主动关掉了，所以这类曲目
     * 在客户端解密能力做出来之前放不了 —— 实测「每日推荐」约 1/3 属于这类。
     */
    suspend fun playUrl(song: Song, quality: String): PlayUrl {
        lastSawEncrypted = false
        var best: PlayUrl.Ok? = null
        for (q in downgradeChain(quality)) {
            val r = fetchNew(song, q) ?: fetchLegacy(song, q)
            if (r == null) continue
            // 实际音质与请求档位一致 → 直接采用，不用再往下试。
            if (r.quality == q) return r
            if (best == null || qualityRank(r.quality) > qualityRank(best!!.quality)) best = r
        }
        best?.let { return it }
        return if (lastSawEncrypted) PlayUrl.Encrypted else PlayUrl.Unavailable
    }

    /** 音质代号 → 降级链，逐行对齐上游 `KugouApiClient.downgradeChain`。 */
    private fun downgradeChain(quality: String): List<String> = when (quality) {
        Q_VIPER_TAPE -> listOf(Q_VIPER_TAPE, Q_HIRES, Q_FLAC, Q_320, Q_128)
        Q_HIRES -> listOf(Q_HIRES, Q_FLAC, Q_320, Q_128)
        Q_FLAC -> listOf(Q_FLAC, Q_320, Q_128)
        Q_320 -> listOf(Q_320, Q_128)
        else -> listOf(Q_128)
    }

    /** 档位排序，用于挑「最好的一次尝试」。 */
    private fun qualityRank(q: String): Int = when (q) {
        Q_VIPER_TAPE -> 4
        Q_HIRES -> 3
        Q_FLAC -> 2
        Q_320 -> 1
        else -> 0
    }

    @Volatile
    private var lastSawEncrypted = false

    /**
     * `/song/url/new`（→ `/v6/priv_url`，登录后能拿完整音质）。
     *
     * Rust 侧 `apply_quality` 已经按 quality 从 url 数组里挑好了，并把**实际**档位
     * 写回 `data[0].quality`；这里优先用它，取不到才从 URL 的 `_quNNN_` 段反推。
     *
     * 有三道试听拦截（对齐上游 `_getSongUrlNew`）：酷狗在 VIP 没生效时**照样返回一个
     * 非空 URL**，但那只是 30s / 1min 试听片段。不拦就会「有地址、能播、几十秒就结束」，
     * 用户只会觉得播放器坏了。
     */
    private suspend fun fetchNew(song: Song, quality: String): PlayUrl.Ok? {
        val j = Http.getJson(
            base(), "/song/url/new",
            params = p(
                "hash" to song.hash.lowercase(),
                "album_audio_id" to song.albumAudioId,
                "quality" to quality,
            ),
        ) ?: return null
        val item = j.arr("data")?.optJSONObject(0) ?: return null
        val info = item.obj("info")

        // Rust 已经挑好的那一条；老版本服务没有该字段时退回 tracker_url[0]。
        val picked = item.str("url").takeIf { it.startsWith("http") }
        val tracker = info?.arr("tracker_url")?.let { a ->
            (0 until a.length()).map { a.optString(it).orEmpty() }.firstOrNull { it.startsWith("http") }
        }
        val url = picked ?: tracker
        if (url == null) {
            val encrypted = info?.arr("en_tracker_url")
            if (encrypted != null && encrypted.length() > 0) lastSawEncrypted = true
            return null
        }

        // 1) priv_status：1 = VIP 验证通过；0 = 走试听/包月/购买。
        if (item.has("priv_status") && item.int("priv_status") == 0) return null
        // 2) fail_process 非空（含 buy / pkg）= 试听兜底。
        item.arr("fail_process")?.takeIf { it.length() > 0 }?.let { return null }
        // 3) 体积兜底：3~5 分钟的歌通常 > 2MB，不到 200KB 几乎一定是 30s 片段。
        val fileSize = item.long("fileSize").takeIf { it > 0 } ?: item.long("file_size")
        if (fileSize in 1 until 200 * 1024) return null

        val actual = item.str("quality").ifBlank { qualityFromUrl(url) }.ifBlank { quality }
        return PlayUrl.Ok(url, actual)
    }

    /** `/song/url`（旧接口，走 v5/url）。实测对匿名请求恒定 502，只作兜底。 */
    private suspend fun fetchLegacy(song: Song, quality: String): PlayUrl.Ok? {
        val j = Http.getJson(
            base(), "/song/url",
            params = p(
                "hash" to song.hash.lowercase(),
                "album_audio_id" to song.albumAudioId,
                "album_id" to song.albumId,
                "quality" to quality,
            ),
        ) ?: return null

        // v5/url 的返回结构在不同版本里飘过，几种都试一遍。
        val data = j.obj("data")
        // `fail_process: ['buy']` 说明该音质要购买/VIP，给的 URL 是试听片段。
        // 必须在取 url **之前**拦掉，否则会抢先返回一段 30s 试听。
        val failProcess = data?.arr("fail_process") ?: j.arr("fail_process")
        if (failProcess != null && (0 until failProcess.length()).any {
                failProcess.optString(it) == "buy"
            } && quality != Q_128
        ) {
            return null
        }
        val url = sequenceOf(
            j.arr("url")?.optString(0),
            data?.str("url"),
            data?.arr("url")?.optString(0),
        ).firstOrNull { !it.isNullOrBlank() && it.startsWith("http") } ?: return null
        val actual = (data?.str("quality") ?: j.str("quality"))
            .ifBlank { qualityFromUrl(url) }.ifBlank { quality }
        return PlayUrl.Ok(url, actual)
    }

    /**
     * CDN 路径里的音质段反推档位：`..._qu320_...` → `320`。
     *
     * 只在服务端没回 `quality` 时兜底。段值口径来自酷狗 CDN：
     * `0` 试听、`128`/`320` 有损、`999` 无损、`2000` 以上 Hi-Res。
     */
    private fun qualityFromUrl(url: String): String {
        val q = Regex("""_qu(\d+)_""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        return when {
            q <= 0 -> Q_128
            q <= 320 -> if (q <= 191) Q_128 else Q_320
            q < 1500 -> Q_FLAC
            else -> Q_HIRES
        }
    }

    /** 歌词。失败返回 [Lyrics.Empty]，调用方不用判空。 */
    suspend fun lyric(song: Song): Lyrics {
        lyricCache[song.key]?.let { return it }

        val search = Http.getJson(
            base(), "/search/lyric",
            params = p(
                "hash" to song.hash.lowercase(),
                "duration" to song.durationMs,
                "keywords" to "${song.singer} - ${song.name}".trim(),
                "album_audio_id" to song.albumAudioId,
            ),
            timeoutMs = 25_000,
        )
        val candidate = search?.arr("candidates")?.optJSONObject(0)
            ?: search?.arr("ugccandidates")?.optJSONObject(0)
            ?: return Lyrics.Empty

        val id = candidate.str("id")
        val accessKey = candidate.str("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return Lyrics.Empty

        // krctype: 1 = KRC 逐字，0 = 普通 LRC
        val krcAvailable = candidate.int("krctype") == 1

        val body = Http.getJson(
            base(), "/lyric",
            params = p(
                "id" to id,
                "accesskey" to accessKey,
                "fmt" to if (krcAvailable) "krc" else "lrc",
                "decode" to 1,
            ),
            timeoutMs = 25_000,
        ) ?: return Lyrics.Empty

        val content = body.str("decodeContent").ifBlank { body.str("content") }
        if (content.isBlank()) return Lyrics.Empty

        val parsed = LyricParser.parse(
            content,
            isKrc = krcAvailable,
            fallbackTitle = song.name,
            fallbackArtist = song.singer,
        )
        if (!parsed.isEmpty) lyricCache[song.key] = parsed
        return parsed
    }

    // ---------------- 评论 ----------------

    /**
     * 歌曲评论。`null` 表示请求失败（超时 / 上游 5xx），调用方要区分「加载失败」
     * 和「确实没有评论」—— 两者都渲染成空列表的话，用户会以为这首歌真的没人评论。
     *
     * 走的是 `/comment/music`（mcomment/v1/cmtlist），参数名是 `mixsongid`，
     * 传的是 `albumAudioId` 而不是 file hash。
     */
    suspend fun musicComments(song: Song, page: Int = 1, pageSize: Int = 30): CommentPage? {
        if (song.albumAudioId <= 0L) return null
        val j = Http.getJson(
            base(), "/comment/music",
            params = p(
                "mixsongid" to song.albumAudioId.toString(),
                "page" to page,
                "pagesize" to pageSize,
            ),
            timeoutMs = 25_000,
        ) ?: return null
        if (j.int("status") != 1) return null

        // 注意：这个接口**没有 data 包裹**，count / list 直接挂在顶层：
        //   { status:1, count:45725, list:[...], star_cmts:{...} }
        // 早期版本返回过 { data: { list: [] } }，所以两种都兼容 —— 只认 data
        // 的话，线上会稳定显示「还没有评论」，而实际有 4 万多条。
        val root = j.obj("data") ?: j
        val total = root.int("count").takeIf { it > 0 }
            ?: root.int("combine_count").takeIf { it > 0 }
            ?: root.int("total").takeIf { it > 0 }
            ?: 0

        val arr = root.arr("list") ?: root.arr("hot_list")
            ?: return CommentPage(total, emptyList())

        val list = ArrayList<Comment>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { Comment.from(it) }?.let { list += it }
        }
        return CommentPage(total.takeIf { it > 0 } ?: list.size, list)
    }

    // ---------------- 登录 ----------------

    /** 二维码 key。`/login/qr/key` 返回 `data.qrcode`。 */
    suspend fun qrKey(): String? {
        val j = Http.getJson(base(), "/login/qr/key", timeoutMs = 20_000) ?: return null
        val data = j.obj("data") ?: return null
        return data.str("qrcode").ifBlank { data.str("key") }.ifBlank { null }
    }

    /**
     * 二维码图片。`/login/qr/create?qrimg=1` 的 `data.base64` 是 data URL
     * （`data:image/png;base64,...`），不是裸 base64，解码前要切掉前缀。
     */
    suspend fun qrImage(key: String): String? {
        val j = Http.getJson(
            base(), "/login/qr/create",
            params = mapOf("key" to key, "qrimg" to 1),
            timeoutMs = 20_000,
        ) ?: return null
        return j.obj("data")?.str("base64")?.ifBlank { null }
    }

    /**
     * 轮询扫码状态。
     * 上游 status：0 过期 / 1 等待 / 2 已扫码待确认 / 4 成功（同响应带 token）。
     */
    suspend fun qrCheck(key: String): LoginState {
        val j = Http.getJson(
            base(), "/login/qr/check",
            params = mapOf("key" to key),
            timeoutMs = 20_000,
        ) ?: return LoginState.Waiting

        val data = j.obj("data") ?: j
        val status = data.int("status").takeIf { it != 0 } ?: j.int("status")
        // 扫码是全流程里最难查的一环：上游把「过期 / 等待 / 已扫 / 成功」都塞在
        // `data.status`，失败时还可能是字符串。这条日志是唯一的现场证据。
        Log.i("DailyMusic", "qr/check status=$status key=${key.take(8)}…")
        return when (status) {
            0 -> LoginState.Expired
            1 -> LoginState.Waiting
            2 -> LoginState.Scanned
            4 -> {
                // Rust 侧在成功时把 token/userid 提升到响应体顶层，两处都兜一下。
                val token = j.str("token").ifBlank { data.str("token") }
                val userId = j.str("userid").ifBlank { data.str("userid") }
                Log.i(
                    "DailyMusic",
                    "qr/check 成功分支 token=${token.take(6)}… userid=$userId " +
                        "t1=${if (data.str("t1").isBlank()) "无" else "有"}",
                )
                if (token.isBlank() || userId.isBlank()) {
                    LoginState.Failed("登录成功但没有拿到 token")
                } else {
                    LoginState.Success(
                        token = token,
                        userId = userId,
                        vipToken = j.str("vip_token").ifBlank { data.str("vip_token") }.ifBlank { null },
                        vipType = j.int("vip_type").takeIf { it > 0 } ?: data.int("vip_type"),
                        t1 = data.str("t1").ifBlank { null },
                    )
                }
            }

            else -> LoginState.Waiting
        }
    }

    /**
     * 发送短信验证码。
     *
     * 以前这里判的是 `errcode`，但上游字段叫 `error_code` —— 名字差一个字，
     * 结果永远读到 0，**任何失败都显示「已发送」**。真机踩过：号码格式不对也提示
     * 发送成功，用户白等短信。现在改成读 `status`，失败时把上游原文带出去。
     */
    suspend fun sendSmsCode(mobile: String): SmsSendResult {
        val j = Http.getJson(
            base(), "/captcha/sent",
            params = mapOf("mobile" to mobile),
            timeoutMs = 25_000,
        ) ?: return SmsSendResult.Failed("请求失败，检查一下网络")
        if (j.int("status") == 1) return SmsSendResult.Sent
        val msg = j.bizMessage().ifBlank { "验证码发送失败" }
        val code = j.int("error_code")
        return SmsSendResult.Failed(if (code > 0) "$msg（$code）" else msg)
    }

    /**
     * 手机号 + 验证码登录。
     *
     * 一个手机号可能绑定多个酷狗账号。这种情况上游**不会**给 token，而是把候选
     * 账号放进 `data.info_list` / `user_list`，客户端必须让用户选一个、再带
     * `userid` 重发一次。以前这里只认 token，于是「多账号」被当成「验证码错误」，
     * 这是手机号登录失败最常见的原因。
     */
    suspend fun loginByPhone(mobile: String, code: String, userId: String? = null): PhoneLoginResult {
        val params = buildMap {
            put("mobile", mobile)
            put("code", code)
            if (!userId.isNullOrBlank()) put("userid", userId)
        }
        val j = Http.getJson(
            base(), "/login/cellphone",
            params = params,
            timeoutMs = 25_000,
        ) ?: return PhoneLoginResult.Failed("请求失败，检查一下网络")

        // 成功时 data 是对象（Rust 侧已把 secu_params 解出来合并进来）；
        // 失败时 data 往往是**字符串**（"验证码错误"），obj() 会返回 null。
        val data = j.obj("data")
        if (data != null) {
            val token = data.str("token").ifBlank { j.str("token") }
            val uid = data.str("userid").ifBlank { j.str("userid") }
            if (token.isNotBlank() && uid.isNotBlank()) {
                return PhoneLoginResult.Success(
                    token = token,
                    userId = uid,
                    vipToken = data.str("vip_token").ifBlank { null },
                    vipType = data.int("vip_type"),
                    t1 = data.str("t1").ifBlank { null },
                )
            }
            val accounts = parseAccounts(data)
            if (accounts.isNotEmpty()) return PhoneLoginResult.NeedAccount(accounts)
        }

        val msg = j.bizMessage().ifBlank { "登录失败，请稍后重试" }
        val code2 = j.int("error_code")
        return PhoneLoginResult.Failed(if (code2 > 0) "$msg（$code2）" else msg)
    }

    /** 从登录响应的 `data` 里抽候选账号（多账号绑定场景）。 */
    private fun parseAccounts(data: JSONObject): List<KugouAccount> {
        for (key in listOf("info_list", "user_list", "userList", "lists", "list")) {
            val arr = data.arr(key) ?: continue
            val out = ArrayList<KugouAccount>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val uid = o.str("userid").ifBlank { o.str("user_id") }
                if (uid.isBlank()) continue
                out += KugouAccount(
                    userId = uid,
                    nickname = o.str("nickname").ifBlank { o.str("username") },
                    avatar = o.str("pic").ifBlank { o.str("avatar") },
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    // ---------------- 概念版「每日畅听」签到 ----------------

    /**
     * 服务端时间（秒）。签到要用**服务端日期**算 `receive_day` —— 手机时区/时间
     * 不准会让酷狗判定成「无效签到」。
     */
    suspend fun serverNow(): Long {
        val j = Http.getJson(base(), "/server/now", timeoutMs = 12_000) ?: return 0L
        return j.obj("data")?.long("timestamp") ?: j.long("timestamp")
    }

    /** 领取当日畅听 VIP。`receiveDay` 必须是 `yyyy-MM-dd`。 */
    suspend fun claimDayVip(receiveDay: String): JSONObject? =
        Http.getJson(
            base(), "/youth/day/vip",
            params = p("receive_day" to receiveDay),
            timeoutMs = 25_000,
        )

    /** 升级为概念版会员（双签到第二步）。 */
    suspend fun upgradeDayVip(): JSONObject? =
        Http.getJson(base(), "/youth/day/vip/upgrade", params = p(), timeoutMs = 25_000)

    /** 本月打卡记录。 */
    suspend fun vipMonthRecord(): JSONObject? =
        Http.getJson(base(), "/youth/month/vip/record", params = p(), timeoutMs = 25_000)

    /** 会员到期信息（`data.busi_vip[]`，含 tvip / svip）。 */
    suspend fun userVipDetail(): JSONObject? =
        Http.getJson(base(), "/user/vip/detail", params = p(), timeoutMs = 25_000)

    /**
     * 当前登录账号的资料（`/user/detail` → `/v3/get_my_info`）。
     *
     * 这是**唯一**能判断「token 到底还活着吗」的接口：`status != 1` 基本就是登录态
     * 失效（`20010` / `20002` / `20018`）。所以它同时承担「取昵称头像」和
     * 「校验登录态」两件事。
     */
    suspend fun userDetail(): JSONObject? =
        Http.getJson(base(), "/user/detail", params = p(), timeoutMs = 25_000)

    /**
     * 刷新登录态。
     *
     * 签到之后账号的 VIP 变了，但本地存着的 token / vip_token 还是签到前那张 ——
     * 不换新的话，**签到成功音质也不会放开**，要等下次登录才生效。
     */
    suspend fun refreshLogin(): JSONObject? =
        Http.getJson(base(), "/login/token", params = p(), timeoutMs = 25_000)

    /** 听歌领取（上报听歌领 VIP）。 */
    suspend fun listenSongClaim(): JSONObject? =
        Http.getJson(base(), "/youth/listen/song", params = p(), timeoutMs = 25_000)

    /**
     * 我的歌单列表（`/user/playlist`）。用来定位账号里的「我喜欢」歌单。
     *
     * 返回原始 JSONObject 而不是模型：这里只需要 `name` / `is_def` / `listid` 三个字段，
     * 为它单独建一个模型不划算。
     */
    suspend fun userPlaylists(pageSize: Int = 50): List<JSONObject>? {
        val j = Http.getJson(
            base(), "/user/playlist",
            params = p("pagesize" to pageSize),
            timeoutMs = 25_000,
        ) ?: return null
        val arr = j.obj("data")?.arr("info") ?: j.arr("info") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
    }

    /**
     * 自己歌单的曲目（`/playlist/track/all/new`），**带 `fileid`**。
     *
     * 不能用 `/playlist/track/all`：那个走 `global_collection_id`，「我喜欢」是私有歌单，
     * gcid 经常为空，拉出来是空列表。另外取消收藏必须用这里返回的 `fileid`
     * （歌单记录 id），传 hash 上游会返回成功但歌还在。
     */
    suspend fun myPlaylistSongs(
        listid: String,
        page: Int = 1,
        pageSize: Int = 200,
    ): List<JSONObject>? {
        if (listid.isBlank()) return emptyList()
        val j = Http.getJson(
            base(), "/playlist/track/all/new",
            params = p("listid" to listid, "page" to page, "pagesize" to pageSize),
            timeoutMs = 25_000,
        ) ?: return null
        // `data` 三种形态都见过：对象里带 info / 对象里带 songs / 直接就是数组。
        val arr = when (val data = j.opt("data")) {
            is JSONArray -> data
            is JSONObject -> data.arr("info") ?: data.arr("songs") ?: data.arr("list")
            else -> j.arr("info")
        } ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(it) }
        }
    }

    /**
     * 往歌单加歌（`/playlist/tracks/add`）。
     *
     * `data` 是 `歌名|hash|album_id|album_audio_id`，多首用逗号拼。四段都要给全：
     * 上游最少只认前两段，但少了后两段在部分曲库下会直接报错。
     */
    suspend fun playlistTracksAdd(listid: String, data: String): JSONObject? =
        Http.getJson(
            base(), "/playlist/tracks/add",
            params = p("listid" to listid, "data" to data),
            timeoutMs = 25_000,
        )

    /** 从歌单删歌（`/playlist/tracks/del`）。`fileids` 是歌单记录 fileid，不是 hash。 */
    suspend fun playlistTracksDel(listid: String, fileids: String): JSONObject? =
        Http.getJson(
            base(), "/playlist/tracks/del",
            params = p("listid" to listid, "fileids" to fileids),
            timeoutMs = 25_000,
        )

    /**
     * 概念版单卡推荐（`/top/card/youth`）。
     *
     * `card_id` 决定是哪一档：3001 私人专属好歌、3004 小众宝藏佳作、3005 潮流尝鲜、
     * 3101 概念er新推、3102 喜欢这首歌的TA也喜欢…… 标题由上游给（`card_info.card_title`），
     * 所以不要在前端写死名字，否则活动换名（「概念er新推09.30-10.29」）就跟不上。
     */
    suspend fun youthCard(cardId: Int, size: Int = 12): Pair<String, List<Song>>? {
        val j = Http.getJson(
            base(), "/top/card/youth",
            params = p("card_id" to cardId, "pagesize" to size),
            timeoutMs = 25_000,
        ) ?: return null
        val data = j.obj("data") ?: return null
        val title = data.obj("card_info")?.str("card_title").orEmpty()
        val arr = data.arr("song_list") ?: return null
        val songs = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { Song.fromPlaylistFile(it) }?.let { add(it) }
            }
        }
        return if (songs.isEmpty()) null else title to songs
    }

    /**
     * 私人 FM（`/personal/fm`）。
     *
     * `song_pool_id` 是档位：0 = 红心（按听歌习惯）、1 = 小众、2 = 探索。
     * 匿名也能拿到，只是内容不含个人偏好。
     */
    suspend fun personalFm(mode: String = "normal", poolId: Int = 0, size: Int = 12): List<Song> {
        val j = Http.getJson(
            base(), "/personal/fm",
            // 不要传 remain_songcnt：实测带上它上游直接返回空 body（不是空列表，是空响应）。
            // 条数在客户端截断即可。
            params = p("mode" to mode, "song_pool_id" to poolId),
            timeoutMs = 25_000,
        ) ?: return emptyList()
        val arr = j.obj("data")?.arr("song_list") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                (Song.fromPlaylistFile(o) ?: Song.fromRecommend(o))?.let { add(it) }
            }
        }.take(size)
    }

    /**
     * 曲风推荐（`/everyday/style/recommend`）。`tagIds` 形如 `S3`（电子）。
     * 返回的 `title` 就是曲风名，正好拿来做「本期开箱：电子」。
     */
    suspend fun styleRecommend(tagIds: String, size: Int = 12): Pair<String, List<Song>>? {
        val j = Http.getJson(
            base(), "/everyday/style/recommend",
            params = p("tagids" to tagIds),
            timeoutMs = 25_000,
        ) ?: return null
        val data = j.obj("data") ?: return null
        val title = data.str("title")
        val arr = data.arr("song_list") ?: return null
        val songs = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                (Song.fromPlaylistFile(o) ?: Song.fromRecommend(o))?.let { add(it) }
            }
        }.take(size)
        return if (songs.isEmpty()) null else title to songs
    }

    /** 广告播放上报领 VIP（调用方自己控节奏）。 */
    suspend fun adClaimVip(): JSONObject? =
        Http.getJson(base(), "/youth/vip", params = p(), timeoutMs = 25_000)

    /**
     * 歌单广场（`/top/playlist`）。
     *
     * 返回结构是 `{ data: { special_list: [...], has_next: 0/1 } }`；
     * 部分版本会退化成 `data.plist` / `data.list`，所以三个键都试一遍。
     */
    suspend fun playlistSquare(page: Int = 1, pageSize: Int = 20): PlaylistPage? {
        val j = Http.getJson(
            base(), "/top/playlist",
            params = p("page" to page, "pagesize" to pageSize),
            timeoutMs = 25_000,
        ) ?: return null
        val data = j.obj("data") ?: j
        val arr = data.arr("special_list") ?: data.arr("plist") ?: data.arr("list")
            ?: return PlaylistPage(emptyList(), false)
        val list = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { PlaylistBrief.from(it) }?.let { add(it) }
            }
        }
        val hasNext = data.int("has_next") == 1 || data.int("hasnext") == 1
        return PlaylistPage(list, hasNext)
    }

    /**
     * 歌单曲目（`/playlist/track/all`）。
     *
     * `key` 必须是 `global_collection_id`。返回的 `data.info` 有两种形态：
     * 带 `audio_info` 嵌套的（和榜单一致），以及平铺 `hash` 的（老接口）。
     * [Song.fromPlaylistFile] 两种都认。
     */
    suspend fun playlistSongs(key: String, page: Int = 1, pageSize: Int = 60): List<Song> {
        if (key.isBlank()) return emptyList()
        val j = Http.getJson(
            base(), "/playlist/track/all",
            params = p("global_collection_id" to key, "page" to page, "pagesize" to pageSize),
            timeoutMs = 25_000,
        ) ?: return emptyList()
        val data = j.obj("data") ?: return emptyList()
        val arr = data.arr("info") ?: data.arr("songs") ?: data.arr("list") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { Song.fromPlaylistFile(it) }?.let { add(it) }
            }
        }
    }

    /**
     * 听歌识曲（`/audio/match`）。
     *
     * body 是**裸的 16bit 小端 PCM（8kHz 单声道）**，不是 WAV —— 传 WAV 会被上游判无效。
     * 参数 `userid` 由 Rust 侧从 cookie 里取，所以这里只要带 cookie 就够了。
     */
    suspend fun matchAudio(pcm: ByteArray): JSONObject? {
        if (pcm.isEmpty()) return null
        val j = Http.postBytes(
            base(), "/audio/match",
            params = p(),
            body = pcm,
            contentType = "application/octet-stream",
            timeoutMs = 25_000,
        ) ?: return null
        if (j.int("status") != 1) return null
        return j
    }
}

/** 发码结果。失败时带上游真实原因（手机格式不正确 / 发送过于频繁 …）。 */
sealed interface SmsSendResult {
    data object Sent : SmsSendResult

    data class Failed(val message: String) : SmsSendResult
}

/** 手机号登录结果。多账号时先让用户选账号，再带 userid 重登。 */
sealed interface PhoneLoginResult {
    data class Success(
        val token: String,
        val userId: String,
        val vipToken: String?,
        val vipType: Int,
        val t1: String? = null,
    ) : PhoneLoginResult

    data class NeedAccount(val accounts: List<KugouAccount>) : PhoneLoginResult

    data class Failed(val message: String) : PhoneLoginResult
}

/** 一个手机号下绑定的候选酷狗账号。 */
data class KugouAccount(
    val userId: String,
    val nickname: String,
    val avatar: String,
)
