package com.dailymusic.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.KugouAccount
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.LoginState
import com.dailymusic.app.data.PhoneLoginResult
import com.dailymusic.app.data.Profile
import com.dailymusic.app.data.SmsSendResult
import com.dailymusic.app.data.YouthVip
import com.dailymusic.app.playback.PlayUrlStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class LoginMode(val label: String) {
    Qr("扫码"),
    Phone("手机号"),
    Cookie("Cookie"),
}

/**
 * 登录页。三种方式并列，因为它们的可用性取决于用户手上有什么：
 * 手机在身边用扫码最省事；只有账号密码/手机号就用短信；已经抓过包的直接贴 cookie。
 *
 * 登录成功后的必做动作是 [PlayUrlStore.invalidate] —— 匿名时解析出来的地址
 * 是按 128k 换的，不清掉会继续放旧音质。
 */
@Composable
fun LoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var mode by rememberSaveable { mutableStateOf(LoginMode.Qr) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
            }
            Spacer(Modifier.size(6.dp))
            Text("登录酷狗账号", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LoginMode.entries.forEach { m ->
                val selected = mode == m
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            if (selected) colors.surfaceContainer else Color.Transparent,
                            RoundedCornerShape(13.dp),
                        )
                        .clickable { mode = m },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = m.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.onSurface else colors.onSurfaceSecondary,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            when (mode) {
                LoginMode.Qr -> QrLogin(onLoggedIn)
                LoginMode.Phone -> PhoneLogin(onLoggedIn)
                LoginMode.Cookie -> CookieLogin(onLoggedIn)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------- 扫码 ----------------

@Composable
private fun QrLogin(onLoggedIn: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var key by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var hint by remember { mutableStateOf("正在获取二维码…") }
    var attempt by remember { mutableIntStateOf(0) }

    // 生成二维码。attempt 变化时重新生成（过期后点「刷新」）。
    LaunchedEffect(attempt) {
        hint = "正在获取二维码…"
        bitmap = null
        val k = KugouApi.qrKey()
        if (k == null) {
            hint = "拿不到二维码，检查一下网络"
            return@LaunchedEffect
        }
        key = k
        val dataUrl = KugouApi.qrImage(k)
        if (dataUrl == null) {
            hint = "二维码图片加载失败"
            return@LaunchedEffect
        }
        val raw = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) {
            hint = "二维码解析失败"
            return@LaunchedEffect
        }
        bitmap = bmp.asImageBitmap()
        hint = "打开酷狗 App 扫一扫"
    }

    // 轮询。key 变了就换一轮；Expired 时停在这里等用户点刷新。
    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            when (val state = KugouApi.qrCheck(k)) {
                LoginState.Waiting -> hint = "打开酷狗 App 扫一扫"
                LoginState.Scanned -> hint = "已扫码，请在手机上确认登录"
                LoginState.Expired -> {
                    hint = "二维码已过期，点下面的按钮刷新"
                    return@LaunchedEffect
                }

                is LoginState.Failed -> {
                    hint = state.message
                    return@LaunchedEffect
                }

                is LoginState.Success -> {
                    Auth.save(state.token, state.userId, state.vipToken, state.vipType, state.t1)
                    Profile.clear()
                    PlayUrlStore.invalidate()
                    // 概念版不签到只有 60 秒试听，登录后顺手把今天的畅听领了。
                    YouthVip.autoSignInIfNeeded()
                    onLoggedIn()
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = "登录二维码", modifier = Modifier.fillMaxSize())
            } else {
                Text("···", fontSize = 30.sp, color = Color(0xFF999999))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(hint, fontSize = 13.sp, color = colors.onSurfaceSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        PillButton(text = "刷新二维码", filled = false) { attempt++ }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "登录后可以拿到更高音质；部分原本只下发加密格式（.mgg）的曲目也会改为下发普通 mp3。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )
    }
}

// ---------------- 手机号 ----------------

@Composable
private fun PhoneLogin(onLoggedIn: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var mobile by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintError by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    /** 一个手机号绑定了多个账号时，上游不给 token，只给候选列表。 */
    var accounts by remember { mutableStateOf<List<KugouAccount>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun finish(token: String, userId: String, vipToken: String?, vipType: Int, t1: String? = null) {
        Auth.save(token, userId, vipToken, vipType, t1)
        // 换账号时先把上一个账号的昵称/头像清掉，否则「我的」会短暂显示别人的名字。
        Profile.clear()
        PlayUrlStore.invalidate()
        // 登录后立刻补一次签到 —— 概念版不签到只有 60 秒试听，
        // 让用户「登录完还要自己去点一下」是没有道理的。
        scope.launch { YouthVip.autoSignInIfNeeded() }
        onLoggedIn()
    }

    fun submit(userId: String? = null) {
        scope.launch {
            submitting = true
            hint = null
            val r = KugouApi.loginByPhone(mobile, code, userId)
            submitting = false
            when (r) {
                is PhoneLoginResult.Success -> finish(r.token, r.userId, r.vipToken, r.vipType, r.t1)

                is PhoneLoginResult.NeedAccount -> {
                    accounts = r.accounts
                    hintError = false
                    hint = "这个手机号绑定了 ${r.accounts.size} 个酷狗账号，选一个继续"
                }

                is PhoneLoginResult.Failed -> {
                    accounts = emptyList()
                    hintError = true
                    hint = r.message
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        FieldLabel("手机号")
        InputBox(
            value = mobile,
            placeholder = "11 位手机号",
            keyboardType = KeyboardType.Phone,
        ) {
            mobile = it.filter(Char::isDigit).take(11)
            accounts = emptyList()
        }

        Spacer(Modifier.height(14.dp))
        FieldLabel("验证码")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                InputBox(
                    value = code,
                    placeholder = "6 位短信验证码",
                    keyboardType = KeyboardType.Number,
                ) { code = it.filter(Char::isDigit).take(6) }
            }
            Spacer(Modifier.width(10.dp))
            PillButton(
                text = if (sending) "发送中" else "发送验证码",
                filled = false,
                enabled = !sending && mobile.length >= 11,
            ) {
                scope.launch {
                    sending = true
                    hint = null
                    when (val r = KugouApi.sendSmsCode(mobile)) {
                        is SmsSendResult.Sent -> {
                            hintError = false
                            hint = "验证码已发送，注意查收短信"
                        }

                        is SmsSendResult.Failed -> {
                            hintError = true
                            hint = r.message
                        }
                    }
                    sending = false
                }
            }
        }

        // 多账号选择：上游这一步还没给 token，必须先选人。
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh, RoundedCornerShape(18.dp))
                    .padding(8.dp),
            ) {
                accounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !submitting) {
                                hint = null
                                submit(acc.userId)
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (acc.avatar.isNotBlank()) {
                            RemoteImage(
                                url = acc.avatar,
                                modifier = Modifier.size(34.dp),
                                shape = CircleShape,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = acc.nickname.ifBlank { "酷狗账号 ${acc.userId}" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                text = "ID ${acc.userId}",
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                        Text("用这个", fontSize = 13.sp, color = colors.primary)
                    }
                }
            }
        }

        hint?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                color = if (hintError) colors.error else colors.onSurfaceSecondary,
            )
        }

        Spacer(Modifier.height(22.dp))
        PillButton(
            text = if (submitting) "登录中…" else "登录",
            filled = true,
            enabled = !submitting && mobile.length >= 11 && code.length >= 4,
            modifier = Modifier.fillMaxWidth(),
        ) {
            accounts = emptyList()
            submit(null)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "短信验证码由酷狗下发。若长时间收不到，可能是该账号未开通短信登录，建议改用扫码。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantSummary,
            lineHeight = 17.sp,
        )
    }
}

// ---------------- Cookie 导入 ----------------

@Composable
private fun CookieLogin(onLoggedIn: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var text by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        FieldLabel("粘贴 cookie")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    hint = null
                    ok = false
                },
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "token=xxxx; userid=123456",
                                fontSize = 13.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        hint?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = if (ok) colors.primary else colors.error)
        }

        Spacer(Modifier.height(18.dp))
        PillButton(
            text = "导入并登录",
            filled = true,
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val parsed = Auth.parseCookieText(text)
            if (parsed == null) {
                hint = "没能从这段文本里识别出 token 和 userid"
                ok = false
            } else {
                Auth.save(parsed.token, parsed.userId, null, 0, parsed.t1)
                Profile.clear()
                PlayUrlStore.invalidate()
                scope.launch { YouthVip.autoSignInIfNeeded() }
                ok = true
                hint = "已导入，登录态即刻生效"
                onLoggedIn()
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "cookie 可以在酷狗网页版登录后，用浏览器开发者工具从任意请求的 Cookie 头里复制。\n只需要 token 和 userid 两项。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantSummary,
            lineHeight = 17.sp,
        )
    }
}

// ---------------- 小部件 ----------------

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = 6.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun InputBox(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, fontSize = 15.sp, color = colors.onSurfaceVariantSummary)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .height(44.dp)
            .background(
                when {
                    !enabled -> colors.surfaceContainerHigh
                    filled -> colors.primary
                    else -> colors.surfaceContainerHigh
                },
                RoundedCornerShape(22.dp),
            )
            .clip(RoundedCornerShape(22.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> colors.onSurfaceVariantSummary
                filled -> colors.onPrimary
                else -> colors.onSurface
            },
        )
    }
}
