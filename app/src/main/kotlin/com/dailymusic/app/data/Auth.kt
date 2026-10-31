package com.dailymusic.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 酷狗账号状态。
 *
 * 关键约束：**本机 Rust 服务不保存 cookie**。它每个请求都从 query 里读
 * `cookie` 参数（`q_cookie()` 就是 `q.get("cookie")`），登录接口把 token 回吐给
 * 客户端之后就不管了。所以持久化和「每次请求带上 cookie」都是客户端的事，
 * 漏了这一步的表现是「扫码成功但拿到的还是匿名音质」。
 */
object Auth {

    private const val PREFS_NAME = "daily_music_auth"
    private const val K_TOKEN = "token"
    private const val K_USERID = "userid"
    private const val K_VIP_TOKEN = "vip_token"
    private const val K_VIP_TYPE = "vip_type"
    private const val K_T1 = "t1"
    private const val K_NICKNAME = "nickname"
    private const val K_AVATAR = "avatar"

    private var prefs: SharedPreferences? = null

    var isLoggedIn by mutableStateOf(false)
        private set

    var nickname by mutableStateOf("")
        private set

    var avatar by mutableStateOf("")
        private set

    var vipType by mutableStateOf(0)
        private set

    private var token: String? = null
    private var userId: String? = null
    private var vipToken: String? = null

    /**
     * 登录凭证里的 `t1`。
     *
     * 手机号 / 微信登录的上游响应会带 `t1`，它是**续期**用的设备凭证：`/login/token`
     * 刷新时会把它拼进新 t1 的种子（`lite_t1("{t1}|{now}")`）。不存的话每次续期都
     * 用空前缀重新算，上游可能不认 —— 表现就是「token 到期后就再也续不上」。
     * 扫码登录不返回 t1，那种情况保持空串即可。
     */
    private var t1: String? = null

    val isVip: Boolean get() = vipType > 0

    /** 当前凭证。签到后换新 token 时要用，不暴露给 UI。 */
    fun currentToken(): String? = token

    fun currentUserId(): String? = userId

    /**
     * 有没有续期种子（`t1`）。
     *
     * 没有的话 `/login/token` 必然被上游拒（它拿 t1 当前缀算新凭证），
     * 这时候发请求纯属浪费一次往返 —— 直接告诉用户「请重新登录」更快。
     */
    fun hasRenewSeed(): Boolean = !t1.isNullOrBlank() && t1 != "undefined"

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        token = p.getString(K_TOKEN, null)
        userId = p.getString(K_USERID, null)
        vipToken = p.getString(K_VIP_TOKEN, null)
        t1 = p.getString(K_T1, null)
        vipType = p.getInt(K_VIP_TYPE, 0)
        nickname = p.getString(K_NICKNAME, "").orEmpty()
        avatar = p.getString(K_AVATAR, "").orEmpty()
        isLoggedIn = !token.isNullOrBlank() && !userId.isNullOrBlank()
    }

    /**
     * 拼成 Rust 服务要的 `cookie` 参数。
     *
     * **格式必须是 `k=v; k=v`，不是 JSON。** 上游的 `cookieToJson()` 只做
     * `split(';')` 再 `split('=')`（`server.rs:818` 是它的逐行移植），喂 JSON 进去
     * 会得到一个「键名是整个 JSON 串、值为 undefined」的垃圾对象 —— `token` 这个
     * 键根本不存在。
     *
     * 以前这里返回 `{"token":"…","userid":"…"}`，后果是**所有需要登录的接口都没
     * 带上 token**：`/user/detail` 和 `/login/token` 一律回 20018、签到回 20002，
     * 但界面上又显示「已登录」—— 表现就是「拿不到账号资料」「登录了也只能试听」。
     *
     * Dart 端是 `cookieParts.join(';')`（`kugou_api_client.dart:842`），这里对齐。
     */
    fun cookieParam(): String? {
        if (!isLoggedIn) return null
        val parts = ArrayList<String>(5)
        token?.takeIf { it.isNotBlank() }?.let { parts += "token=$it" }
        userId?.takeIf { it.isNotBlank() }?.let { parts += "userid=$it" }
        vipToken?.takeIf { it.isNotBlank() }?.let { parts += "vip_token=$it" }
        // 上游扫码登录不返回 t1，会写成字面量 "undefined"；这种东西发回去只会添乱。
        t1?.takeIf { it.isNotBlank() && it != "undefined" }?.let { parts += "t1=$it" }
        if (vipType > 0) parts += "vip_type=$vipType"
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    fun save(token: String?, userId: String?, vipToken: String?, vipType: Int, t1: String? = null) {
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return
        this.token = token
        this.userId = userId
        this.vipToken = vipToken
        this.vipType = vipType
        if (!t1.isNullOrBlank() && t1 != "undefined") this.t1 = t1
        isLoggedIn = true
        prefs?.edit()
            ?.putString(K_TOKEN, token)
            ?.putString(K_USERID, userId)
            ?.putString(K_VIP_TOKEN, vipToken)
            ?.putInt(K_VIP_TYPE, vipType)
            ?.putString(K_T1, this.t1)
            ?.apply()
    }

    fun saveProfile(nickname: String, avatar: String) {
        if (nickname.isNotBlank()) this.nickname = nickname
        if (avatar.isNotBlank()) this.avatar = avatar
        prefs?.edit()
            ?.putString(K_NICKNAME, this.nickname)
            ?.putString(K_AVATAR, this.avatar)
            ?.apply()
    }

    fun clear() {
        token = null
        userId = null
        vipToken = null
        t1 = null
        vipType = 0
        isLoggedIn = false
        nickname = ""
        avatar = ""
        prefs?.edit()?.clear()?.apply()
    }

    /**
     * 从用户粘贴的 cookie 串里抠出 token / userid / t1。
     *
     * 兼容三种常见写法，因为用户会从浏览器插件、抓包工具里各拷一份过来：
     * `token=xxx; userid=yyy`、`{"token":"xxx","userid":"yyy"}`、以及换行分隔。
     * `t1` 有就一起带上（续期要用），没有也不影响登录。
     */
    fun parseCookieText(raw: String): CookieCreds? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("{")) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val t = o.str("token").ifBlank { o.str("Token") }
            val u = o.str("userid").ifBlank { o.str("user_id") }.ifBlank { o.str("userId") }
            if (t.isBlank() || u.isBlank()) return null
            return CookieCreds(t, u, o.str("t1").ifBlank { null })
        }

        var token: String? = null
        var userid: String? = null
        var t1: String? = null
        text.split(';', '\n', ',').forEach { part ->
            val kv = part.trim()
            val idx = kv.indexOf('=')
            if (idx <= 0) return@forEach
            val key = kv.substring(0, idx).trim().lowercase()
            val value = kv.substring(idx + 1).trim().trim('"', '\'')
            when (key) {
                "token" -> token = value
                "userid", "user_id", "userid_" -> userid = value
                "t1" -> t1 = value
            }
        }
        val t = token
        val u = userid
        return if (t != null && u != null && t.isNotBlank() && u.isNotBlank()) {
            CookieCreds(t, u, t1)
        } else {
            null
        }
    }
}

/** 从粘贴文本里解析出来的登录凭证。 */
data class CookieCreds(
    val token: String,
    val userId: String,
    val t1: String?,
)
