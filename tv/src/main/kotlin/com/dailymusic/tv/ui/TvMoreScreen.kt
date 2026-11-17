package com.dailymusic.tv.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.LoginState
import com.dailymusic.app.data.Profile
import com.dailymusic.app.data.YouthVip
import com.dailymusic.app.playback.PlayUrlStore
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV「更多」一级页：账号 + 签到 + 设置入口。
 *
 * 电视没有输入框，所以手机端的手机号登录在这里砍掉，只保留扫码登录
 * （手机上点确认即可，TV 侧只负责展示二维码 + 轮询）。
 */
@Composable
fun TvMoreScreen(
    onOpenLogin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 每次进「更多」刷一次资料（Profile.refresh 内部会做 token 续期）。
    LaunchedEffect(Unit) {
        if (Auth.isLoggedIn) runCatching { Profile.refresh() }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            BasicText("更多", style = TvText.h1, modifier = Modifier.padding(bottom = 4.dp))
        }

        // 账号卡片
        item { AccountCard(onOpenLogin = onOpenLogin) }

        // 每日签到卡片
        item { SignCard(onOpenLogin = onOpenLogin) }

        // 功能入口网格
        item {
            BasicText("功能", style = TvText.h2, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TvPill(
                    icon = AppIcons.Tune,
                    title = "设置",
                    subtitle = "版本 · 偏好 · 关于",
                    onClick = onOpenSettings,
                    modifier = Modifier.width(180.dp).height(150.dp),
                )
                TvPill(
                    icon = AppIcons.Person,
                    title = if (Auth.isLoggedIn) "账号" else "扫码登录",
                    subtitle = if (Auth.isLoggedIn) "查看 / 退出" else "手机扫码登录",
                    onClick = onOpenLogin,
                    modifier = Modifier.width(180.dp).height(150.dp),
                )
            }
        }
    }
}

/** 账号卡：登录态显示头像/昵称/VIP，未登录显示诱导。 */
@Composable
private fun AccountCard(onOpenLogin: () -> Unit) {
    val loggedIn = Auth.isLoggedIn && Profile.valid
    FocusSurface(
        onClick = if (loggedIn) {{ /* 已登录点击暂无操作 */ }} else onOpenLogin,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.fillMaxSize().background(TvColor.card).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loggedIn && Profile.avatar.isNotBlank()) {
                TvImage(Profile.avatar, Modifier.size(96.dp), RoundedCornerShape(48.dp))
            } else {
                Box(
                    Modifier.size(96.dp).background(TvColor.cardHigh, RoundedCornerShape(48.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.Person),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        colorFilter = ColorFilter.tint(TvColor.textDim),
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loggedIn) {
                    BasicText(Profile.displayName, style = TvText.h2)
                    BasicText(
                        text = if (Auth.isVip) "酷狗 VIP · 已登录" else "已登录 · ${Auth.currentUserId().orEmpty()}",
                        style = TvText.body,
                    )
                    if (!Profile.valid) {
                        BasicText(Profile.error.ifBlank { "登录态异常" }, style = TvText.caption.copy(color = Color(0xFFFF8A8A)))
                    }
                } else {
                    BasicText("登录酷狗账号", style = TvText.h2)
                    BasicText("登录后每天签到领畅听，才能听整首", style = TvText.body)
                    BasicText("按 OK 进入扫码登录", style = TvText.caption.copy(color = TvColor.accent))
                }
            }
        }
    }
}

/** 每日签到卡：直接调 YouthVip.signIn()，结果就地显示。 */
@Composable
private fun SignCard(onOpenLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcomeText by remember { mutableStateOf<String?>(null) }

    val signedToday = YouthVip.signedToday
    FocusSurface(
        onClick = {
            if (busy) return@FocusSurface
            if (!Auth.isLoggedIn) { onOpenLogin(); return@FocusSurface }
            busy = true
            outcomeText = null
            scope.launch {
                val r = runCatching { YouthVip.signIn() }
                busy = false
                outcomeText = r.getOrNull()?.message ?: "签到失败，检查一下网络"
            }
        },
        modifier = Modifier.fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.fillMaxSize().background(TvColor.card).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(72.dp).background(TvColor.cardHigh, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.Calendar),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    colorFilter = ColorFilter.tint(if (signedToday) TvColor.accent else TvColor.textDim),
                )
            }
            Spacer(Modifier.width(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("每日畅听签到", style = TvText.h2)
                val sub = when {
                    !Auth.isLoggedIn -> "登录后每天签到领畅听"
                    busy -> "签到中…"
                    outcomeText != null -> outcomeText!!
                    signedToday -> "今日已签到 · 畅听到 ${YouthVip.tvipEnd.ifBlank { "明天" }}"
                    else -> "还没签到 —— 签到领一天畅听"
                }
                BasicText(sub, style = TvText.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                text = if (signedToday) "已签到" else "去签到",
                style = TvText.h3.copy(color = if (signedToday) TvColor.accent else TvColor.text),
            )
        }
    }
}

/**
 * 扫码登录页（二级页）：展示二维码 + 轮询扫码状态。
 *
 * 上游二维码图片是 data URL（`data:image/png;base64,...`），要切掉前缀再解码。
 */
@Composable
fun TvLoginScreen(
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var hint by remember { mutableStateOf("正在获取二维码…") }
    var attempt by remember { mutableIntStateOf(0) }

    // 生成二维码。attempt 变化时重新生成（过期/失败后点「刷新」）。
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
            hint = "二维码图片加载失败，按 OK 刷新"
            return@LaunchedEffect
        }
        val raw = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp == null) {
            hint = "二维码解析失败，按 OK 刷新"
            return@LaunchedEffect
        }
        bitmap = bmp.asImageBitmap()
        hint = "打开酷狗 App 扫一扫，扫码后在手机上确认"
    }

    // 轮询。key 变了就换一轮；Expired / Failed 停在这里等用户刷新。
    LaunchedEffect(key) {
        val k = key ?: return@LaunchedEffect
        while (true) {
            delay(2000)
            when (val state = KugouApi.qrCheck(k)) {
                LoginState.Waiting -> hint = "打开酷狗 App 扫一扫，扫码后在手机上确认"
                LoginState.Scanned -> hint = "已扫码，请在手机上点「确认登录」"
                LoginState.Expired -> {
                    hint = "二维码已过期，按 OK 刷新"
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
                    runCatching { YouthVip.autoSignInIfNeeded() }
                    hint = "登录成功"
                    onLoggedIn()
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BasicText("扫码登录", style = TvText.h1)
        BasicText("用手机酷狗 App 扫一扫，按 OK 可刷新二维码", style = TvText.body)

        // 二维码卡片，按 OK 刷新
        FocusSurface(
            onClick = { attempt++ },
            modifier = Modifier.size(360.dp),
            shape = RoundedCornerShape(22.dp),
        ) {
            Box(
                Modifier.fillMaxSize().background(TvColor.card),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.size(320.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    BasicText("二维码加载中…", style = TvText.body)
                }
            }
        }

        BasicText(
            text = hint,
            style = TvText.h3.copy(color = TvColor.accent, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            text = "提示：二维码需在手机上确认登录，登录成功后自动返回",
            style = TvText.caption.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 设置页（二级页）：版本、检查更新、播放偏好快捷入口、关于、退出登录。 */
@Composable
fun TvSettingsScreen(
    onOpenQuality: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    // 检查更新时把结果回显；有新版本由 updater 自身处理（TV 端暂用文字提示，不弹安装框）
    LaunchedEffect(Unit) {
        if (checkResult != null) return@LaunchedEffect
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { BasicText("设置", style = TvText.h1, modifier = Modifier.padding(bottom = 4.dp)) }

        item {
            SettingCard(
                title = "当前版本",
                subtitle = if (version.isBlank()) "未知" else "v$version",
                icon = null,
                onClick = null,
            )
        }

        item {
            SettingCard(
                title = "检查更新",
                subtitle = when {
                    checking -> "检查中…"
                    checkResult != null -> checkResult!!
                    else -> "从 GitHub 检查新版本"
                },
                icon = null,
                onClick = {
                    if (checking) return@SettingCard
                    checking = true
                    checkResult = null
                    scope.launch {
                        val has = runCatching { com.dailymusic.app.data.Updater.checkSilently(context, manual = true) }
                            .getOrDefault(false)
                        checking = false
                        checkResult = if (has) "发现新版本，请到官网下载" else "已是最新版本"
                    }
                },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingTile(title = "音质选择", subtitle = "播放音质", icon = AppIcons.Headphones, onClick = onOpenQuality)
                SettingTile(title = "定时关闭", subtitle = "睡眠定时", icon = AppIcons.Clock, onClick = onOpenSleep)
            }
        }

        item { BasicText("账号", style = TvText.h2, modifier = Modifier.padding(top = 6.dp)) }

        item {
            if (Auth.isLoggedIn) {
                SettingCard(
                    title = "退出登录",
                    subtitle = "清除本机登录态 · ${Auth.currentUserId().orEmpty()}",
                    icon = null,
                    onClick = {
                        Auth.clear()
                        Profile.clear()
                        YouthVip.clear()
                        FavoriteCloudClear()
                        PlayUrlStore.invalidate()
                        onOpenLogin()
                    },
                )
            } else {
                SettingCard(
                    title = "登录酷狗账号",
                    subtitle = "扫码登录，解锁高音质与签到",
                    icon = null,
                    onClick = onOpenLogin,
                )
            }
        }

        item {
            BasicText(
                text = "Daily Music TV · 用 Kotlin Compose 打造",
                style = TvText.caption.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/** 退出登录时清掉云收藏缓存（避免退出后还显示上一个账号的收藏）。 */
private fun FavoriteCloudClear() {
    runCatching { com.dailymusic.app.data.FavoriteCloud.clearCache() }
}

/** 设置页整行卡片。icon 保留参数位以对齐视觉，当前不显示图标。 */
@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: (() -> Unit)?,
) {
    val inner: @Composable BoxScope.() -> Unit = {
        Row(
            Modifier
                .fillMaxSize()
                .background(TvColor.card)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(title, style = TvText.h3)
                BasicText(subtitle, style = TvText.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (onClick != null) {
        FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(92.dp), shape = RoundedCornerShape(18.dp), content = inner)
    } else {
        Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(18.dp)), content = inner)
    }
}

/** 设置页小方块入口。 */
@Composable
private fun SettingTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FocusSurface(onClick = onClick, modifier = Modifier.width(220.dp).height(120.dp), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier.fillMaxSize().background(TvColor.card).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                colorFilter = ColorFilter.tint(TvColor.accent),
            )
            BasicText(title, style = TvText.h3.copy(fontSize = 15.sp))
            BasicText(subtitle, style = TvText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
