package com.dailymusic.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailymusic.app.data.Auth
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SleepTimer
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * 播放相关的三个底部弹层：队列 / 睡眠定时 / 更多。
 *
 * 都用 [Dialog] 而不是在 RootScreen 的 Box 里叠一层：Dialog 有独立的 window，
 * 天然盖在播放页、歌词页之上，不需要把 `showSheet` 这类状态往上传。
 * 代价是要自己画遮罩和底部卡片（`usePlatformDefaultWidth = false` 之后
 * Dialog 不再提供默认尺寸）。
 */

@Composable
private fun SheetScaffold(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()

    // 弹层铺满全屏之后，状态栏图标仍按「浅色主题 = 深色图标」画，压在深色遮罩上就看不见了。
    // 打开期间把图标强制成白色，关掉再恢复。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        onDispose {
            // 用外面捕获的 dark：onDispose 不是 @Composable，不能在里面调 isSystemInDarkTheme()
            controller?.isAppearanceLightStatusBars = !dark
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false：让弹层的窗口铺到状态栏/导航栏下面，
        // 遮罩才能盖住状态栏（否则顶部会留一条没被压暗的缝，看着像两层窗口）。
        // 内容自己用 navigationBarsPadding 让开手势条。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // 遮罩。Dialog 自带一层 dim，这里再加一点让底下的播放页退得更干净。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(if (dark) Color(0xFF1C1C1E) else Color.White)
                    .navigationBarsPadding()
                    // 空 clickable 吃掉点击，否则点卡片会穿透到遮罩把弹层关掉。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(top = 10.dp, bottom = 12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SheetHandle() {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(colors.onSurface.copy(alpha = 0.16f)),
        )
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val tint = when {
        danger -> colors.error
        selected -> colors.primary
        else -> colors.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = if (selected) 0.14f else 0.07f)),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(icon, tint, 18.dp)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = tint)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) TintedIcon(AppIcons.Check, colors.primary, 18.dp)
    }
}

// ==================== 睡眠定时 ====================

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60, 90)

    SheetScaffold(onDismiss) {
        SheetHandle()
        SheetTitle(
            title = "睡眠定时",
            // 倒计时进行中时这里实时跳秒 —— 用户能立刻确认「真的设上了」。
            subtitle = if (SleepTimer.active) SleepTimer.label else null,
        )
        options.forEach { minutes ->
            SheetRow(
                icon = AppIcons.Clock,
                title = "$minutes 分钟",
                onClick = {
                    SleepTimer.start(minutes)
                    onDismiss()
                },
            )
        }
        SheetRow(
            icon = AppIcons.MusicNote,
            title = "播完本曲",
            subtitle = "当前这首歌放完就停",
            selected = SleepTimer.untilTrackEnd,
            onClick = {
                SleepTimer.startUntilTrackEnd()
                onDismiss()
            },
        )
        if (SleepTimer.active) {
            SheetRow(
                icon = AppIcons.Close,
                title = "关闭定时",
                danger = true,
                onClick = {
                    SleepTimer.cancel()
                    onDismiss()
                },
            )
        }
    }
}

// ==================== 播放队列 ====================

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val queue = PlayerManager.queue
    val currentIndex = PlayerManager.index

    SheetScaffold(onDismiss) {
        SheetHandle()
        SheetTitle(title = "播放队列", subtitle = "${queue.size} 首")

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "队列是空的",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                itemsIndexed(queue, key = { _, s -> s.key }) { i, song ->
                    QueueRow(
                        song = song,
                        playing = i == currentIndex,
                        isPlaying = PlayerManager.isPlaying,
                        onClick = {
                            // 用整条队列重新开播，而不是 seek 到某个下标 ——
                            // 后者在队列被 enqueueNext 改过之后下标容易对不上。
                            PlayerManager.play(context, song, queue)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(song: Song, playing: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = song.cover(120),
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (playing) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.singer,
                fontSize = 11.5.sp,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (playing) {
            TintedIcon(
                icon = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                tint = colors.primary,
                size = 16.dp,
            )
        } else {
            Text(
                text = song.durationText,
                fontSize = 11.5.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

// ==================== 更多 ====================

@Composable
fun MoreSheet(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showInfo by remember { mutableStateOf(false) }
    val favorite = Library.isFavorite(song.key)

    SheetScaffold(onDismiss) {
        SheetHandle()
        if (showInfo) {
            SheetTitle(title = "歌曲信息")
            InfoRow("歌名", song.name)
            InfoRow("歌手", song.singer)
            InfoRow("专辑", song.albumName.ifBlank { "—" })
            InfoRow("时长", song.durationText)
            // 别直接显示 song.bitrate：那是搜索接口给的「源文件」元数据，固定 128 kbps mp3。
            // 实际在放的是 Hi-Res / 无损时，这里写着 128 会被当成「音质又被压回去了」。
            // 当前曲就报真实播放音质，其它歌才退回源文件码率。
            val playing = PlayerManager.current?.key == song.key
            InfoRow(
                "音质",
                if (playing && PlayerManager.actualQualityLabel.isNotBlank()) {
                    PlayerManager.actualQualityLabel
                } else {
                    "${song.bitrate} kbps · ${song.extName.ifBlank { "—" }}"
                },
            )
            // 这个大小是搜索接口按默认档（128k mp3）给的，和上面真实播放音质不是一回事，
            // 所以标签写「源文件大小」，免得用户拿 3.7 MB 去质问「Hi-Res 怎么这么小」。
            InfoRow("源文件大小", formatSize(song.fileSize))
            InfoRow("Hash", song.hash.lowercase())
            InfoRow("AlbumAudioId", song.albumAudioId.toString())
            SheetRow(icon = AppIcons.ArrowBack, title = "返回") { showInfo = false }
        } else {
            SheetTitle(title = song.name, subtitle = song.singer)
            SheetRow(
                icon = if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                title = if (favorite) "取消收藏" else "收藏",
                // 说清楚会写到哪里。未登录时只落本机，不能让用户以为已经进账号了。
                subtitle = when {
                    !Auth.isLoggedIn -> "未登录，只存在本机"
                    favorite -> "同时从账号「我喜欢」移除"
                    else -> "同时加入账号「我喜欢」"
                },
                onClick = {
                    Library.toggleFavorite(song)
                    onDismiss()
                },
            )
            // 「下一首播放」只在「这首歌不是当前曲」时才有意义。
            // 从播放页/歌词页打开这个菜单时，对象永远是当前曲 —— 对当前曲谈「排到自己后面」
            // 没有语义（挪位置等于把下一首变成第三首）。所以这里直接不显示，而不是留一个
            // 点了没反应的死项。以后从列表长按进来时它会自然出现。
            if (song.key != PlayerManager.current?.key) {
                SheetRow(
                    icon = AppIcons.QueueMusic,
                    title = "下一首播放",
                    subtitle = if (PlayerManager.queue.getOrNull(PlayerManager.index + 1)?.key == song.key) {
                        "已经是下一首"
                    } else {
                        null
                    },
                    onClick = {
                        PlayerManager.enqueueNext(song)
                        onDismiss()
                    },
                )
            }
            SheetRow(
                icon = AppIcons.ListBullet,
                title = "复制歌曲链接",
                onClick = {
                    copyToClipboard(context, shareUrl(song))
                    onDismiss()
                },
            )
            SheetRow(
                icon = AppIcons.MusicNote,
                title = "歌曲信息",
                onClick = { showInfo = true },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            fontSize = 12.5.sp,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 酷狗网页版能直接打开的分享链接。 */
private fun shareUrl(song: Song): String =
    "https://www.kugou.com/song/#hash=${song.hash.lowercase()}&album_id=${song.albumId}"

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("歌曲链接", text))
    Toast.makeText(context, "链接已复制", Toast.LENGTH_SHORT).show()
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
