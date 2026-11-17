package com.dailymusic.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.FeedModule
import com.dailymusic.app.data.PlaylistBrief
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager

/**
 * TV 首页的推荐流（对齐手机端首页）：
 *  - 推荐歌单：官方推荐位横滑大卡，点进歌单详情。
 *  - 概念版推荐模块（红心 Radio / 私人专属好歌 / 曲风盲盒 …）：横滑歌曲小卡，点按整模块播放。
 *
 * 卡片统一用 FocusSurface：遥控器聚焦放大 + 描边，和全 App 焦点规则一致。
 */

/** 推荐歌单横滑：大封面 + 播放量徽章 + 两行文字。 */
@Composable
fun TvRecommendPlaylists(
    playlists: List<PlaylistBrief>,
    onOpenPlaylist: (PlaylistBrief) -> Unit,
) {
    if (playlists.isEmpty()) return
    SectionTitle(title = "推荐歌单", subtitle = "酷狗官方推荐 · ${playlists.size} 个歌单")
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp),
    ) {
        items(playlists, key = { it.id }) { pl ->
            PlaylistCard(pl) { onOpenPlaylist(pl) }
        }
    }
}

/** 一个推荐模块的横滑歌曲卡区。 */
@Composable
fun TvFeedModuleRow(
    module: FeedModule,
    onPlay: (Song, List<Song>) -> Unit,
) {
    if (module.songs.isEmpty()) return
    SectionTitle(
        title = module.title,
        subtitle = module.subtitle.ifBlank { "${module.songs.size} 首" },
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 8.dp),
    ) {
        items(module.songs, key = { it.key }) { song ->
            FeedSongCard(song) { onPlay(song, module.songs) }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 2.dp)) {
        BasicText(title, style = TvText.h2)
        BasicText(subtitle, style = TvText.caption, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 推荐歌单大卡：200dp 封面 + 播放量徽章，对齐手机端 PlaylistCard。 */
@Composable
private fun PlaylistCard(pl: PlaylistBrief, onClick: () -> Unit) {
    val width = 200.dp
    FocusSurface(onClick = onClick, modifier = Modifier.width(width), shape = RoundedCornerShape(20.dp)) {
        Column {
            Box(Modifier.size(width)) {
                TvImage(pl.cover(400), Modifier.fillMaxSize(), RoundedCornerShape(20.dp))
                // 底部压一层渐变，保证播放量白字在浅色封面上也读得出来
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.6f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.65f),
                            ),
                        ),
                )
                val playText = pl.playCountText
                if (playText.isNotBlank()) {
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(99.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.Play),
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                        )
                        Spacer(Modifier.width(5.dp))
                        BasicText(playText, style = TvText.caption.copy(color = Color.White, fontSize = 12.sp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = pl.name,
                style = TvText.h3,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            BasicText(pl.collectText, style = TvText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 推荐模块里的歌曲小卡：150dp 封面 + 歌名 + 歌手。 */
@Composable
private fun FeedSongCard(song: Song, onClick: () -> Unit) {
    val width = 160.dp
    FocusSurface(onClick = onClick, modifier = Modifier.width(width), shape = RoundedCornerShape(18.dp)) {
        Column {
            TvImage(
                url = song.cover(300),
                modifier = Modifier.size(width),
                shape = RoundedCornerShape(18.dp),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(10.dp))
            BasicText(song.name, style = TvText.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            BasicText(song.singer, style = TvText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
