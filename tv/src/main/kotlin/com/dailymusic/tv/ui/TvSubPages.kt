package com.dailymusic.tv.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.MyPlaylist
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import java.time.LocalDate

/**
 * TV 二级内容页（每日推荐 / 新歌速递 / 我的歌单 / 歌手）。
 *
 * 四页共用同一套骨架：大标题 + 副标题 + 播放全部 + 可滚动歌曲列表。
 * 抽出 [SongListBody] 让遥控器焦点规则、加载/空态在四页之间完全一致 ——
 * 电视上「焦点行为不一致」比「样式不统一」更让人困惑。
 */

@Composable
fun TvDailyScreen(onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        songs = runCatching { KugouApi.recommend() }.getOrDefault(emptyList())
        loading = false
    }

    val today = remember { LocalDate.now() }
    SongListBody(
        title = "每日推荐",
        subtitle = "${today.monthValue} 月 ${today.dayOfMonth} 日 · 每天 0 点更新",
        songs = songs,
        loading = loading,
        emptyText = "今天没拿到推荐，可能是登录态失效了",
        onRetry = { attempt++ },
        onPlay = { song, list ->
            PlayerManager.play(context, song, list)
            onOpenPlayer()
        },
    )
}

private data class NewSongTab(val label: String, val rankId: Long)

/** 新歌没有独立接口，用几个地区新歌榜拼，和手机端一致。 */
private val NewSongTabs = listOf(
    NewSongTab("全部", 74534L),
    NewSongTab("华语", 31308L),
    NewSongTab("欧美", 31310L),
    NewSongTab("韩国", 31311L),
    NewSongTab("日本", 31312L),
)

@Composable
fun TvNewSongsScreen(onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(tabIndex, attempt) {
        loading = true
        songs = runCatching { KugouApi.rankSongs(NewSongTabs[tabIndex].rankId) }
            .getOrDefault(emptyList())
        loading = false
    }

    SongListBody(
        title = "新歌速递",
        subtitle = "每天更新 · ${NewSongTabs[tabIndex].label}",
        songs = songs,
        loading = loading,
        emptyText = "这个分区暂时没有新歌",
        onRetry = { attempt++ },
        onPlay = { song, list ->
            PlayerManager.play(context, song, list)
            onOpenPlayer()
        },
        headerExtra = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NewSongTabs.forEachIndexed { i, tab ->
                    TabChip(
                        label = tab.label,
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                    )
                }
            }
        },
    )
}

/**
 * 歌手页。
 *
 * 酷狗没有「按歌手取全部作品」的端点，用「按歌手名搜 3 页 + 过滤出歌手匹配的歌」
 * 拼一份作品集 —— 和手机端一致。搜不到匹配就退回全部结果，总比空页强。
 */
@Composable
fun TvArtistScreen(name: String, onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(name, attempt) {
        loading = true
        val all = ArrayList<Song>()
        for (page in 1..3) {
            val chunk = runCatching { KugouApi.search(name, page = page) }.getOrDefault(emptyList())
            if (chunk.isEmpty()) break
            all += chunk
        }
        val mine = all
            .filter { song ->
                song.singer.split("/", "、", ",", "&").any { it.trim().equals(name, true) }
            }
            .distinctBy { it.key }
        songs = mine.ifEmpty { all.distinctBy { it.key } }
        loading = false
    }

    SongListBody(
        title = name,
        subtitle = if (loading) "正在查找作品…" else "热门歌曲 · ${songs.size} 首（来自搜索结果）",
        songs = songs,
        loading = loading,
        emptyText = "没找到「$name」的作品",
        onRetry = { attempt++ },
        onPlay = { song, list ->
            PlayerManager.play(context, song, list)
            onOpenPlayer()
        },
    )
}

/**
 * 我的歌单：我创建的 + 我收藏的。
 *
 * 数据全部来自账号（`/user/playlist`），换设备登录看到的是同一份，不做本地缓存。
 * TV 端没有登录页（C 组），未登录时明确提示，不给用户一个空页面。
 */
@Composable
fun TvMyPlaylistsScreen(onOpenPlaylist: (MyPlaylist) -> Unit) {
    var items by remember { mutableStateOf<List<MyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        if (!Auth.isLoggedIn) {
            items = emptyList()
            error = "登录后才能同步账号里的歌单"
            loading = false
            return@LaunchedEffect
        }
        val list = runCatching { KugouApi.myPlaylists() }.getOrNull()
        if (list == null) {
            error = "拉取歌单失败，检查网络后重试"
            items = emptyList()
        } else {
            items = list
        }
        loading = false
    }

    val created = items.filter { it.created }
    val collected = items.filter { !it.created }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                BasicText("我的歌单", style = TvText.h1)
                BasicText(
                    text = if (loading) "正在同步账号歌单…" else "${items.size} 个 · 来自账号",
                    style = TvText.body,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        when {
            loading -> item { Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                BasicText("正在加载…", style = TvText.body)
            } }

            error != null -> item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 50.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(error!!, style = TvText.body)
                    // 未登录时「重试」没有意义：TV 端还没有登录页（C 组），给不了出口。
                    if (Auth.isLoggedIn) {
                        Spacer(Modifier.height(18.dp))
                        TvButton("重试", primary = false, onClick = { attempt++ }, modifier = Modifier.width(140.dp).height(48.dp))
                    }
                }
            }

            items.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    BasicText("账号里还没有歌单", style = TvText.body)
                }
            }

            else -> {
                if (created.isNotEmpty()) {
                    item { BasicText("我创建的 · ${created.size} 个", style = TvText.h2) }
                    item { PlaylistStrip(created, onOpenPlaylist) }
                }
                if (collected.isNotEmpty()) {
                    item { BasicText("我收藏的 · ${collected.size} 个", style = TvText.h2) }
                    item { PlaylistStrip(collected, onOpenPlaylist) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistStrip(playlists: List<MyPlaylist>, onClick: (MyPlaylist) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(playlists, key = { it.listid }) { p ->
            FocusSurface(
                onClick = { onClick(p) },
                modifier = Modifier.width(180.dp).height(250.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column {
                    TvImage(
                        url = p.cover(360).takeIf { it.isNotBlank() },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        p.name,
                        style = TvText.h3.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    BasicText(
                        "${p.songCount} 首",
                        style = TvText.caption,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** 二级页通用骨架：标题区（可插标签）+ 播放全部 + 歌曲列表。 */
@Composable
private fun SongListBody(
    title: String,
    subtitle: String,
    songs: List<Song>,
    loading: Boolean,
    emptyText: String,
    onRetry: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val currentKey = PlayerManager.current?.key
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                BasicText(title, style = TvText.h1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(subtitle, style = TvText.body, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (headerExtra != null) {
            item { headerExtra() }
        }
        if (songs.isNotEmpty()) {
            item {
                TvButton(
                    text = "播放全部 · ${songs.size} 首",
                    primary = true,
                    onClick = { onPlay(songs.first(), songs) },
                    modifier = Modifier.width(240.dp).height(52.dp),
                )
            }
        }
        when {
            loading -> item { CenterHint("正在加载…") }
            songs.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText(emptyText, style = TvText.body)
                    Spacer(Modifier.height(18.dp))
                    TvButton("重试", primary = false, onClick = onRetry, modifier = Modifier.width(140.dp).height(48.dp))
                }
            }
            else -> itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
                SongRow(
                    index = index,
                    name = song.name,
                    singer = song.singer,
                    cover = song.cover(160),
                    onClick = { onPlay(song, songs) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .then(
                            if (song.key == currentKey) {
                                Modifier.background(TvColor.cardHigh, RoundedCornerShape(14.dp))
                            } else Modifier
                        ),
                )
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        scaleUp = 1.08f,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (selected) TvColor.accent else TvColor.card)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = TvText.h3.copy(
                    fontSize = 15.sp,
                    color = if (selected) TvColor.onAccent else TvColor.textDim,
                ),
            )
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = TvText.body)
    }
}
