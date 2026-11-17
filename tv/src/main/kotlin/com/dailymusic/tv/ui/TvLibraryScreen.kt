package com.dailymusic.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/**
 * 曲库：我喜欢 + 最近播放。
 *
 * 都用横向卡片而不是纵向列表 —— 电视上一屏横向能扫 5~6 张，
 * 纵向列表一屏只能看 4 行，翻起来累。
 */
@Composable
fun TvLibraryScreen(onOpenPlayer: () -> Unit, onOpenMyPlaylists: () -> Unit = {}) {
    val context = LocalContext.current
    val favorites = Library.favorites
    val history = Library.history.map { it.song }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 28.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item {
            BasicText(
                text = "曲库",
                style = TvText.h1,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // 账号歌单入口：TV 没有独立的「我的」页，归到曲库最自然。
        item {
            FocusSurface(
                onClick = onOpenMyPlaylists,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(TvColor.card, RoundedCornerShape(18.dp))
                        .padding(horizontal = 24.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.ListBullet),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvColor.accent),
                    )
                    Spacer(Modifier.width(20.dp))
                    Column {
                        BasicText("我的歌单", style = TvText.h2)
                        BasicText("我创建的 · 我收藏的", style = TvText.caption)
                    }
                }
            }
        }

        item {
            SectionHeader("我喜欢", if (favorites.isEmpty()) "还没有收藏" else "${favorites.size} 首")
            if (favorites.isEmpty()) {
                EmptyHint("在手机上点过红心的歌会出现在这里")
            } else {
                SongStrip(favorites) { song ->
                    PlayerManager.play(context, song, favorites)
                    onOpenPlayer()
                }
            }
        }

        item {
            SectionHeader("最近播放", if (history.isEmpty()) "暂无记录" else "${history.size} 首")
            if (history.isEmpty()) {
                EmptyHint("播放过的歌会自动记录")
            } else {
                SongStrip(history) { song ->
                    PlayerManager.play(context, song, history)
                    onOpenPlayer()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        BasicText(title, style = TvText.h2)
        BasicText(subtitle, style = TvText.caption, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun EmptyHint(text: String) {
    BasicText(
        text = text,
        style = TvText.body,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun SongStrip(songs: List<Song>, onPlay: (Song) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(songs, key = { it.key }) { song ->
            FocusSurface(
                onClick = { onPlay(song) },
                modifier = Modifier.width(168.dp).height(240.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column {
                    TvImage(
                        url = song.cover(360),
                        modifier = Modifier.fillMaxWidth().height(168.dp),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = song.name,
                        style = TvText.h3.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    BasicText(
                        text = song.singer,
                        style = TvText.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
