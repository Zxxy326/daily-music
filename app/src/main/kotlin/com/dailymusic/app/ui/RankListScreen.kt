package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import com.dailymusic.app.data.Song
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Rank
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 排行榜目录。
 *
 * 首页只横滑展示前 12 个榜，这里是完整目录（实测 55 个左右）。
 * 两列网格，点进去复用 [PlaylistScreen] 的榜单详情。
 */
@Composable
fun RankListScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenRank: (Rank) -> Unit,
) {
    var ranks by remember { mutableStateOf<List<Rank>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        ranks = runCatching { KugouApi.rankList() }.getOrDefault(emptyList())
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            SubPageHeader(
                title = "排行榜",
                subtitle = if (ranks.isEmpty()) "" else "${ranks.size} 个榜单",
                onBack = onBack,
            )
        }

        when {
            loading -> item { LoadingHint("正在拉取榜单…") }
            ranks.isEmpty() -> item { EmptyHint("没有拿到榜单数据", "重试") { attempt++ } }
            else -> {
                // ---- 顶部：热榜速览（每张卡带前三首）----
                val featured = pickFeatured(ranks)
                if (featured.isNotEmpty()) {
                    item { SectionTitle("热榜速览", trailing = "更新于今天") }
                    items(featured, key = { "f-${it.id}" }) { rank ->
                        RankPreviewCard(rank = rank, onClick = { onOpenRank(rank) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                // ---- 下面按上游分类分组 ----
                // 上游 classify：2 地区榜、3 特色榜、4 全球榜、5 曲风榜，1 是热歌/新歌。
                // 不分组的话 55 个榜平铺，用户根本不知道「有哪些类别」。
                RankSections.forEach { section ->
                    val group = ranks.filter { it.classify == section.classify }
                    if (group.isNotEmpty()) {
                        item { SectionTitle(section.title, trailing = "${group.size} 个") }
                        itemsIndexed(group, key = { _, r -> r.id }) { index, rank ->
                            RankRow(
                                rank = rank,
                                showDivider = index > 0,
                                onClick = { onOpenRank(rank) },
                            )
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                }
            }
        }
    }
}

/**
 * 榜单行：46dp 封面 + 榜名 + 播放量。
 *
 * 和首页「推荐歌单」用的是同一套行样式 —— 同一个东西在首页和列表页长得不一样，
 * 用户会以为是两批数据。
 */
@Composable
private fun RankRow(rank: Rank, showDivider: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 78.dp, end = 20.dp)
                    .height(1.dp)
                    .background(colors.onSurface.copy(alpha = 0.07f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = rank.cover(240),
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = rank.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(rank.updateHint.ifBlank { "每日更新" })
                        if (rank.playCountText.isNotBlank()) append(" · ${rank.playCountText}次播放")
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 只有返回箭头的顶栏。
 *
 * 歌单详情这类页面，标题就在下面的大封面旁边，顶栏再写一遍歌名会重复。
 */
@Composable
fun BackBar(onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * 二级页统一顶栏：返回箭头 + 标题 + 可选副标题。
 *
 * 这些页面都从首页四宫格进来，层级上属于「盖在 tab 之上」，所以共用同一套头，
 * 免得每页各写一遍圆角/间距还写歪。
 */
@Composable
fun SubPageHeader(title: String, subtitle: String = "", onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.8).sp,
                color = colors.onBackground,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.width(9.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun LoadingHint(text: String) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
    }
}

/** 空态 / 失败态：一句话 + 可选的重试按钮。 */
@Composable
fun EmptyHint(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = action,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
            }
        }
    }
}


/** 榜单分区：标题 + 上游 classify。顺序就是页面上的顺序。 */
private data class RankSection(val title: String, val classify: Int)

private val RankSections = listOf(
    RankSection("新歌榜单", 2),
    RankSection("特色榜单", 3),
    RankSection("全球榜单", 4),
    RankSection("曲风榜单", 5),
    RankSection("热歌榜单", 1),
)

/**
 * 顶部「热榜速览」挑哪几个。
 *
 * 概念版首页那五张卡（助力周榜 / 概念版热歌榜 / 概念版飙升榜 / 说唱先锋榜 / ACG 新歌榜）
 * 走的是概念版自己的接口，我们代理的 `/rank/list` 里没有这几个 id。
 *
 * 但实测对得上：概念版飙升榜/说唱先锋榜/ACG 新歌榜的前三首，和 6666/44412/33162
 * 三个榜**完全一致**；概念版热歌榜和 TOP500 有 2/3 重合，就用 TOP500 顶。
 * 只有「助力周榜」是概念版自己的运营活动榜，没有对应物，不放。
 */
private val FEATURED_IDS = listOf(8888L, 6666L, 44412L, 33162L, 82831L)

private fun pickFeatured(ranks: List<Rank>): List<Rank> {
    val byId = ranks.associateBy { it.id }
    val picked = FEATURED_IDS.mapNotNull { byId[it] }
    return picked.ifEmpty { ranks.take(5) }
}

/**
 * 热榜速览卡片：左文右图，左边带前三首歌。
 *
 * 前三首是单独一次 `/rank/audio` 请求（pagesize=3），只在卡片进入组合时拉一次；
 * 5 张卡就是 5 个请求，比一次性拉 5×60 首省得多。
 */
@Composable
private fun RankPreviewCard(rank: Rank, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    var top by remember(rank.id) { mutableStateOf<List<Song>>(emptyList()) }

    LaunchedEffect(rank.id) {
        top = runCatching { KugouApi.rankSongs(rank.id, pageSize = 3) }.getOrDefault(emptyList())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = rank.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            if (top.isEmpty()) {
                Text(
                    text = if (rank.updateHint.isBlank()) " " else rank.updateHint,
                    fontSize = 11.5.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            } else {
                top.forEachIndexed { i, song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${i + 1}.",
                            fontSize = 12.5.sp,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            text = song.name,
                            fontSize = 12.5.sp,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = " — ${song.singer}",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (i != top.lastIndex) Spacer(Modifier.height(3.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        RemoteImage(
            url = rank.cover(240).orEmpty(),
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(14.dp),
        )
    }
}
