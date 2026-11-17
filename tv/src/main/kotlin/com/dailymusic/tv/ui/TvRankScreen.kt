package com.dailymusic.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Rank
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/** 榜单列表：网格铺开，一屏能看到十几个。 */
@Composable
fun TvRankScreen(
    onOpenRank: (Rank) -> Unit,
) {
    var ranks by remember { mutableStateOf<List<Rank>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        ranks = runCatching { KugouApi.rankList() }.getOrDefault(emptyList())
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp)) {
        BasicText(
            text = "排行榜",
            style = TvText.h1,
            modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
        )
        if (loading) {
            CenterHint("加载中…")
        } else if (ranks.isEmpty()) {
            CenterHint("榜单加载失败")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(ranks, key = { it.id }) { rank ->
                    RankCard(rank) { onOpenRank(rank) }
                }
            }
        }
    }
}

@Composable
private fun RankCard(rank: Rank, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(240.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            TvImage(
                url = rank.cover(400),
                modifier = Modifier.fillMaxWidth().height(178.dp),
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = rank.name,
                style = TvText.h3.copy(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            BasicText(
                text = rank.updateHint.ifBlank { rank.playCountText },
                style = TvText.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
}

/** 榜单详情：和歌单详情同构，左封面 + 右曲目。 */
@Composable
fun TvRankDetailScreen(rank: Rank, onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(rank.id) {
        songs = runCatching { KugouApi.rankSongs(rank.id) }.getOrDefault(emptyList())
        loading = false
    }

    Row(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp)) {
        Column(Modifier.width(320.dp)) {
            TvImage(
                url = rank.cover(600),
                modifier = Modifier.size(280.dp),
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.height(20.dp))
            BasicText(
                text = rank.name,
                style = TvText.h1.copy(fontSize = 26.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = if (loading) "正在加载…" else "${songs.size} 首 · ${rank.updateHint}",
                style = TvText.body,
            )
            Spacer(Modifier.height(22.dp))
            TvButton(
                text = "播放全部",
                primary = true,
                onClick = {
                    songs.firstOrNull()?.let { first ->
                        PlayerManager.play(context, first, songs)
                        onOpenPlayer()
                    }
                },
                modifier = Modifier.width(180.dp).height(52.dp),
            )
        }

        Spacer(Modifier.width(32.dp))

        if (songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(if (loading) "加载中…" else "这个榜单暂时没有数据", style = TvText.body)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
                    SongRow(
                        index = index,
                        name = song.name,
                        singer = song.singer,
                        cover = song.cover(160),
                        onClick = {
                            PlayerManager.play(context, song, songs)
                            onOpenPlayer()
                        },
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    )
                }
            }
        }
    }
}

private val SquareCategories = listOf(
    "推荐" to 0, "流行" to 9, "经典" to 12, "粤语" to 19,
    "英语" to 20, "日语" to 21, "韩语" to 23, "摇滚" to 29,
)

/** 歌单广场：顶部分类 + 下方网格。 */
@Composable
fun TvSquareScreen(onOpenPlaylist: (PlaylistBrief) -> Unit) {
    var category by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<PlaylistBrief>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(category) {
        loading = true
        items = runCatching {
            KugouApi.playlistSquare(page = 1, pageSize = 30, categoryId = SquareCategories[category].second)
        }.getOrNull()?.list ?: emptyList()
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp)) {
        BasicText(
            text = "歌单广场",
            style = TvText.h1,
            modifier = Modifier.padding(start = 8.dp, bottom = 14.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SquareCategories.forEachIndexed { i, (label, _) ->
                FocusSurface(
                    onClick = { category = i },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    scaleUp = 1.08f,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                if (i == category) TvColor.accentDeep else TvColor.card,
                            )
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = label,
                            style = TvText.h3.copy(
                                fontSize = 15.sp,
                                color = if (i == category) TvColor.text else TvColor.textDim,
                            ),
                        )
                    }
                }
            }
        }

        when {
            loading -> CenterHint("加载中…")
            items.isEmpty() -> CenterHint("这个分类暂时没有歌单")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    FocusSurface(
                        onClick = { onOpenPlaylist(item) },
                        modifier = Modifier.fillMaxWidth().height(250.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column {
                            TvImage(
                                url = item.cover(400),
                                modifier = Modifier.fillMaxWidth().height(190.dp),
                                shape = RoundedCornerShape(16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            BasicText(
                                text = item.name,
                                style = TvText.h3.copy(fontSize = 14.sp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text, style = TvText.body)
    }
}
