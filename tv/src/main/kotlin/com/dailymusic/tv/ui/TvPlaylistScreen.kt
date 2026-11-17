package com.dailymusic.tv.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/**
 * 歌单详情：左封面 + 右曲目列表。
 *
 * 左栏固定不滚，右栏独立滚动 —— 电视上焦点在列表里上下走时，
 * 封面和「播放全部」要一直留在视野里，用户才知道自己在听哪个歌单。
 */
@Composable
fun TvPlaylistScreen(
    playlist: PlaylistBrief,
    onOpenPlayer: () -> Unit,
    /**
     * 账号里的私有歌单（我创建的 / 我收藏的）传 listid：走 `/playlist/track/all/new`。
     * 广场歌单不传，走 `playlist.trackKey`（gcid）。两者混用会拿到空列表。
     */
    listid: String? = null,
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(playlist.id, listid) {
        loading = true
        songs = runCatching {
            if (listid != null) {
                KugouApi.myPlaylistSongs(listid)
                    ?.mapNotNull { Song.fromPlaylistFile(it) }
                    .orEmpty()
            } else {
                KugouApi.playlistSongs(playlist.trackKey, page = 1, pageSize = 60)
            }
        }.getOrDefault(emptyList())
        loading = false
    }

    Row(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp)) {
        Column(Modifier.width(320.dp).fillMaxHeight()) {
            // 私有歌单的封面字段经常是账号头像，不可信；用第一首歌的专辑图代替。
            TvImage(
                url = if (listid != null) {
                    songs.firstOrNull()?.cover(600) ?: playlist.cover(600).takeIf { it.isNotBlank() }
                } else {
                    playlist.cover(600)
                },
                modifier = Modifier.size(280.dp),
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.height(20.dp))
            BasicText(
                text = playlist.name,
                style = TvText.h1.copy(fontSize = 26.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            BasicText(
                text = if (loading) "正在加载…" else "${songs.size} 首 · ${playlist.collectText}",
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
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                BasicText(
                    text = if (loading) "加载中…" else "这个歌单里没有能播放的歌曲",
                    style = TvText.body,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
