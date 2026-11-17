package com.dailymusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.playback.PlayOrder
import com.dailymusic.app.playback.PlayerManager

/**
 * TV 主框架：左侧导航 + 右侧内容 + 底部播放条。
 *
 * 播放条常驻在底部而不是「点歌才出现」：电视上听完一首歌想切歌，
 * 不应该先回到某个页面再找播放器。
 */
@Composable
fun TvRoot() {
    var page by remember { mutableIntStateOf(0) }
    var showPlayer by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<TvSub?>(null) }
    // 播放页里的工具面板（队列/音质/定时/更多），覆盖在播放页之上。
    var playerTool by remember { mutableStateOf<PlayerTool?>(null) }
    // 歌词动效全屏页（动感涟漪 / 简约 / 精简时钟），层级最高。
    var showLyricEffect by remember { mutableStateOf(false) }

    // 返回键优先级：歌词动效 > 工具面板 > 播放页 > 二级页 > 退出。
    BackHandler(enabled = showLyricEffect) { showLyricEffect = false }
    BackHandler(enabled = !showLyricEffect && playerTool != null) { playerTool = null }
    BackHandler(enabled = !showLyricEffect && playerTool == null && showPlayer) { showPlayer = false }
    // 详情页在内容区里切换，返回键先退详情、再退页面。
    // 「我的歌单详情」特殊：它上面还有「我的歌单」列表这一层，先退回列表。
    BackHandler(enabled = !showLyricEffect && playerTool == null && !showPlayer && sub != null) {
        sub = if (sub is TvSub.MyPlaylistDetail) TvSub.MyPlaylists else null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(TvColor.bg)
    ) {
        Row(Modifier.fillMaxSize()) {
            NavRail(page) { page = it }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 20.dp),
            ) {
                val current = sub
                if (current != null) {
                    when (current) {
                        is TvSub.Playlist -> TvPlaylistScreen(
                            current.playlist,
                            onOpenPlayer = { showPlayer = true },
                        )
                        is TvSub.RankDetail -> TvRankDetailScreen(
                            current.rank,
                            onOpenPlayer = { showPlayer = true },
                        )
                        TvSub.Square -> TvSquareScreen(
                            onOpenPlaylist = { sub = TvSub.Playlist(it) },
                        )
                        TvSub.Daily -> TvDailyScreen(onOpenPlayer = { showPlayer = true })
                        TvSub.NewSongs -> TvNewSongsScreen(onOpenPlayer = { showPlayer = true })
                        TvSub.MyPlaylists -> TvMyPlaylistsScreen(
                            onOpenPlaylist = { sub = TvSub.MyPlaylistDetail(it) },
                        )
                        is TvSub.MyPlaylistDetail -> TvPlaylistScreen(
                            playlist = com.dailymusic.app.data.PlaylistBrief(
                                id = current.playlist.listid,
                                name = current.playlist.name,
                                coverTemplate = current.playlist.coverTemplate,
                                songCount = current.playlist.songCount,
                                playCount = 0,
                                globalCollectionId = "",
                            ),
                            listid = current.playlist.listid,
                            onOpenPlayer = { showPlayer = true },
                        )
                        is TvSub.Artist -> TvArtistScreen(
                            name = current.name,
                            onOpenPlayer = { showPlayer = true },
                        )
                        TvSub.Login -> TvLoginScreen(onLoggedIn = { sub = null })
                        TvSub.Settings -> TvSettingsScreen(
                            onOpenQuality = { playerTool = PlayerTool.Quality },
                            onOpenSleep = { playerTool = PlayerTool.Sleep },
                            onOpenLogin = { sub = TvSub.Login },
                        )
                        else -> Placeholder("")
                    }
                } else when (page) {
                    0 -> TvHomeScreen(
                        onOpenPlayer = { showPlayer = true },
                        onTab = { page = it },
                        onSub = { sub = it },
                    )
                    1 -> TvSearchScreen(onOpenPlayer = { showPlayer = true })
                    2 -> TvLibraryScreen(
                        onOpenPlayer = { showPlayer = true },
                        onOpenMyPlaylists = { sub = TvSub.MyPlaylists },
                    )
                    3 -> TvRankScreen(onOpenRank = { sub = TvSub.RankDetail(it) })
                    4 -> TvMoreScreen(
                        onOpenLogin = { sub = TvSub.Login },
                        onOpenSettings = { sub = TvSub.Settings },
                    )
                    else -> Placeholder(NavItems[page].label)
                }
            }
        }

        if (PlayerManager.current != null) {
            PlayBar(
                onOpen = { showPlayer = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 200.dp, end = 32.dp, bottom = 18.dp),
            )
        }

        if (showPlayer) {
            TvNowPlaying(
                onClose = { showPlayer = false },
                onOpenQueue = { playerTool = PlayerTool.Queue },
                onOpenQuality = { playerTool = PlayerTool.Quality },
                onOpenSleep = { playerTool = PlayerTool.Sleep },
                onOpenMore = { playerTool = PlayerTool.More },
                onOpenLyricEffect = { showLyricEffect = true },
            )
        }

        // 歌词动效全屏页：盖在播放页与工具面板之上，返回键优先级最高。
        if (showLyricEffect) {
            PlayerManager.current?.let { song ->
                com.dailymusic.tv.ui.TvLyricEffectScreen(
                    song = song,
                    onClose = { showLyricEffect = false },
                )
            }
        }

        if (playerTool != null) {
            TvPlayerToolPanel(
                tool = playerTool!!,
                onClose = { playerTool = null },
                onOpenQueue = { playerTool = PlayerTool.Queue },
            )
        }
    }
}

/** 播放页工具面板类型：从播放页工具行进入，覆盖在播放页之上。 */
enum class PlayerTool { Queue, Quality, Sleep, More }

/**
 * 二级页面。
 *
 * 电视上的层级只有两层：导航项（一级）+ 详情（二级）。
 * 用 sealed 而不是多个 Boolean —— 多个布尔会出现「同时为真」的非法状态。
 */
sealed interface TvSub {
    data class Playlist(val playlist: com.dailymusic.app.data.PlaylistBrief) : TvSub
    data class RankDetail(val rank: com.dailymusic.app.data.Rank) : TvSub
    data object Square : TvSub
    data object Daily : TvSub
    data object NewSongs : TvSub
    data object MyPlaylists : TvSub
    data class MyPlaylistDetail(val playlist: com.dailymusic.app.data.MyPlaylist) : TvSub
    data class Artist(val name: String) : TvSub
    data object Login : TvSub
    data object Settings : TvSub
}

private data class NavEntry(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val NavItems = listOf(
    NavEntry("首页", AppIcons.Home),
    NavEntry("搜索", AppIcons.Search),
    NavEntry("曲库", AppIcons.Library),
    NavEntry("榜单", AppIcons.Chart),
    NavEntry("更多", AppIcons.MoreHoriz),
)

@Composable
private fun NavRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(start = 18.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Logo：和设计稿一致，圆角方块 + 两行字
        Row(
            Modifier.padding(start = 10.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFBFD8FF), Color(0xFF8FB6E8)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.MusicNote),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvColor.onAccent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                BasicText("Daily", style = TvText.h3)
                BasicText("Music TV", style = TvText.caption)
            }
        }

        NavItems.forEachIndexed { i, item ->
            NavItem(
                icon = item.icon,
                label = item.label,
                selected = i == selected,
                onClick = { onSelect(i) },
            )
        }

        Spacer(Modifier.weight(1f))
        BasicText(
            text = "遥控器 · 方向键",
            style = TvText.caption.copy(color = TvColor.textDim.copy(alpha = 0.6f)),
            modifier = Modifier.padding(start = 18.dp, bottom = 90.dp),
        )
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText("$title · 建设中", style = TvText.h2.copy(color = TvColor.textDim))
    }
}

/** 底部播放条：进度 + 封面 + 信息 + 控制。 */
@Composable
private fun PlayBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val song = PlayerManager.current ?: return
    val duration = PlayerManager.durationMs.coerceAtLeast(1L)
    val fraction = (PlayerManager.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(shape)
            .background(TvColor.card),
    ) {
        // 顶部 3dp 进度条，不占布局高度
        Box(Modifier.fillMaxWidth().height(3.dp).background(TvColor.stroke)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(TvColor.accent),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusSurface(
                onClick = onOpen,
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(12.dp),
                scaleUp = 1.0f,
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvImage(song.cover(120), Modifier.size(48.dp), RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        BasicText(
                            text = song.name,
                            style = TvText.h3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            text = "${song.singer} · ${if (PlayerManager.isPlaying) "正在播放" else "已暂停"}",
                            style = TvText.caption,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val order = PlayerManager.playOrder
                IconAction(
                    icon = when (order) {
                        PlayOrder.LIST -> AppIcons.Repeat
                        PlayOrder.ONE -> AppIcons.RepeatOne
                        PlayOrder.SHUFFLE -> AppIcons.Shuffle
                    },
                    52,
                    tint = if (order == PlayOrder.LIST) TvColor.textDim else TvColor.accent,
                ) { PlayerManager.cyclePlayOrder(context) }
                IconAction(AppIcons.SkipPrevious, 52) { PlayerManager.previous() }
                IconAction(
                    icon = if (PlayerManager.isPlaying) AppIcons.Pause else AppIcons.Play,
                    64,
                ) { PlayerManager.toggle(context) }
                IconAction(AppIcons.SkipNext, 52) { PlayerManager.next() }
            }
        }
    }
}
