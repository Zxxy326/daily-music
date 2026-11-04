package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新歌速递。
 *
 * 概念版这个页面的分区是「全部 / 华语 / 欧美 / 韩国 / 日本」，对应上游的几个榜单：
 * 全部走新歌榜（74534），地区走内地 / 欧美 / 韩国 / 日本榜。
 * 没有单独的新歌接口，用榜单拼是最接近的做法。
 */
private data class NewSongTab(val label: String, val rankId: Long)

private val NewSongTabs = listOf(
    NewSongTab("全部", 74534L),
    NewSongTab("华语", 31308L),
    NewSongTab("欧美", 31310L),
    NewSongTab("韩国", 31311L),
    NewSongTab("日本", 31312L),
)

@Composable
fun NewSongScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
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

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item { SubPageHeader(title = "新歌速递", subtitle = "每天更新", onBack = onBack) }

        // 分区：横向一排胶囊，选中态用主题色底
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(NewSongTabs.size) { i ->
                    val on = i == tabIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) colors.primary else colors.surfaceContainer)
                            .clickable { tabIndex = i }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = NewSongTabs[i].label,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) colors.onPrimary else colors.onSurfaceSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 全部播放
        if (songs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onPlay(songs.first(), songs) }
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            TintedIcon(AppIcons.Play, colors.onPrimary, 13.dp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "全部播放",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${songs.size}",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        when {
            loading -> item { LoadingHint("正在拉取新歌…") }
            songs.isEmpty() -> item { EmptyHint("没有拿到新歌数据", "重试") { attempt++ } }
            else -> itemsIndexed(songs, key = { _, s -> s.key }) { _, song ->
                SongRow(
                    song = song,
                    isCurrent = song.key == currentKey,
                    isPlaying = playing,
                    onClick = { onPlay(song, songs) },
                    showFavorite = true,
                    favorite = Library.isFavorite(song.key),
                    onToggleFavorite = { Library.toggleFavorite(song) },
                )
            }
        }
    }
}
