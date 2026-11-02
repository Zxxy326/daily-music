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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌手页。
 *
 * 酷狗那套接口里**没有**「按歌手取全部作品」的端点（Rust 服务只透传了 `/search`），
 * 所以这里用「按歌手名多翻几页搜索 + 过滤出歌手完全匹配的歌」来拼一个作品集。
 * 拿不到的东西不硬编：页面上标了「来自搜索结果」，不假装是官方歌手主页。
 */
@Composable
fun ArtistScreen(
    name: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme

    var songs by remember(name) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(name) { mutableStateOf(true) }
    var albumFilter by remember(name) { mutableStateOf<String?>(null) }

    LaunchedEffect(name) {
        loading = true
        val all = ArrayList<Song>()
        for (page in 1..3) {
            val chunk = runCatching { KugouApi.search(name, page = page) }.getOrDefault(emptyList())
            if (chunk.isEmpty()) break
            all += chunk
        }
        // 只留这位歌手的歌；一首都没匹配上（比如英文名大小写、合作署名）就退回全部结果，
        // 总比空页面强。
        val mine = all
            .filter { song -> song.singer.split("/", "、", ",", "&").any { it.trim().equals(name, true) } }
            .distinctBy { it.key }
        songs = mine.ifEmpty { all.distinctBy { it.key } }
        loading = false
    }

    // 专辑：按专辑名分组，张数多的排前面
    val albums = remember(songs) {
        songs.groupBy { it.albumName.trim().ifBlank { "未知专辑" } }
            .entries
            .sortedByDescending { it.value.size }
    }
    val shown = albumFilter?.let { f ->
        songs.filter { it.albumName.trim().ifBlank { "未知专辑" } == f }
    } ?: songs

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item { SubPageHeader(title = name, subtitle = "歌手", onBack = onBack) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    val avatar = songs.firstOrNull()?.cover(300)
                    if (avatar != null) {
                        RemoteImage(
                            url = avatar,
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            showFallbackIcon = false,
                        )
                    } else {
                        TintedIcon(AppIcons.Person, colors.onSurfaceVariantSummary, 34.dp)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (loading) "加载中…" else "${songs.size} 首 · ${albums.size} 张专辑",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "来自搜索结果",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary.copy(alpha = 0.7f),
                    )
                }
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text("正在整理作品…", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                }
            }
        }

        if (!loading && albums.size > 1) {
            item { SectionTitle("专辑", trailing = "${albums.size} 张") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { it.key }) { entry ->
                        val on = albumFilter == entry.key
                        Column(
                            modifier = Modifier
                                .width(112.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { albumFilter = if (on) null else entry.key }
                                .background(if (on) colors.surfaceContainerHigh else colors.surfaceContainer)
                                .padding(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(96.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surfaceContainerHigh),
                            ) {
                                val cover = entry.value.firstOrNull()?.cover(300)
                                if (cover != null) {
                                    RemoteImage(
                                        url = cover,
                                        modifier = Modifier.fillMaxSize(),
                                        shape = RoundedCornerShape(12.dp),
                                        showFallbackIcon = true,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = entry.key,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${entry.value.size} 首",
                                fontSize = 10.5.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionTitle(
                if (albumFilter != null) albumFilter!! else "全部歌曲",
                trailing = if (albumFilter != null) "再点专辑取消" else "${shown.size} 首",
            )
        }

        items(shown, key = { it.key }) { song ->
            SongRow(
                song = song,
                isCurrent = song.key == currentKey,
                isPlaying = playing,
                onClick = { onPlay(song, shown) },
                showFavorite = true,
                favorite = Library.isFavorite(song.key),
                onToggleFavorite = { Library.toggleFavorite(song) },
            )
        }
    }
}
