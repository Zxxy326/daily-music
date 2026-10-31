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

    val rows = items.chunked(2)

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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                for (pl in row) {
                    PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(13.dp))
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
    Column(modifier = modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RemoteImage(
                url = pl.cover(320),
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(22.dp),
            )
            if (pl.playCountText.isNotBlank()) {
                Text(
                    text = "▶ ${pl.playCountText}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = pl.name,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
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
) {
    val colors = MiuixTheme.colorScheme
    var songs by remember(playlist.trackKey) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(playlist.trackKey) { mutableStateOf(true) }
    var attempt by remember(playlist.trackKey) { mutableIntStateOf(0) }

    LaunchedEffect(playlist.trackKey, attempt) {
        loading = true
        songs = runCatching { KugouApi.playlistSongs(playlist.trackKey) }.getOrDefault(emptyList())
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
                RemoteImage(
                    url = playlist.cover(400),
                    modifier = Modifier.size(104.dp),
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
