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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate

/**
 * 每日推荐。
 *
 * 数据源和首页「为你推荐」是同一个接口（`/everyday_song_recommend`），
 * 区别是这里给完整列表 + 「播放全部」——首页那份只露前几首做预览。
 * 每天 0 点后上游会换一批，所以标题上带日期，用户能看出今天有没有刷新。
 */
@Composable
fun DailyScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        songs = runCatching { KugouApi.recommend() }.getOrDefault(emptyList())
        loading = false
    }

    val currentKey = PlayerManager.current?.key
    val playing = PlayerManager.isPlaying
    val today = remember { LocalDate.now() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            SubPageHeader(
                title = "每日推荐",
                subtitle = "${today.monthValue} 月 ${today.dayOfMonth} 日 · 每天 0 点更新",
                onBack = onBack,
            )
        }

        if (songs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.primary)
                        .clickable { onPlay(songs.first(), songs) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TintedIcon(AppIcons.Play, colors.onPrimary, 15.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "播放全部 · ${songs.size} 首",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimary,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        when {
            loading -> item { LoadingHint("正在拉取今日推荐…") }
            songs.isEmpty() -> item { EmptyHint("今天没拿到推荐，可能是登录态失效了", "重试") { attempt++ } }
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
