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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Rank
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌单（榜单）详情页。
 *
 * 进入即拉取整单曲目；「播放全部」把整单交给播放器当队列，所以从这里开始播放
 * 之后，通知栏的上一首/下一首和自动续播都是完整的。
 */
@Composable
fun PlaylistScreen(
    rank: Rank,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var songs by remember(rank.id) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(rank.id) { mutableStateOf(true) }
    var attempt by remember(rank.id) { mutableIntStateOf(0) }

    LaunchedEffect(rank.id, attempt) {
        loading = true
        songs = KugouApi.rankSongs(rank.id)
        loading = false
    }

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteImage(
                        url = rank.cover(300),
                        modifier = Modifier.size(104.dp),
                        shape = RoundedCornerShape(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = rank.name,
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
                                if (rank.playCountText.isNotBlank()) append(" · ${rank.playCountText}次播放")
                            },
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .height(38.dp)
                                .background(colors.primary, RoundedCornerShape(19.dp))
                                .clip(RoundedCornerShape(19.dp))
                                .clickable(enabled = songs.isNotEmpty()) {
                                    songs.firstOrNull()?.let { onPlay(it, songs) }
                                }
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TintedIcon(AppIcons.Play, colors.onPrimary, 14.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "播放全部",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        if (!loading && songs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("没有拿到曲目", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.clickable { attempt++ },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TintedIcon(AppIcons.Refresh, colors.primary, 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("重试", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.primary)
                    }
                }
            }
        }

        items(songs, key = { it.key }) { song ->
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
