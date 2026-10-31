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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.FavoriteCloud
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 收藏 / 历史 列表页。
 *
 * 两者结构一样（都是一列歌），差别只在数据来源和空态文案，所以共用一个页面 +
 * 顶部切换，而不是写两份几乎相同的列表代码。
 */
@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    initialTab: Int,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    val favoriteSongs = Library.favorites
    val historySongs = Library.history.map { it.song }
    val songs = if (tab == 0) favoriteSongs else historySongs

    Column(Modifier.fillMaxSize()) {
        // ---- 顶部：返回 + 标题 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(AppIcons.ArrowBack, colors.onSurface, 20.dp)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = if (tab == 0) "我的收藏" else "播放历史",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
            )
        }

        // ---- 切换 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("收藏 ${favoriteSongs.size}" to 0, "历史 ${historySongs.size}" to 1).forEach { (label, index) ->
                val selected = tab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .background(
                            if (selected) colors.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(13.dp),
                        )
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { tab = index },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.onSurface else colors.onSurfaceSecondary,
                    )
                }
            }
        }

        // 收藏是唯一和账号有写关系的本地数据，所以状态要说清楚：
        // 未登录 → 只在本机；同步失败 → 直接给重试入口，别让用户以为已经存到账号里了。
        if (tab == 0) {
            val scope = rememberCoroutineScope()
            val error = FavoriteCloud.lastError
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        !Auth.isLoggedIn -> "未登录，收藏只保存在本机"
                        FavoriteCloud.syncing -> "正在与账号同步…"
                        error != null -> error
                        else -> "已与账号「我喜欢」同步"
                    },
                    fontSize = 11.5.sp,
                    color = if (error != null) colors.error else colors.onSurfaceVariantSummary,
                )
                if (Auth.isLoggedIn) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "立即同步",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { scope.launch { Library.syncFromCloud() } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        if (songs.isEmpty()) {
            // 空态要区分「确实没收藏」和「同步没成功」：
            // 前者该引导用户去点红心，后者该让用户点重试，混在一起会让人以为收藏丢了。
            val syncError = if (tab == 0) FavoriteCloud.lastError else null
            EmptyHint(
                title = when {
                    tab != 0 -> "还没有播放记录"
                    syncError != null -> "收藏没同步下来"
                    else -> "还没有收藏"
                },
                body = when {
                    tab != 0 -> "播放过的歌会自动记录在这里，最多保留 300 首。"
                    syncError != null -> syncError
                    !Auth.isLoggedIn -> "登录后会把账号「我喜欢」里的歌同步过来。"
                    else -> "在列表里点歌曲右侧的爱心，就会收进这里。"
                },
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            items(songs, key = { it.key }) { song ->
                SongRow(
                    song = song,
                    isCurrent = PlayerManager.current?.key == song.key,
                    isPlaying = PlayerManager.isPlaying,
                    onClick = { onPlay(song, songs) },
                    showFavorite = true,
                    favorite = Library.isFavorite(song.key),
                    onToggleFavorite = { Library.toggleFavorite(song) },
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, body: String) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TintedIcon(AppIcons.QueueMusic, colors.onSurfaceVariantSummary, 40.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            fontSize = 12.sp,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}
