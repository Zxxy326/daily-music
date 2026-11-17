package com.dailymusic.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.FeedModule
import com.dailymusic.app.data.HomeFeed
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/**
 * TV 首页。
 *
 * 布局照设计稿：
 *   1. 继续聆听（大卡，背景是当前曲封面）
 *   2. 四个快捷入口（搜索 / 发现 / 收藏 / 历史）
 *   3. 热门新歌（左侧大封面 + 右侧列表）
 *
 * 纵向用 LazyColumn，遥控器上下移动焦点时列表会跟着滚 —— 电视上「一屏能看多少」
 * 比手机重要得多，所以每块都按 1080p 全屏来排。
 */
@Composable
fun TvHomeScreen(
    onOpenPlayer: () -> Unit,
    onTab: (Int) -> Unit = {},
    onSub: (TvSub) -> Unit = {},
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playlists by remember { mutableStateOf<List<PlaylistBrief>>(emptyList()) }
    var modules by remember { mutableStateOf<List<FeedModule>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { KugouApi.ensureDevice() }
        songs = runCatching { KugouApi.recommend() }.getOrDefault(emptyList())
        loading = false
    }

    // 推荐歌单 + 概念版推荐模块各自容错，失败只少一块，不影响上面的主内容。
    LaunchedEffect(Unit) {
        playlists = runCatching { KugouApi.playlistSquare(page = 1, pageSize = 12) }
            .getOrNull()?.list.orEmpty().take(12)
        modules = runCatching { HomeFeed.load() }.getOrDefault(emptyList())
    }

    val current = PlayerManager.current
    val hero = current ?: songs.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 8.dp,
            end = 40.dp,
            top = 28.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { HeroCard(hero, onOpenPlayer) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvPill(
                    icon = AppIcons.Sparkle,
                    title = "每日推荐",
                    subtitle = "每天 30 首",
                    onClick = { onSub(TvSub.Daily) },
                    modifier = Modifier.weight(1f).height(80.dp),
                )
                TvPill(
                    icon = AppIcons.Chart,
                    title = "排行榜",
                    subtitle = "热歌新歌榜",
                    onClick = { onTab(3) },
                    modifier = Modifier.weight(1f).height(80.dp),
                )
                TvPill(
                    icon = AppIcons.ListBullet,
                    title = "歌单广场",
                    subtitle = "按分类找歌单",
                    onClick = { onSub(TvSub.Square) },
                    modifier = Modifier.weight(1f).height(80.dp),
                )
                TvPill(
                    icon = AppIcons.MusicNote,
                    title = "新歌速递",
                    subtitle = "本周新歌",
                    onClick = { onSub(TvSub.NewSongs) },
                    modifier = Modifier.weight(1f).height(80.dp),
                )
                TvPill(
                    icon = AppIcons.Library,
                    title = "曲库",
                    subtitle = "收藏与历史",
                    onClick = { onTab(2) },
                    modifier = Modifier.weight(1f).height(80.dp),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText("热门新歌", style = TvText.h1)
                    BasicText(
                        text = if (loading) "正在挑选…" else "根据你的口味挑选 · ${songs.size} 首",
                        style = TvText.body,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TvButton(
                    text = "播放全部",
                    primary = true,
                    onClick = {
                        songs.firstOrNull()?.let { first ->
                            PlayerManager.play(context, first, songs)
                            onOpenPlayer()
                        }
                    },
                    modifier = Modifier.width(140.dp).height(48.dp),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // 左侧大封面：只作视觉锚点，点它播第一首
                FocusSurface(
                    onClick = {
                        songs.firstOrNull()?.let { first ->
                            PlayerManager.play(context, first, songs)
                            onOpenPlayer()
                        }
                    },
                    modifier = Modifier.width(320.dp).height(320.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    TvImage(songs.firstOrNull()?.cover(720), Modifier.fillMaxSize(), RoundedCornerShape(22.dp))
                }

                // 右侧列表：两列铺开，一屏能看到 6 首
                Column(
                    Modifier.weight(1f).height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val visible = songs.take(6)
                    visible.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            pair.forEachIndexed { offset, song ->
                                val realIndex = visible.indexOf(song)
                                SongRow(
                                    index = realIndex,
                                    name = song.name,
                                    singer = song.singer,
                                    cover = song.cover(160),
                                    onClick = {
                                        PlayerManager.play(context, song, songs)
                                        onOpenPlayer()
                                    },
                                    modifier = Modifier.weight(1f).height(72.dp),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 推荐歌单（官方推荐位，横滑大卡）→ 点进歌单详情
        item {
            TvRecommendPlaylists(
                playlists = playlists,
                onOpenPlaylist = { onSub(TvSub.Playlist(it)) },
            )
        }

        // 概念版推荐模块：红心 Radio / 私人专属好歌 / 曲风盲盒 … 每个横滑歌曲卡
        modules.forEach { module ->
            item(key = "feed-${module.id}") {
                TvFeedModuleRow(
                    module = module,
                    onPlay = { song, queue ->
                        PlayerManager.play(context, song, queue)
                        onOpenPlayer()
                    },
                )
            }
        }
    }
}

/** 继续聆听大卡。 */
@Composable
private fun HeroCard(song: Song?, onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(26.dp)
    // 进入页面时焦点给「继续播放」而不是左侧导航：
    // 电视上默认焦点会落到第一个可聚焦元素（导航第一项），但用户真正想操作的是内容区。
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { primaryFocus.requestFocus() }
    }

    Box(Modifier.fillMaxWidth().height(300.dp)) {
        // 背景：当前曲封面铺满 + 左侧压暗，保证文字可读
        TvImage(song?.cover(1080), Modifier.fillMaxSize(), shape)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        0f to TvColor.bg.copy(alpha = 0.94f),
                        0.45f to TvColor.bg.copy(alpha = 0.72f),
                        1f to TvColor.bg.copy(alpha = 0.30f),
                    ),
                ),
        )

        Row(Modifier.fillMaxSize().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = if (PlayerManager.current != null) "继续聆听" else "今日推荐",
                    style = TvText.caption.copy(color = TvColor.textDim),
                )
                Spacer(Modifier.height(10.dp))
                BasicText(
                    text = song?.name ?: "还没有正在播放的歌曲",
                    style = TvText.h1.copy(fontSize = 40.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = song?.singer ?: "去「热门新歌」里挑一首",
                    style = TvText.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton(
                        text = if (PlayerManager.isPlaying) "正在播放" else "继续播放",
                        primary = true,
                        onClick = {
                            if (song != null) {
                                if (PlayerManager.current == null) {
                                    PlayerManager.play(context, song, listOf(song))
                                } else {
                                    PlayerManager.toggle(context)
                                }
                                onOpenPlayer()
                            }
                        },
                        modifier = Modifier
                            .width(150.dp)
                            .height(50.dp)
                            .focusRequester(primaryFocus),
                    )
                    TvButton(
                        text = "发现更多",
                        primary = false,
                        onClick = { },
                        modifier = Modifier.width(150.dp).height(50.dp),
                    )
                }
            }
            if (song != null) {
                TvImage(
                    url = song.cover(480),
                    modifier = Modifier.size(216.dp),
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
    }
}
