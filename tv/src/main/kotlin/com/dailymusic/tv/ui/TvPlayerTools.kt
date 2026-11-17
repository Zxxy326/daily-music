package com.dailymusic.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.LyricPrefs
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SleepTimer

/**
 * 播放页工具面板：队列 / 音质 / 定时 / 更多。
 *
 * 用覆盖层而不是 Dialog：TV 上弹层的焦点进出很难控制，覆盖层作为 Box 的最后一层，
 * 和播放页共用一套遥控器焦点，返回键交给 RootScreen 的 BackHandler 分层处理。
 */

@Composable
fun TvPlayerToolPanel(tool: PlayerTool, onClose: () -> Unit, onOpenQueue: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxHeight(0.82f)
                .width(760.dp)
                .background(TvColor.bg, RoundedCornerShape(24.dp))
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val title = when (tool) {
                        PlayerTool.Queue -> "播放队列"
                        PlayerTool.Quality -> "音质选择"
                        PlayerTool.Sleep -> "睡眠定时"
                        PlayerTool.More -> "更多"
                    }
                    BasicText(title, style = TvText.h2)
                    val subtitle = when (tool) {
                        PlayerTool.Queue -> "${PlayerManager.queue.size} 首 · 点按跳歌"
                        PlayerTool.Quality -> PlayerManager.actualQualityLabel.ifBlank { "选择后从下一首生效" }
                        PlayerTool.Sleep -> if (SleepTimer.active) "进行中 · ${SleepTimer.label}" else "到点自动暂停播放"
                        PlayerTool.More -> PlayerManager.current?.let { "${it.name} · ${it.singer}" } ?: ""
                    }
                    BasicText(subtitle, style = TvText.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(16.dp))
                TvButton("关闭", primary = false, onClick = onClose, modifier = Modifier.width(130.dp).height(50.dp))
            }

            Spacer(Modifier.height(20.dp))

            when (tool) {
                PlayerTool.Queue -> QueuePanel(onPicked = onClose)
                PlayerTool.Quality -> QualityPanel()
                PlayerTool.Sleep -> SleepPanel(onPicked = onClose)
                PlayerTool.More -> MorePanel(onClose = onClose, onOpenQueue = onOpenQueue)
            }
        }
    }
}

@Composable
private fun ColumnScope.QueuePanel(onPicked: () -> Unit) {
    val context = LocalContext.current
    val queue = PlayerManager.queue
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            BasicText("队列是空的", style = TvText.body)
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(queue, key = { _, s -> s.key }) { index, song ->
            val isCurrent = index == PlayerManager.index
            OptionRow(
                title = song.name,
                subtitle = "${index + 1}. ${song.singer}",
                selected = isCurrent,
                badge = if (isCurrent && PlayerManager.isPlaying) "播放中" else null,
            ) {
                // 用整条队列重新开播，不 seek 下标：队列被 enqueueNext 改过之后下标容易对不上。
                PlayerManager.play(context, song, queue)
                onPicked()
            }
        }
    }
}

private val QUALITY_OPTIONS = listOf(
    KugouApi.Q_128 to "标准 128k",
    KugouApi.Q_320 to "高品质 320k",
    KugouApi.Q_FLAC to "无损 FLAC",
    KugouApi.Q_HIRES to "Hi-Res",
    KugouApi.Q_VIPER_TAPE to "蝰蛇母带",
)

@Composable
private fun QualityPanel() {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(
            "无损 / Hi-Res / 母带需要会员权益，实际档位会按账号自动降级。",
            style = TvText.caption,
        )
        QUALITY_OPTIONS.forEach { (code, label) ->
            OptionRow(
                title = label,
                subtitle = if (code == PlayerManager.quality) "当前选择" else null,
                selected = code == PlayerManager.quality,
            ) { PlayerManager.setQuality(context, code) }
        }
    }
}

@Composable
private fun SleepPanel(onPicked: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(15, 30, 45, 60, 90).forEach { minutes ->
            OptionRow(title = "$minutes 分钟", subtitle = null, selected = false) {
                SleepTimer.start(minutes)
                onPicked()
            }
        }
        OptionRow(
            title = "播完本曲",
            subtitle = "当前这首歌放完就停",
            selected = SleepTimer.untilTrackEnd,
        ) {
            SleepTimer.startUntilTrackEnd()
            onPicked()
        }
        if (SleepTimer.active) {
            OptionRow(title = "关闭定时", subtitle = null, selected = false) {
                SleepTimer.cancel()
                onPicked()
            }
        }
    }
}

@Composable
private fun ColumnScope.MorePanel(onClose: () -> Unit, onOpenQueue: () -> Unit) {
    val context = LocalContext.current
    val song = PlayerManager.current ?: run {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            BasicText("没有正在播放的歌曲", style = TvText.body)
        }
        return
    }
    val favorite = Library.isFavorite(song.key)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OptionRow(
            title = if (favorite) "取消收藏" else "收藏这首歌",
            subtitle = if (favorite) "从「我喜欢」移除" else "加入本机「我喜欢」",
            selected = favorite,
        ) { Library.toggleFavorite(song) }
        OptionRow(title = "播放队列", subtitle = "${PlayerManager.queue.size} 首", selected = false) { onOpenQueue() }
        SongInfoRows(song)

        Spacer(Modifier.height(6.dp))
        BasicText("歌词显示", style = TvText.h3)
        BasicText("对齐", style = TvText.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LyricPrefs.Align.entries.forEach { align ->
                SmallChip(
                    label = align.label,
                    selected = LyricPrefs.align == align,
                ) { LyricPrefs.setAlign(context, align) }
            }
        }
        BasicText("字号", style = TvText.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LyricPrefs.Size.entries.forEach { size ->
                SmallChip(
                    label = size.label,
                    selected = LyricPrefs.size == size,
                ) { LyricPrefs.setSize(context, size) }
            }
        }
    }
}

@Composable
private fun SongInfoRows(song: Song) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("歌曲信息", style = TvText.h3)
        val playing = PlayerManager.current?.key == song.key
        val quality = if (playing && PlayerManager.actualQualityLabel.isNotBlank()) {
            PlayerManager.actualQualityLabel
        } else {
            "${song.bitrate} kbps"
        }
        BasicText("专辑：${song.albumName.ifBlank { "—" }}", style = TvText.caption)
        BasicText("时长：${song.durationText} · 音质：$quality", style = TvText.caption)
    }
}

/** 通用选项行：标题 + 副标题 + 选中态，整行一个焦点。 */
@Composable
private fun OptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(12.dp),
        scaleUp = 1.02f,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(if (selected) TvColor.accentDeep else TvColor.card)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    title,
                    style = TvText.h3.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    BasicText(subtitle, style = TvText.caption)
                }
            }
            if (badge != null) {
                BasicText(badge, style = TvText.caption.copy(color = TvColor.accent))
            } else if (selected) {
                BasicText("✓", style = TvText.h3.copy(color = TvColor.accent))
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(42.dp),
        shape = RoundedCornerShape(99.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (selected) TvColor.accent else TvColor.card)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = TvText.body.copy(
                    fontSize = 13.sp,
                    color = if (selected) TvColor.onAccent else TvColor.text,
                ),
            )
        }
    }
}
