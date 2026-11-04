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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌单广场（`/top/playlist`）。
 *
 * 两列瀑布卡片，滚到底自动续页。首页那个「歌单广场」入口以前直接跳搜索页 ——
 * 搜索只能按关键词找，用户想「随便逛逛」时没有落点。
 */
@Composable
fun PlaylistSquareScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenPlaylist: (PlaylistBrief) -> Unit,
) {
    var items by remember { mutableStateOf<List<PlaylistBrief>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasNext by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    suspend fun load(target: Int) {
        if (loading) return
        loading = true
        val result = KugouApi.playlistSquare(target)
        if (result == null) {
            failed = items.isEmpty()
        } else {
            failed = false
            // 去重：续页时上游偶尔会把上一页末尾重复带回来。
            val seen = items.map { it.id }.toHashSet()
            items = items + result.list.filter { seen.add(it.id) }
            hasNext = result.hasNext
            page = target
        }
        loading = false
    }

    LaunchedEffect(attempt) {
        items = emptyList()
        hasNext = true
        load(1)
    }

    // 触底续页：用最后可见下标判断，比监听滚动量稳（快速甩动也不会漏触发）。
    LaunchedEffect(listState, hasNext) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { hasNext }
            .collect { last ->
                if (last >= listState.layoutInfo.totalItemsCount - 3) load(page + 1)
            }
    }

    // 概念版是三列：一屏能看到 9 个封面，「有哪些歌单」比「封面多大」更重要
    val rows = items.chunked(3)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            SubPageHeader(
                title = "歌单广场",
                subtitle = if (items.isEmpty()) "" else "已加载 ${items.size} 个",
                onBack = onBack,
            )
        }

        if (failed) {
            item { EmptyHint("歌单广场没拉到数据，上游可能限流", "重试") { attempt++ } }
        } else if (items.isEmpty()) {
            item { LoadingHint("正在拉取歌单…") }
        }

        itemsIndexed(rows) { _, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                for (pl in row) {
                    PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (items.isNotEmpty() && !hasNext) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "已经到底了",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(pl: PlaylistBrief, modifier: Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RemoteImage(
                url = pl.cover(320),
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(14.dp),
            )
            // 左上角分类标签：概念版的识别度主要来自这颗胶囊
            if (pl.tag.isNotBlank()) {
                Text(
                    text = pl.tag,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // 播放量放右下角，和标签错开
            if (pl.playCountText.isNotBlank()) {
                Text(
                    text = "▶ ${pl.playCountText}",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = pl.name,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
    }
}

/**
 * 歌单详情（广场里的歌单）。
 *
 * 和 [PlaylistScreen]（榜单详情）分开写，是因为两者的取数接口不同：
 * 榜单靠 `rankid`，广场歌单靠 `global_collection_id`，而且后者可能解析不出曲目。
 */
@Composable
fun PlaylistDetailScreen(
    playlist: PlaylistBrief,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    /** 账号里的私有歌单要传 listid：走 `/playlist/track/all/new`，广场歌单不传。 */
    listid: String? = null,
) {
    val colors = MiuixTheme.colorScheme
    val loadKey = listid ?: playlist.trackKey
    var songs by remember(loadKey) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(loadKey) { mutableStateOf(true) }
    var attempt by remember(loadKey) { mutableIntStateOf(0) }

    LaunchedEffect(loadKey, attempt) {
        loading = true
        songs = runCatching {
            if (listid != null) {
                KugouApi.myPlaylistSongs(listid)?.mapNotNull { Song.fromPlaylistFile(it) }.orEmpty()
            } else {
                KugouApi.playlistSongs(playlist.trackKey)
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item { BackBar(onBack = onBack) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaylistCover(
                    url = playlist.cover(400).takeIf { it.isNotBlank() },
                    name = playlist.name,
                    size = 104.dp,
                    shape = RoundedCornerShape(20.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            append(if (loading) "加载中" else "${songs.size} 首")
                            if (playlist.playCountText.isNotBlank()) append(" · ${playlist.playCountText}次播放")
                        },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (songs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.primary)
                                .clickable { onPlay(songs.first(), songs) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TintedIcon(AppIcons.Play, colors.onPrimary, 14.dp)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = "播放全部",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onPrimary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        when {
            loading -> item { LoadingHint("正在拉取歌单曲目…") }
            songs.isEmpty() -> item { EmptyHint("这个歌单没解析出可播放的曲目", "重试") { attempt++ } }
            else -> itemsIndexed(songs) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.key == currentKey,
                    isPlaying = playing,
                    onClick = { onPlay(song, songs) },
                )
                if (index != songs.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 84.dp, end = 20.dp)
                            .height(1.dp)
                            .background(colors.onSurface.copy(alpha = 0.06f)),
                    )
                }
            }
        }
    }
}
