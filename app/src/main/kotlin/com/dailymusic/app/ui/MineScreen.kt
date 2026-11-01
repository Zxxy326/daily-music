package com.dailymusic.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Profile
import com.dailymusic.app.data.YouthVip
import com.dailymusic.app.data.vipExpireText
import com.dailymusic.app.native.KugouServer
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val QUALITIES = listOf(
    "128" to "标准",
    "320" to "高品质",
    "flac" to "无损",
    "high" to "Hi-Res",
)

@Composable
fun MineScreen(
    contentPadding: PaddingValues,
    onQualityChange: (String) -> Unit,
    onOpenLibrary: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenSignIn: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme

    // 每次进「我的」都刷一次：账号资料 + 签到状态。用户可能在别的设备上签过/换过头像。
    // 顺序不能反：Profile.refresh() 负责续期（token 过期时换新），
    // 先跑它，后面拉会员到期信息用的才是新凭证。
    LaunchedEffect(Unit) {
        Profile.refresh()
        YouthVip.refresh()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp)) {
                Text(
                    text = "我的",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
            }
        }

        // ---- 账号 ----
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    // 未登录、或者登录态已经失效（token 过期）时，点整张卡都去登录页 ——
                    // 失效状态下这卡片上除了「重新登录」没有别的有用信息。
                    .clickable { if (!Auth.isLoggedIn || !Profile.valid) onOpenLogin() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (Auth.isLoggedIn) {
                    // 头像：有就加载，没有就给一个首字母占位 —— 空着会显得「没登上」。
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (Profile.avatar.isNotBlank()) {
                            RemoteImage(
                                url = Profile.avatar,
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                            )
                        } else {
                            Text(
                                text = Profile.displayName.take(1),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = Profile.displayName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = buildString {
                                val id = Profile.userId.ifBlank { Auth.currentUserId().orEmpty() }
                                if (id.isNotBlank()) append("ID $id")
                            },
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        // 会员到期：和 MD3Music 一样直接摊在账号卡上，
                        // 用户一眼就知道「今天还听不听得成整首」。
                        val vipLine = listOfNotNull(
                            vipPart("畅听", YouthVip.tvipEnd),
                            vipPart("概念", YouthVip.svipEnd),
                        ).joinToString(" · ")
                        if (vipLine.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = vipLine,
                                fontSize = 12.sp,
                                color = colors.primary,
                            )
                        }
                        if (!Profile.valid) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = Profile.error.ifBlank { "登录态异常" },
                                fontSize = 12.sp,
                                color = colors.error,
                            )
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    if (Profile.valid) {
                        Text(
                            text = "退出",
                            modifier = Modifier.clickable(onClick = onLogout).padding(6.dp),
                            fontSize = 13.sp,
                            color = colors.error,
                        )
                    } else {
                        Text(
                            text = "重新登录",
                            modifier = Modifier.clickable(onClick = onOpenLogin).padding(6.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.primary,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.Person, colors.onSurfaceVariantSummary, 24.dp)
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("登录酷狗账号", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "登录后每天签到领畅听，才能听整首。",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("去登录", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.primary)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        // ---- 每日畅听（概念版签到）----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenSignIn)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(
                    icon = if (YouthVip.signedToday) AppIcons.Check else AppIcons.Calendar,
                    tint = if (YouthVip.signedToday) colors.primary else colors.onSurfaceSecondary,
                    size = 22.dp,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (YouthVip.signedToday) "今日畅听已到账" else "每日畅听",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when {
                            !Auth.isLoggedIn -> "登录后每天签到领一天畅听"
                            // tvipEnd 是「畅听到期时间」，不是签到时间。直接怼一个
                            // `2026-11-01 12:38:00` 在「今日已到账」下面，会被读成
                            // 「今天几点签的」，所以把语义写出来。
                            YouthVip.signedToday && YouthVip.tvipEnd.isNotBlank() ->
                                "畅听有效期至 ${YouthVip.tvipEnd}"
                            YouthVip.signedToday -> "概念版权益已生效"
                            else -> "还没签到 —— 不签到只有 60 秒试听"
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Text(
                    text = if (YouthVip.signedToday) "查看" else "去签到",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }

        // ---- 收藏 / 历史 入口 ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EntryCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.Heart,
                    title = "我的收藏",
                    subtitle = "${Library.favorites.size} 首",
                    onClick = { onOpenLibrary(0) },
                )
                EntryCard(
                    modifier = Modifier.weight(1f),
                    icon = AppIcons.QueueMusic,
                    title = "播放历史",
                    subtitle = "${Library.history.size} 首",
                    onClick = { onOpenLibrary(1) },
                )
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .padding(16.dp),
            ) {
                Text("音质", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "无损 / Hi-Res 需要会员权益；拿不到时逐级降级，" +
                        "下方「当前实际音质」显示真实档位。",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUALITIES.forEach { (value, label) ->
                        val selected = PlayerManager.quality == value
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(
                                    if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { onQualityChange(value) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceSecondary,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("运行状态", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                InfoRow("本地服务端口", if (KugouServer.port > 0) KugouServer.port.toString() else "未启动")
                InfoRow("当前实际音质", PlayerManager.actualQualityLabel.ifBlank { "—" })
                InfoRow("界面组件库", "Miuix 0.9.3")
                InfoRow("播放内核", "Media3 ExoPlayer 1.4.1")
                KugouServer.lastError?.let { InfoRow("服务错误", it) }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        // ---- 设置 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(AppIcons.Tune, colors.primary, 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "设置",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.5.sp,
                    color = colors.onSurface,
                )
                Text(
                    text = "项目信息",
                    fontSize = 12.5.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Text("›", fontSize = 17.sp, color = colors.onSurfaceVariantSummary)
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(
                    text = "Daily Music · 自用构建\n曲库与接口来自第三方开源项目，仅供个人学习，请勿分发。",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .background(colors.surfaceContainer, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        TintedIcon(icon, colors.primary, 22.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = colors.onSurfaceSecondary)
        Text(value, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
    }
}

/** `畅听 5 天后到期`；没开通返回 null（不占位）。 */
private fun vipPart(label: String, end: String): String? {
    if (end.isBlank()) return null
    val rel = vipExpireText(end)
    return if (rel.isBlank()) "$label 已开通" else "$label $rel"
}
