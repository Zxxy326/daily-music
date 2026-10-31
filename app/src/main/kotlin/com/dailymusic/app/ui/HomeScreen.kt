package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FeedModule
import com.dailymusic.app.data.HomeFeed
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Profile
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Rank
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalTime

/** 首页快捷入口。`action` 由调用方决定，这里只管视觉。 */
private data class QuickEntry(
    val label: String,
    val icon: ImageVector,
    val tint: List<Color>,
)

private val QuickEntries = listOf(
    QuickEntry("每日推荐", AppIcons.Sparkle, listOf(Color(0xFFFFD08A), Color(0xFFF97F5F))),
    QuickEntry("排行榜", AppIcons.Chart, listOf(Color(0xFF9FD8FF), Color(0xFF4A8DF0))),
    QuickEntry("歌单广场", AppIcons.ListBullet, listOf(Color(0xFFC7B6FF), Color(0xFF7B5CF0))),
    QuickEntry("识曲", AppIcons.Mic, listOf(Color(0xFFA8ECD0), Color(0xFF3FB894))),
)

/**
 * 首页，版式对齐 `design/mockup.html` 第 ① 屏：
 * 大标题 + 问候语 + 头像 / 搜索药丸 / 四宫格快捷入口 / 推荐歌单横滑 / 为你推荐卡片列表。
 *
 * 和旧版的三处主要差别：
 * 1. 标题下面那行是**问候语**而不是日期 —— 日期对「现在听什么」没有帮助；
 * 2. 榜单从「横向小卡」升级成「推荐歌单」的 132dp 大封面 + 播放量徽章；
 * 3. 歌曲列表包进一张白色圆角卡片，行间用缩进分隔线，不再是一行一张散卡。
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenSearch: () -> Unit,
    onOpenRank: (Rank) -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onOpenDaily: () -> Unit,
    onOpenRankList: () -> Unit,
    onOpenPlaylistSquare: () -> Unit,
    onOpenRecognize: () -> Unit,
    onOpenPlaylist: (PlaylistBrief) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var ranks by remember { mutableStateOf<List<Rank>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistBrief>>(emptyList()) }
    var modules by remember { mutableStateOf<List<FeedModule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        // 头像只有「我的」页会刷，直接冷启动停在首页时这里是空的。
        if (!Profile.loaded && Auth.isLoggedIn) Profile.refresh()
    }

    // 推荐模块单独拉：七个接口并发，失败一块只少一行，不阻塞下面的主列表。
    LaunchedEffect(attempt) {
        modules = emptyList()
        modules = runCatching { HomeFeed.load() }.getOrDefault(emptyList())
    }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        try {
            val deviceOk = KugouApi.ensureDevice()
            val list = runCatching { KugouApi.recommend() }.getOrDefault(emptyList())
            songs = list
            // 榜单 / 推荐歌单失败都不该影响首页主列表，各自单独容错。
            ranks = runCatching { KugouApi.rankList() }.getOrDefault(emptyList()).take(12)
            // 推荐歌单走 /top/playlist（上游 special_recommend）—— 这是酷狗自己的
            // 「推荐歌单」位，匿名就能拿到；不要拿排行榜当推荐位，那是另一回事。
            playlists = runCatching { KugouApi.playlistSquare(page = 1, pageSize = 12) }
                .getOrNull()?.list.orEmpty().take(12)
            error = when {
                !deviceOk -> "本地服务未就绪（设备注册失败）"
                list.isEmpty() -> "没有拿到推荐内容"
                else -> null
            }
        } finally {
            // 必须放 finally：任何一步抛异常都会让 loading 永远停在 true，
            // 首页就一直挂着「加载中」，连重试按钮都出不来。
            loading = false
        }
    }

    // 视觉稿的列表卡是纯白；Miuix 的 surfaceContainer 在浅色下偏灰，一眼就能看出差别。
    val cardBg = if (isSystemInDarkTheme()) colors.surfaceContainer else Color.White
    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                // 标题行：左边问候，右边头像
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Daily Music",
                            fontSize = 31.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1.1).sp,
                            lineHeight = 35.sp,
                            color = colors.onBackground,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = greeting(),
                            fontSize = 12.5.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    AvatarBubble()
                }

                // 搜索药丸。视觉稿 .searchbar 用的是 --card（浅色纯白），
                // 之前用 surfaceContainer 会发灰，和下面白色的四宫格不是一套色。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp)
                        .height(50.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(25.dp),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.05f),
                            spotColor = Color.Black.copy(alpha = 0.05f),
                        )
                        .background(cardBg, RoundedCornerShape(25.dp))
                        // clip 必须在 clickable 之前：background 只负责「画」一个圆角矩形，
                        // 不参与裁剪；涟漪是画在节点矩形边界里的，不裁剪就是直角。
                        .clip(RoundedCornerShape(25.dp))
                        .clickable(onClick = onOpenSearch)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TintedIcon(AppIcons.Search, colors.onSurfaceVariantSummary, 17.dp)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        text = "搜索歌曲、歌手、歌单",
                        fontSize = 14.5.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }

                // 四宫格快捷入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    QuickEntries.forEach { entry ->
                        QuickTile(entry, Modifier.weight(1f)) {
                            when (entry.label) {
                                "每日推荐" -> onOpenDaily()
                                "排行榜" -> onOpenRankList()
                                "歌单广场" -> onOpenPlaylistSquare()
                                else -> onOpenRecognize()
                            }
                        }
                    }
                }
            }
        }

        // 推荐歌单：官方推荐位，横滑大卡（这一块保持大卡，用户明确要的）。
        if (playlists.isNotEmpty()) {
            item { SectionLabel("推荐歌单") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        PlaylistCard(pl) { onOpenPlaylist(pl) }
                    }
                }
            }
        }

        // 概念版推荐模块：红心 Radio / 私人专属好歌 / 概念er新推 / 曲风盲盒 …
        // 标题和内容都由上游给，前端只负责按顺序排。
        modules.forEach { module ->
            item(key = "mod-head-${module.id}") {
                SectionLabel(
                    text = module.title,
                    trailing = module.subtitle.ifBlank { null },
                )
            }
            item(key = "mod-row-${module.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(module.songs, key = { it.key }) { song ->
                        SongCard(song) { onPlay(song, module.songs) }
                    }
                }
            }
        }

        // 排行榜：竖排列表，不再是横滑大卡片。
        //
        // 大卡一屏只放得下 2.5 个，用户得横向扒拉才知道有哪些榜；
        // 竖排一行一个，名字和播放量一眼扫完，也和下面的「为你推荐」是同一套列表语言。
        if (ranks.isNotEmpty()) {
            item { SectionLabel("排行榜") }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(cardBg, RoundedCornerShape(24.dp))
                        // 行涟漪要被卡片圆角裁掉，否则首尾两行会把圆角顶成直角。
                        .clip(RoundedCornerShape(24.dp))
                        .padding(vertical = 6.dp),
                ) {
                    // 只露前 5 个。12 个榜单全铺在首页会把「为你推荐」挤到两屏以外，
                    // 而首页的主线是听歌，不是逛榜单。剩下的走「查看全部」。
                    ranks.take(5).forEachIndexed { index, rank ->
                        RankRow(rank, showDivider = index > 0) { onOpenRank(rank) }
                    }
                    if (ranks.size > 5) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 76.dp, end = 18.dp)
                                .height(1.dp)
                                .background(colors.onSurface.copy(alpha = 0.07f)),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenRankList)
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "查看全部 ${ranks.size} 个榜单",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.primary,
                            )
                            Spacer(Modifier.weight(1f))
                            // 没有 ChevronRight 图标，用「›」字符代替 —— 比把返回箭头
                            // 旋转 180° 干净，也不用为此引一个 graphicsLayer。
                            Text(
                                text = "›",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.primary,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionLabel(
                text = "为你推荐",
                trailing = when {
                    loading -> "加载中"
                    songs.isNotEmpty() -> "${songs.size} 首"
                    else -> null
                },
            )
        }

        if (error != null) {
            item { ErrorBlock(error!!) { attempt++ } }
        }

        if (songs.isNotEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .background(cardBg, RoundedCornerShape(24.dp))
                        // 卡片里的每一行都有涟漪；不裁剪的话首尾两行会把卡片的圆角
                        // 顶成直角。clip 放在 background 之后，行涟漪一起被裁。
                        .clip(RoundedCornerShape(24.dp))
                        .padding(vertical = 6.dp),
                ) {
                    songs.forEachIndexed { index, song ->
                        CardSongRow(
                            song = song,
                            isCurrent = song.key == currentKey,
                            isPlaying = playing,
                            onClick = { onPlay(song, songs) },
                            showDivider = index > 0,
                            favorite = Library.isFavorite(song.key),
                            onToggleFavorite = { Library.toggleFavorite(song) },
                        )
                    }
                }
            }
        }

        if (!loading && songs.isEmpty() && error == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("暂无内容", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** 按时段问候。mockup 写的是「晚上好」，真机照抄会变成半夜也说晚上好。 */
private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..8 -> "早上好，听点什么"
    in 9..11 -> "上午好，听点什么"
    in 12..13 -> "中午好，听点什么"
    in 14..17 -> "下午好，听点什么"
    in 18..22 -> "晚上好，听点什么"
    else -> "夜深了，听点什么"
}

/** 右上角头像：登录过就显示真实头像，否则用一枚渐变色占位。 */
@Composable
private fun AvatarBubble() {
    val url = Profile.avatar
    if (url.isNotBlank()) {
        RemoteImage(
            url = url,
            modifier = Modifier.size(40.dp).clip(CircleShape),
            shape = CircleShape,
        )
    } else {
        Box(
            Modifier
                .size(40.dp)
                .background(
                    Brush.linearGradient(listOf(Color(0xFFFFC48A), Color(0xFFEF6F8E))),
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun QuickTile(entry: QuickEntry, modifier: Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(top = 13.dp, bottom = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Brush.linearGradient(entry.tint), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(entry.icon, Color.White, 16.dp)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = entry.label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceSecondary,
            maxLines = 1,
        )
    }
}

/** 推荐歌单大卡：132dp 封面 + 播放量徽章 + 两行文字，对齐视觉稿 `.pl`。 */
@Composable
private fun PlaylistCard(pl: PlaylistBrief, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.width(132.dp).clip(RoundedCornerShape(26.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.size(132.dp)) {
            RemoteImage(
                url = pl.cover(320),
                modifier = Modifier.size(132.dp),
                shape = RoundedCornerShape(26.dp),
            )
            // 播放量压一层半透明底，白字才在浅色封面上也读得出来。
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(99.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TintedIcon(AppIcons.Play, Color.White, 9.dp)
                Spacer(Modifier.width(3.dp))
                Text(
                    text = pl.playCountText.ifBlank { "歌单" },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = pl.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            lineHeight = 17.sp,
            overflow = TextOverflow.Ellipsis,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = pl.collectText,
            fontSize = 11.5.sp,
            maxLines = 1,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/** 推荐模块里的歌曲小卡：108dp 封面 + 歌名 + 歌手。 */
@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        RemoteImage(
            url = song.cover(320),
            modifier = Modifier.size(108.dp),
            shape = RoundedCornerShape(20.dp),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = song.name,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = song.singer,
            fontSize = 11.sp,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 榜单行，对齐「为你推荐」那套列表语言：46dp 封面 + 名字 + 一行说明。
 *
 * 以前这里是 132dp 大卡横滑，一屏只露出两个半；榜单本来就是「扫一眼有哪些」的场景，
 * 竖排列表比大卡更适合。
 */
@Composable
private fun RankRow(rank: Rank, showDivider: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 76.dp, end = 18.dp)
                    .height(1.dp)
                    .background(colors.onSurface.copy(alpha = 0.07f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = rank.cover(240),
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = rank.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append("榜单 · 每日更新")
                        if (rank.playCountText.isNotBlank()) append(" · ${rank.playCountText}次播放")
                    },
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            TintedIcon(AppIcons.Chart, colors.onSurfaceVariantSummary.copy(alpha = 0.55f), 15.dp)
        }
    }
}

@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .background(colors.surfaceContainer, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, fontSize = 13.sp, color = colors.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(
            modifier = Modifier.clickable(onClick = onRetry),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintedIcon(AppIcons.Refresh, colors.primary, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("重试", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.primary)
        }
    }
}
