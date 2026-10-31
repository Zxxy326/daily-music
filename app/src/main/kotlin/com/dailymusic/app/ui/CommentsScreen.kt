package com.dailymusic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dailymusic.app.data.Comment
import com.dailymusic.app.data.CommentPage
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.Song
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 歌曲评论页。
 *
 * 用全屏 [Dialog] 而不是往 RootScreen 里再叠一层 —— 它可能从播放页或歌词页打开，
 * 挂在任何一个页面内部都会被另一个页面盖住（这个坑在歌词页已经踩过一次）。
 *
 * 加载失败和「没有评论」必须分开显示：上游 `mcomment` 接口经常超时，
 * 如果失败也渲染成空列表，用户会以为这首歌真的没人评论。
 */
@Composable
fun CommentsScreen(song: Song, onClose: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = MiuixTheme.colorScheme
    val bg = if (dark) Color(0xFF0B0B0D) else Color(0xFFF2F2F4)

    var page by remember(song.key) { mutableStateOf<CommentPage?>(null) }
    var loading by remember(song.key) { mutableStateOf(true) }
    var failed by remember(song.key) { mutableStateOf(false) }
    var attempt by remember(song.key) { mutableIntStateOf(0) }

    LaunchedEffect(song.key, attempt) {
        loading = true
        failed = false
        val r = KugouApi.musicComments(song, page = 1, pageSize = 30)
        loading = false
        if (r == null) failed = true else page = r
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

                // 顶栏
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(38.dp).clickable(onClick = onClose),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TintedIcon(AppIcons.ArrowBack, colors.onSurface, 22.dp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "评论",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface,
                        )
                        val count = page?.total ?: 0
                        if (count > 0) {
                            Text(
                                text = "$count 条",
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Spacer(Modifier.size(38.dp))
                }

                // 歌名做副标题，避免用户忘了自己在看哪首
                Text(
                    text = "${song.name} · ${song.singer}",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )

                when {
                    loading -> CenterBox {
                        Text(
                            text = "评论加载中…",
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }

                    failed -> CenterBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "评论加载失败",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "上游评论接口没有响应，稍后再试",
                                fontSize = 12.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(16.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(colors.primary)
                                    .clickable { attempt++ }
                                    .padding(horizontal = 26.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = "重试",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onPrimary,
                                )
                            }
                        }
                    }

                    (page?.list ?: emptyList()).isEmpty() -> CenterBox {
                        Text(
                            text = "还没有评论",
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp,
                        ),
                    ) {
                        items(page!!.list, key = { it.id }) { CommentRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun CommentRow(comment: Comment) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        if (comment.avatar.isNullOrBlank()) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(AppIcons.Person, colors.primary, 18.dp)
            }
        } else {
            RemoteImage(
                url = comment.avatar,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                showFallbackIcon = false,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.user,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.time.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = comment.time,
                        fontSize = 10.5.sp,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = comment.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.onSurface,
            )
            if (comment.likes > 0) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TintedIcon(AppIcons.Heart, colors.onSurfaceVariantSummary, 12.dp)
                    Text(
                        text = "${comment.likes}",
                        fontSize = 10.5.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
