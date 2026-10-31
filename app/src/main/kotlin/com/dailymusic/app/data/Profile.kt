package com.dailymusic.app.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 当前登录账号的资料。
 *
 * 之前「我的」页只显示「已登录」四个字，用户看不到自己登的是谁 —— 换过账号、
 * 或者登录态其实已经失效，界面都长得一模一样。这里补两件事：
 *
 * 1. 拉 `/user/detail` 拿昵称 / 头像 / ID；
 * 2. **顺带校验登录态**。`status != 1` 说明 token 死了（`20010/20002/20018`），
 *    此时必须让用户看见「登录态失效」并给一个重新登录的入口，而不是继续显示
 *    「已登录」然后在签到/播歌时莫名其妙失败。
 */
object Profile {

    private val mutex = Mutex()

    var nickname by mutableStateOf("")
        private set

    var avatar by mutableStateOf("")
        private set

    var userId by mutableStateOf("")
        private set

    /** 已经成功拉到过一次资料。 */
    var loaded by mutableStateOf(false)
        private set

    /** 登录态校验失败的原因；空串表示正常。 */
    var error by mutableStateOf("")
        private set

    var refreshing by mutableStateOf(false)
        private set

    val valid: Boolean get() = error.isBlank()

    /** 展示用：昵称优先，其次「酷狗账号 <id>」。 */
    val displayName: String
        get() = nickname.ifBlank {
            val id = userId.ifBlank { Auth.currentUserId().orEmpty() }
            if (id.isBlank()) "已登录" else "酷狗账号 $id"
        }

    fun clear() {
        nickname = ""
        avatar = ""
        userId = ""
        loaded = false
        error = ""
    }

    suspend fun refresh() = mutex.withLock {
        if (!Auth.isLoggedIn) {
            clear()
            return@withLock
        }
        // 先用上次存下来的昵称头像垫上，避免「我的」页闪一下「酷狗账号 xxx」。
        if (nickname.isBlank() && Auth.nickname.isNotBlank()) nickname = Auth.nickname
        if (avatar.isBlank() && Auth.avatar.isNotBlank()) avatar = normalizeAvatar(Auth.avatar)
        refreshing = true
        try {
            // 先续一次期再拉资料。
            //
            // 酷狗的概念版 token 会过期，而**过期是静默的** —— 界面照样显示
            // 「已登录」，只有真正调接口时才回 20018。以前这里是直接调
            // `/user/detail`，token 一死就永久卡在「登录态已失效」；现在只要
            // 存过 t1（登录时写进去），进「我的」就能自动换一张新 token 自愈。
            //
            // 没存 t1 的老凭证不用试 —— `/login/token` 缺种子必然被拒。
            if (Auth.hasRenewSeed() && !YouthVip.renewLogin()) {
                error = "登录态已失效，请重新登录"
                loaded = false
                userId = Auth.currentUserId().orEmpty()
                return@withLock
            }
            val j = KugouApi.userDetail()
            if (j == null) {
                // 网络问题不能判成「登录失效」—— 那会让用户白白重登一次。
                if (!loaded) error = "资料加载失败（网络），下拉重试"
                return@withLock
            }
            val status = j.int("status")
            val code = j.int("error_code")
            // token 只打前 6 位：排查够用，又不至于把凭证写进日志。
            val tokenHint = Auth.currentToken()?.take(6).orEmpty()
            Log.i(
                "DailyMusic",
                "user/detail status=$status code=$code userid=${Auth.currentUserId()} token=$tokenHint…",
            )
            if (status != 1) {
                error = loginErrorText(code)
                loaded = false
                userId = Auth.currentUserId().orEmpty()
                Log.w("DailyMusic", "user/detail 业务失败：${j.bizMessage()}（$code）")
                return@withLock
            }
            val d = j.obj("data") ?: j
            nickname = d.str("nickname").ifBlank { d.str("username") }.ifBlank { d.str("name") }
            avatar = normalizeAvatar(
                d.str("avatar").ifBlank { d.str("img") }.ifBlank { d.str("pic") },
            )
            userId = d.str("userid")
                .ifBlank { d.str("userId") }
                .ifBlank { Auth.currentUserId().orEmpty() }
            error = ""
            loaded = true
            // 存一份到 prefs：下次冷启动能立刻显示昵称头像，不用等网络。
            Auth.saveProfile(nickname, avatar)
        } finally {
            refreshing = false
        }
    }

    /** 头像 URL 里可能带 `{size}` 占位符，也可能缺协议头。 */
    private fun normalizeAvatar(raw: String): String {
        if (raw.isBlank()) return ""
        var url = raw.replace("{size}", "240")
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) url = "https://$url"
        return url
    }

    private fun loginErrorText(code: Int): String = when (code) {
        20002, 20010, 20018 -> "登录态已失效，请重新登录"
        else -> "登录态异常（$code），请重新登录"
    }
}
