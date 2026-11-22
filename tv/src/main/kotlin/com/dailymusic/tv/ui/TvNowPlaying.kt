package com.dailymusic.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.playback.PlayOrder
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SleepTimer

/** TV 播放页：左封面、右信息 + 控制。 */
@Composable
fun TvNowPlaying(
    onClose: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onOpenQuality: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    onOpenMore: () -> Unit = {},
    onOpenLyricEffect: () -> Unit = {},
) {
    val context = LocalContext.current
    val song = PlayerManager.current

    BackHandler { onClose() }

    if (song == null) {
        Box(Modifier.fillMaxSize().background(TvColor.bg), contentAlignment = Alignment.Center) {
            BasicText("没有正在播放的歌曲", style = TvText.h2)
        }
        return
    }

    val duration = PlayerManager.durationMs.coerceAtLeast(1L)
    val fraction = (PlayerManager.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song.key) {
        lyrics = runCatching { KugouApi.lyric(song) }.getOrNull()
    }
    val anchor = lyrics?.anchorIndex(PlayerManager.positionMs) ?: -1

    Row(
        Modifier
            .fillMaxSize()
            .background(TvColor.bg)
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvImage(
            url = song.cover(720),
            modifier = Modifier.fillMaxHeight(0.86f).aspectRatio(1f),
            shape = RoundedCornerShape(22.dp),
        )

        Spacer(Modifier.width(56.dp))

            Column(Modifier.weight(1f).fillMaxHeight(0.86f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = song.name,
                    style = TvText.h1.copy(fontSize = 36.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                val favorite = Library.isFavorite(song.key)
                IconAction(
                    icon = if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                    size = 52,
                    tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFFF6B8A) else TvColor.textDim,
                ) { Library.toggleFavorite(song) }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = song.singer,
                style = TvText.body.copy(fontSize = 17.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(26.dp))
            LyricWindow(lyrics, anchor)

            Spacer(Modifier.height(32.dp))

            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(color = TvColor.stroke, size = size, cornerRadius = r)
                drawRoundRect(
                    color = TvColor.accent,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = r,
                )
                drawCircle(
                    color = TvColor.text,
                    radius = size.height * 1.3f,
                    center = Offset((size.width * fraction).coerceIn(0f, size.width), size.height / 2),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText(timeText(PlayerManager.positionMs), style = TvText.caption)
                BasicText(timeText(PlayerManager.durationMs), style = TvText.caption)
            }

            Spacer(Modifier.height(28.dp))

            val order = PlayerManager.playOrder
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    icon = when (order) {
                        PlayOrder.LIST -> AppIcons.Repeat
                        PlayOrder.ONE -> AppIcons.RepeatOne
                        PlayOrder.SHUFFLE -> AppIcons.Shuffle
                    },
                    52,
                    tint = if (order == PlayOrder.LIST) TvColor.textDim else TvColor.accent,
                ) { PlayerManager.cyclePlayOrder(context) }
                IconAction(AppIcons.SkipPrevious, 64) { PlayerManager.previous() }
                IconAction(
                    icon = if (PlayerManager.isPlaying) AppIcons.Pause else AppIcons.Play,
                    80,
                ) { PlayerManager.toggle(context) }
                IconAction(AppIcons.SkipNext, 64) { PlayerManager.next() }
            }

            Spacer(Modifier.height(24.dp))

            // 工具行：队列 / 音质 / 定时 / 更多，进二级页操作（TV 不用弹层）。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolChip(text = "队列", onClick = onOpenQueue)
                ToolChip(
                    text = PlayerManager.actualQualityLabel.ifBlank { "音质" },
                    onClick = onOpenQuality,
                )
                ToolChip(
                    text = if (SleepTimer.active) "定时 ${SleepTimer.label}" else "定时",
                    highlight = SleepTimer.active,
                    onClick = onOpenSleep,
                )
                ToolChip(text = "更多", onClick = onOpenMore)
                ToolChip(text = "歌词动效", highlight = true, onClick = onOpenLyricEffect)
            }

        }
    }
}

private fun timeText(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** 播放页工具胶囊：队列 / 音质 / 定时 / 更多。 */
@Composable
private fun ToolChip(text: String, highlight: Boolean = false, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        // 必须给固定宽度：Row 是全宽约束，内部 Box 又是 fillMaxSize，
        // 不设宽度会让 5 个胶囊各占满整行互相重叠，只露出最后一个。
        // 96dp 刚好容纳最长的「歌词动效 / 标准 128k」，5 个横排不挤出屏幕。
        modifier = Modifier.width(96.dp).height(46.dp),
        shape = RoundedCornerShape(99.dp),
        scaleUp = 1.06f,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (highlight) TvColor.accentDeep else TvColor.card)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text,
                style = TvText.body.copy(
                    fontSize = 14.sp,
                    color = if (highlight) TvColor.accent else TvColor.text,
                ),
            )
        }
    }
}


/**
 * 歌词窗口：只显示当前行前后各两行。
 *
 * 电视上整屏滚动的歌词读起来太累（距离远、行数多），
 * 三到五行反而更容易跟。当前行放大 + 提亮，其余压暗。
 */
@Composable
private fun LyricWindow(lyrics: Lyrics?, anchor: Int) {
    val lines = lyrics?.lines
    if (lines.isNullOrEmpty()) {
        BasicText(
            text = if (lyrics == null) "正在加载歌词…" else "纯音乐，请欣赏",
            style = TvText.body,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    // 前奏还没到第一句时 anchor 是 -1，这时也要铺满 5 行，
    // 否则只剩两行、看着像歌词没加载全。
    val from = if (anchor < 0) 0 else (anchor - 2).coerceAtLeast(0)
    val to = if (anchor < 0) 4.coerceAtMost(lines.lastIndex)
    else (anchor + 2).coerceAtMost(lines.lastIndex)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in from..to) {
            val active = i == anchor
            BasicText(
                text = lines[i].text,
                style = TvText.body.copy(
                    fontSize = if (active) 24.sp else 17.sp,
                    fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold
                    else androidx.compose.ui.text.font.FontWeight.Normal,
                    color = if (active) TvColor.text else TvColor.textDim.copy(alpha = 0.55f),
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
