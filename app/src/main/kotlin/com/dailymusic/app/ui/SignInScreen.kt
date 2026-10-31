package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.SignOutcome
import com.dailymusic.app.data.YouthVip
import com.dailymusic.app.data.vipExpireText
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日畅听（概念版签到）。
 *
 * 这个页面存在的唯一理由：概念版的权益是「每天签到领一天畅听」，不签到就只有
 * 60 秒试听。所以这里既要有**一键签到**，也要让用户看得见「哪天签了、权益到哪天」。
 */
@Composable
fun SignInScreen(onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // 底栏是浮在内容之上的，滚动区必须自己留出避让高度，否则最后一张卡被压住。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) { YouthVip.refresh() }

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
            Text("每日畅听", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // ---- 今日状态 + 签到 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainer, RoundedCornerShape(24.dp))
                    .padding(18.dp),
            ) {
                Text(
                    text = if (YouthVip.signedToday) "今日已签到" else "今日还没签到",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (YouthVip.signedToday) colors.primary else colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (YouthVip.signedToday) {
                        "畅听权益已到账，今天可以正常听整首。"
                    } else {
                        "概念版每天签到领一天畅听，不签到只能试听 60 秒。"
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary,
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionChip(
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.Check,
                        text = when {
                            busy -> "签到中…"
                            YouthVip.signedToday -> "再签一次"
                            else -> "立即签到"
                        },
                        filled = true,
                        enabled = !busy && !YouthVip.running && Auth.isLoggedIn,
                        onClick = {
                            scope.launch {
                                busy = true
                                YouthVip.signIn()
                                busy = false
                            }
                        },
                    )
                    ActionChip(
                        modifier = Modifier.weight(1f),
                        icon = AppIcons.Headphones,
                        text = if (busy) "处理中" else "听歌领取",
                        filled = false,
                        enabled = !busy && !YouthVip.running && Auth.isLoggedIn,
                        onClick = {
                            scope.launch {
                                busy = true
                                YouthVip.listenClaim()
                                busy = false
                            }
                        },
                    )
                }

                if (!Auth.isLoggedIn) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "签到需要登录酷狗账号。",
                        fontSize = 12.sp,
                        color = colors.error,
                    )
                }

                YouthVip.lastOutcome?.let { OutcomeText(it) }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 本月日历 ----
            CalendarCard()

            Spacer(Modifier.height(12.dp))

            // ---- 会员到期 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainer, RoundedCornerShape(24.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("会员到期时间", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                ExpiryRow("畅听会员", YouthVip.tvipEnd)
                ExpiryRow("概念会员", YouthVip.svipEnd)
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainer, RoundedCornerShape(24.dp))
                    .padding(18.dp),
            ) {
                Text("广告领取", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "概念版每天可以「看广告」领额外额度，最多 8 次、每次之间隔 30 秒。" +
                        "这里会连续上报 8 次，中途可以退出。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                ActionChip(
                    icon = AppIcons.Check,
                    text = if (busy) "处理中" else "开始广告领取",
                    filled = false,
                    enabled = !busy && !YouthVip.running && Auth.isLoggedIn,
                    onClick = {
                        scope.launch {
                            busy = true
                            YouthVip.adClaim()
                            busy = false
                        }
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "签到链路：领畅听 VIP（/youth/day/vip）→ 升级概念版（/youth/day/vip/upgrade）。" +
                    "两步都成功才算完整签到；只有第一步成功时会如实提示。\n" +
                    "上游偶尔要求滑块安全验证（错误码 20028），当前版本没有实现，" +
                    "遇到时去官方 App 签一次即可。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(navBottom + 104.dp))
        }
    }
}

@Composable
private fun OutcomeText(outcome: SignOutcome) {
    val colors = MiuixTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Text(
        text = outcome.message,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = if (outcome.success) colors.primary else colors.error,
    )
}

@Composable
private fun CalendarCard() {
    val colors = MiuixTheme.colorScheme
    val cal = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar: SUNDAY=1 … SATURDAY=7 → 周一开头要 0 个前导，周二要 1 个。
    val leading = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val today = remember {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
    val signed = YouthVip.signedDays

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Text(
            text = "$year 年 $month 月",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        val cells = ArrayList<Pair<Int, Boolean>>()   // day to isSigned，day=0 表示占位
        repeat(leading) { cells += 0 to false }
        for (d in 1..daysInMonth) {
            val key = String.format(Locale.US, "%04d-%02d-%02d", year, month, d)
            cells += d to signed.contains(key)
        }
        while (cells.size % 7 != 0) cells += 0 to false

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                week.forEach { (day, isSigned) ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day == 0) {
                            Spacer(Modifier.size(32.dp))
                        } else {
                            val key = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
                            val isToday = key == today
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSigned -> colors.primary
                                            isToday -> colors.primary.copy(alpha = 0.12f)
                                            else -> Color.Transparent
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = day.toString(),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSigned || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSigned -> colors.onPrimary
                                        isToday -> colors.primary
                                        else -> colors.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "本月已签 ${signed.count { it.startsWith(String.format(Locale.US, "%04d-%02d", year, month)) }} / $daysInMonth 天",
            fontSize = 12.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ExpiryRow(label: String, end: String) {
    val colors = MiuixTheme.colorScheme
    val rel = vipExpireText(end)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = colors.onSurfaceSecondary)
        Text(
            text = when {
                end.isBlank() -> "未开通"
                rel.isNotBlank() -> "$rel · $end"
                else -> end
            },
            fontSize = 12.sp,
            color = if (end.isBlank()) colors.onSurfaceVariantSummary else colors.onSurface,
        )
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    filled: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
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
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TintedIcon(
            icon = icon,
            tint = when {
                !enabled -> colors.onSurfaceVariantSummary
                filled -> colors.onPrimary
                else -> colors.primary
            },
            size = 16.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> colors.onSurfaceVariantSummary
                filled -> colors.onPrimary
                else -> colors.onSurface
            },
        )
    }
}
