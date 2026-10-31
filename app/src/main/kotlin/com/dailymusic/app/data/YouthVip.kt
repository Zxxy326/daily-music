package com.dailymusic.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 概念版「每日畅听」签到。
 *
 * ## 为什么必须做
 *
 * 我们这套接口用的是**酷狗概念版**身份（appid 3116 / clientver 11440）。概念版的
 * 权益模型不是「买会员」，而是「每天签到领一天畅听」：不签到就只剩 60 秒试听。
 * 换句话说 —— **签到没做，登录了也还是只能试听**。这是「官方酷狗只能试听」的
 * 真正原因，跟 appid 无关。
 *
 * ## 双签到
 *
 * 酷狗概念版每天要做两步，缺一不可：
 *
 * 1. `POST /youth/day/vip`          → 领「畅听 VIP」（tvip）一天
 * 2. `POST /youth/day/vip/upgrade`  → 把它升级成「概念版会员」（svip）
 *
 * 只做第一步的话，官方那边显示的是「畅听VIP+1天」，音质确实会放开，但概念版
 * 专属的权益（例如部分无损档位）拿不到。上游 MD3Music 早期就是静默吞掉第二步
 * 的失败，导致自己显示「概念版会员」而官方只认畅听 —— 这里不重蹈覆辙：
 * **两步分别判成功，失败如实报。**
 *
 * ## receive_day 用服务端时间
 *
 * `receive_day` 是 `yyyy-MM-dd`。用手机本地时间算，时区偏一点就会变成「昨天」
 * 或「明天」，上游直接判无效签到。所以先打 `/server/now` 拿服务端时间戳。
 *
 * ## 20028
 *
 * 上游风控会返回 `error_code = 20028`（需要二次安全验证，通常是腾讯滑块）。
 * 滑块要在 WebView 里跑 TCaptcha 组件，当前版本没做 —— 如实提示，不假装成功。
 */
object YouthVip {

    private const val PREFS = "daily_music_youth_vip"
    private const val K_AUTO_DAY = "auto_sign_day"
    private const val K_SIGNED = "signed_days"

    /** 今天已领取过畅听 VIP（上游认为重复领取，不算失败）。 */
    private const val CODE_ALREADY_CLAIMED = 131001

    /** 已升级过概念版（当天第一次重复升级）。 */
    private const val CODE_ALREADY_UPGRADED = 20030

    /**
     * 同一天重复升级概念版时，上游换了个码：`297002` + `已经领取过升级vip奖励`。
     * 真机实测（2026-10-27）：`claim=131001`（今日畅听已领）+ `upgrade=297002`。
     * 这两个都是「今天做过了」，不是失败 —— 早期只认 20030，于是每次手动补签
     * 都会红字报「升级概念版失败」，其实权益早就到账了。
     */
    private const val CODE_ALREADY_UPGRADED_TODAY = 297002

    /** 需要二次安全验证（滑块）。 */
    private const val CODE_NEED_VERIFY = 20028

    private var prefs: SharedPreferences? = null
    private val mutex = Mutex()

    /**
     * 续期单独一把锁。
     *
     * 签到流程已经持有 [mutex]，而签到内部会调 [renewLogin] —— 共用一把锁会
     * 直接死锁；但完全不锁又不行：「我的」页刷新和签到可能同时续期，两个响应
     * 回来的顺序不确定，后到的（其实是更旧的）会把新 token 覆盖掉。
     */
    private val renewMutex = Mutex()

    /** 正在跑签到流程（UI 用来禁用按钮 / 显示进度）。 */
    var running by mutableStateOf(false)
        private set

    /** 最近一次操作的结果，UI 直接展示。 */
    var lastOutcome by mutableStateOf<SignOutcome?>(null)
        private set

    /** 本月已签日期，元素形如 `2026-10-27`。 */
    var signedDays by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 畅听会员到期时间（原始串，如 `2026-11-03 23:59:59`）。 */
    var tvipEnd by mutableStateOf("")
        private set

    /** 概念会员到期时间。 */
    var svipEnd by mutableStateOf("")
        private set

    /** 今天是否已签（本地判定，服务端记录可能滞后）。 */
    val signedToday: Boolean
        get() = signedDays.contains(todayKey())

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // 本地记录的已签日期和服务端记录取并集，避免「刚签完服务端还没刷新」时日历不打勾。
        val stored = p.getStringSet(K_SIGNED, emptySet()).orEmpty().toSet()
        if (stored.isNotEmpty()) signedDays = signedDays + stored
    }

    fun clear() {
        signedDays = emptySet()
        tvipEnd = ""
        svipEnd = ""
        lastOutcome = null
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * 登录后 / 冷启动时自动签到。
     *
     * 同一天只自动跑一次（成功或「已领取」才记账；网络失败不记账，下次启动重试 ——
     * 那种情况本来就该重试）。
     */
    suspend fun autoSignInIfNeeded() {
        if (!Auth.isLoggedIn) return
        val day = todayOnServer()
        if (prefs?.getString(K_AUTO_DAY, null) == day) {
            // 今天已经签过：不再签到，但顺手续一次 token —— 会话「放着不用」会自然
            // 过期，用户隔天打开就是「登录已失效」，那体验很莫名其妙。
            renewLogin()
            return
        }
        val out = signIn()
        if (out.success || out.alreadyDone) {
            prefs?.edit()?.putString(K_AUTO_DAY, day)?.apply()
        }
    }

    /**
     * 手动双签到。返回的结果**如实反映两步各自的状态**：
     * 第二步失败时 success=false，但 message 会说明「畅听已领取」。
     */
    suspend fun signIn(): SignOutcome = mutex.withLock {
        if (!Auth.isLoggedIn) return@withLock SignOutcome(false, "请先登录酷狗账号")
        running = true
        val out = try {
            doSignIn()
        } finally {
            running = false
        }
        // 所有分支都必须落到 lastOutcome —— 早退的那几条以前漏了，
        // 表现是「点了签到，界面一点反应都没有」（真机踩过）。
        lastOutcome = out
        out
    }

    private suspend fun doSignIn(): SignOutcome {
        // 先验一次登录态：token 死了就直接说清楚，别让用户看到签到接口的 20002。
        if (!ensureLoginValid()) {
            return SignOutcome(false, "登录态已失效，请重新登录", code = 20018)
        }

        val day = todayOnServer()

        // ① 领畅听 VIP
        val claim = KugouApi.claimDayVip(day)
            ?: return SignOutcome(false, "签到请求没有响应，检查一下网络")
        val claimCode = claim.int("error_code")
        val claimOk = claim.int("status") == 1 || claimCode == 0
        val claimDone = claimCode == CODE_ALREADY_CLAIMED
        if (!claimOk && !claimDone) {
            val msg = claim.bizMessage().ifBlank { mapError(claimCode) }
            return SignOutcome(false, msg, code = claimCode)
        }

        // ② 升级概念版
        val up = KugouApi.upgradeDayVip()
        val upCode = up?.int("error_code") ?: -1
        val upOk = up != null && (
            up.int("status") == 1 ||
                upCode == 0 ||
                upCode == CODE_ALREADY_UPGRADED ||
                upCode == CODE_ALREADY_UPGRADED_TODAY ||
                upCode == CODE_ALREADY_CLAIMED
            )
        markSigned(day)
        // 双签到的两步响应都只在失败时才有文本，而「已领过」用的是上游特有的
        // error_code（不是 curl 文档里那几个）。把原始码打出来，下次遇到新码
        // 不用重新抓包就能补进 mapError / alreadyDone 判定。
        Log.i(
            "DailyMusic",
            "youth/day/vip claim=${claim.int("status")}/$claimCode " +
                "upgrade=${up?.int("status") ?: -1}/$upCode msg=${up?.bizMessage().orEmpty()}",
        )
        if (!upOk) {
            // 畅听确实到账了，只是没升级成概念版。不能吞掉，也不能报「全成功」。
            val tail = up?.bizMessage().orEmpty().ifBlank { mapError(upCode) }
            return SignOutcome(false, "畅听已领取，但升级概念版失败：$tail", code = upCode)
        }

        refresh()
        renewLogin()
        return if (
            claimDone ||
            upCode == CODE_ALREADY_UPGRADED ||
            upCode == CODE_ALREADY_UPGRADED_TODAY ||
            upCode == CODE_ALREADY_CLAIMED
        ) {
            SignOutcome(true, "今日已签到（畅听 + 概念版）", alreadyDone = true)
        } else {
            SignOutcome(true, "签到成功，今日畅听已到账")
        }
    }

    /** 听歌领取：上报一次听歌记录，换当日额度。130012 = 今日已领。 */
    suspend fun listenClaim(): SignOutcome = mutex.withLock {
        if (!Auth.isLoggedIn) return@withLock SignOutcome(false, "请先登录酷狗账号")
        running = true
        val out = try {
            doListenClaim()
        } finally {
            running = false
        }
        lastOutcome = out
        out
    }

    private suspend fun doListenClaim(): SignOutcome {
        val j = KugouApi.listenSongClaim() ?: return SignOutcome(false, "请求没有响应")
        val code = j.int("error_code")
        return when {
            j.int("status") == 1 -> SignOutcome(true, "听歌领取成功")
            code == 130012 -> SignOutcome(true, "今日已领取过", alreadyDone = true)
            else -> SignOutcome(false, j.bizMessage().ifBlank { mapError(code) }, code = code)
        }
    }

    /**
     * 广告领取：上游要求「播完广告上报」，最多 8 次、每次之间隔 30 秒。
     * 这里按 8 次循环，30002（次数用光）视为正常结束。
     */
    suspend fun adClaim(onProgress: (Int) -> Unit = {}): SignOutcome = mutex.withLock {
        if (!Auth.isLoggedIn) return@withLock SignOutcome(false, "请先登录酷狗账号")
        running = true
        val out = try {
            doAdClaim(onProgress)
        } finally {
            running = false
        }
        lastOutcome = out
        out
    }

    private suspend fun doAdClaim(onProgress: (Int) -> Unit): SignOutcome {
        var success = 0
        for (i in 1..8) {
            val j = KugouApi.adClaimVip()
                ?: return SignOutcome(false, if (success > 0) "领取 $success/8 次后网络中断" else "请求没有响应")
            val code = j.int("error_code")
            when {
                j.int("status") == 1 -> {
                    success++
                    onProgress(success)
                    if (i != 8) kotlinx.coroutines.delay(30_000)
                }

                code == 30002 -> return SignOutcome(
                    true,
                    if (success > 0) "广告领取 $success/8 次，今日次数已用光" else "今日广告次数已用光",
                    alreadyDone = success == 0,
                )

                else -> return SignOutcome(
                    false,
                    if (success > 0) "领取 $success/8 次后失败：${mapError(code)}" else "领取失败：${mapError(code)}",
                    code = code,
                )
            }
        }
        return SignOutcome(true, "广告领取 $success/8 次")
    }

    /** 拉本月打卡记录 + 会员到期时间。失败静默（不打断签到结果展示）。 */
    suspend fun refresh() {
        if (!Auth.isLoggedIn) return

        runCatching {
            val rec = KugouApi.vipMonthRecord()
            val days = parseRecordDays(rec)
            if (days.isNotEmpty()) {
                signedDays = signedDays + days
                persistSigned()
            }
        }

        runCatching {
            val detail = KugouApi.userVipDetail() ?: return@runCatching
            val data = detail.obj("data") ?: detail
            val list = data.arr("busi_vip") ?: return@runCatching
            var tv = ""
            var sv = ""
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                if (item.int("is_vip") != 1) continue
                val end = item.str("vip_end_time")
                if (end.isBlank()) continue
                when (item.str("product_type")) {
                    "tvip" -> if (laterThan(end, tv)) tv = end
                    "svip" -> if (laterThan(end, sv)) sv = end
                }
            }
            tvipEnd = tv
            svipEnd = sv
        }
    }

    // ---------------- 内部 ----------------

    /**
     * 换一张 token。返回 false 表示**确定**登录态已失效（上游 20002/20010/20018）。
     *
     * 两个用途：
     * 1. 签到前先跑一次 —— 提前发现死 token，让用户看到「请重新登录」而不是签到接口
     *    返回的 20002；
     * 2. 签到后再跑一次 —— 本地存的是签到前那张凭证，`vip_token` 里没有当天额度，
     *    不换的话表现就是「页面显示签到成功，但播歌还是试听」。
     *
     * 网络异常 / 其它错误码一律返回 true（不判死）—— 不能因为一次网络抖动就把用户
     * 踢去重新登录。
     */
    suspend fun renewLogin(): Boolean = renewMutex.withLock {
        val oldToken = Auth.currentToken() ?: return@withLock false
        val oldUserId = Auth.currentUserId() ?: return@withLock false
        // 没有 t1 就别打这个接口。
        //
        // `/login/token` 的 t1 是**续期种子**：服务端把 `"{t1}|{now}"` 加密后发给上游，
        // 种子缺失时会退化成 `"|{now}"`，上游一律回 20018。而 20018 又同时是
        // 「token 过期」的码 —— 不区分就会把「我们没带材料」误判成「用户凭证死了」。
        // 真机踩过：扫码登录（拿不到 t1）之后每次冷启动都弹「登录态已失效」，
        // 但同一个 token 拉 `/user/detail`、打卡记录、歌单全都正常。
        //
        // 需要验登录态时走 [ensureLoginValid]：有种子才续期，没种子改用能真正
        // 判定 token 死活的接口。
        if (!Auth.hasRenewSeed()) return@withLock true
        val j = runCatching { KugouApi.refreshLogin() }.getOrNull() ?: return@withLock true

        val status = j.int("status")
        val code = j.int("error_code")
        Log.i("DailyMusic", "login/token status=$status code=$code")
        if (status != 1) {
            val dead = code == 20002 || code == 20010 || code == 20018
            if (dead) Log.w("DailyMusic", "登录态失效：${j.bizMessage()}（$code）")
            return@withLock !dead
        }

        val data = j.obj("data") ?: return@withLock true
        val token = data.str("token").ifBlank { oldToken }
        val userId = data.str("userid").ifBlank { oldUserId }
        // 上游这几个字段偶尔缺省，缺了不要拿 0/空把已有权益覆盖掉。
        val newVipType = data.int("vip_type").takeIf { it > 0 } ?: Auth.vipType
        val newVipToken = data.str("vip_token").ifBlank { null }
        Auth.save(
            token = token,
            userId = userId,
            vipToken = newVipToken,
            vipType = newVipType,
            t1 = data.str("t1").ifBlank { null },
        )
        true
    }

    /**
     * 登录态自检（签到前用）。
     *
     * 有 `t1` 种子 → 走 [renewLogin]，既能续期又能验活；
     * 没种子（扫码登录就是这种）→ 改问 `/user/detail`：它是**唯一**能精确判定
     * token 死活的接口，`status != 1` 且错误码属于登录类才判死。
     *
     * 网络异常一律不判死 —— 不能因为一次抖动就把用户踢去重新登录。
     */
    private suspend fun ensureLoginValid(): Boolean {
        if (Auth.hasRenewSeed()) return renewLogin()
        val j = runCatching { KugouApi.userDetail() }.getOrNull() ?: return true
        if (j.int("status") == 1) return true
        val code = j.int("error_code")
        return code != 20002 && code != 20010 && code != 20018
    }

    private fun markSigned(day: String) {
        signedDays = signedDays + day
        persistSigned()
    }

    private fun persistSigned() {
        // 只留最近 40 天，避免这个集合无限长。
        val trimmed = signedDays.sorted().takeLast(40).toSet()
        signedDays = trimmed
        prefs?.edit()?.putStringSet(K_SIGNED, trimmed)?.apply()
    }

    /** 服务端当天日期 `yyyy-MM-dd`。拿不到服务端时间就退回本地。 */
    private suspend fun todayOnServer(): String {
        val ts = KugouApi.serverNow().takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        return formatDate(ts * 1000)
    }

    private fun todayKey(): String = formatDate(System.currentTimeMillis())

    private fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(ms))

    /** 从打卡记录里抽出日期集合。tvip / svip 都算「签过」。 */
    private fun parseRecordDays(root: JSONObject?): Set<String> {
        if (root == null) return emptySet()
        val data = root.obj("data")
        val list = data?.arr("list")
            ?: data?.arr("record_list")
            ?: root.arr("list")
            ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val raw = item.str("day")
            if (raw.length >= 10) {
                // 统一成 yyyy-MM-dd（上游可能给 2026-06-07 或 2026/6/7）
                val norm = raw.replace('/', '-').take(10)
                if (norm.length == 10) out += norm
                continue
            }
            val num = item.long("day")
            if (num > 1_000_000_000L) {
                out += formatDate(if (num > 1_000_000_000_000L) num else num * 1000)
            }
        }
        return out
    }

    /** `end` 是否比 `current` 更晚（字符串比较前先归一化）。 */
    private fun laterThan(end: String, current: String): Boolean {
        if (current.isBlank()) return true
        return end > current
    }

    private fun mapError(code: Int): String = when (code) {
        20002 -> "登录已失效，请重新登录"
        20006 -> "签名错误，重新登录后重试"
        20010 -> "参数错误（签到日期格式不对）"
        20018 -> "登录已过期，请重新登录"
        CODE_NEED_VERIFY -> "酷狗要求安全验证（滑块），当前版本未实现 —— 去官方 App 签一次即可"
        CODE_ALREADY_UPGRADED -> "已升级过概念版，无需重复领取"
        CODE_ALREADY_UPGRADED_TODAY -> "今日已升级过概念版，无需重复领取"
        20033 -> "今日已签到，无需重复领取"
        20034 -> "领取失败：今日次数已达上限"
        CODE_ALREADY_CLAIMED -> "今日已签到，无需重复领取"
        30002 -> "今日广告次数已用光"
        130012 -> "今日已领取"
        -1 -> "签到失败，请稍后重试"
        else -> "签到失败，请稍后重试（$code）"
    }
}

/** 一次签到 / 领取的结果。 */
data class SignOutcome(
    val success: Boolean,
    val message: String,
    /** 上游认为「今天已经做过了」—— 不算失败，UI 可以显示成已完成。 */
    val alreadyDone: Boolean = false,
    val code: Int = 0,
)

/**
 * `2026-11-03 23:59:59` → `5 天后到期`。
 * 解析失败返回空串，调用方自己决定显示什么（不要编造「已过期」）。
 */
fun vipExpireText(raw: String): String {
    if (raw.isBlank()) return ""
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val d = runCatching { fmt.parse(raw) }.getOrNull()
        ?: runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw) }.getOrNull()
        ?: return ""
    val diff = d.time - System.currentTimeMillis()
    if (diff <= 0) return "已过期"
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        days > 365 -> "${days / 365} 年后到期"
        days > 30 -> "${days / 30} 个月后到期"
        days > 0 -> "$days 天后到期"
        hours > 0 -> "$hours 小时后到期"
        minutes > 0 -> "$minutes 分钟后到期"
        else -> "即将到期"
    }
}
