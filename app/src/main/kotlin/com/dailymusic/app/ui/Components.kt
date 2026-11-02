package com.dailymusic.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TintedIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
        )
        if (trailing != null) {
            Text(text = trailing, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}

/**
 * 分组小标题，对齐视觉稿的 `.sec-label`：12.5sp、次级灰、字重 600、上方留 26dp。
 *
 * 和 [SectionTitle] 的区别是**层级更低**：它是「卡片列表的抬头」，不是页面级大标题。
 */
@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurfaceVariantSummary,
        )
        if (trailing != null) {
            Text(text = trailing, fontSize = 11.5.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}

/**
 * 卡片式歌曲行，对齐视觉稿的 `.list-card .row`。
 *
 * 和 [SongRow] 的区别：外面包一张白色圆角大卡片，行之间用缩进分隔线而不是整行间距。
 * 这种「一张卡里装多行」的结构在浅色下更接近 HyperOS 的列表观感。
 */
@Composable
fun CardSongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = false,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 70.dp, end = 18.dp)
                    .height(1.dp)
                    .background(colors.onSurface.copy(alpha = 0.07f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = song.cover(240),
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) colors.primary else colors.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.singer,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        color = colors.onSurfaceVariantSummary,
                    )
                    // 只在真正的高音质上打标；每行都挂「标准」等于没信息。
                    val tag = song.qualityLabel
                    if (tag == "SQ" || tag == "HQ") {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tag,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                            modifier = Modifier
                                .border(1.dp, colors.primary, RoundedCornerShape(5.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            if (isCurrent && isPlaying) {
                TintedIcon(AppIcons.Play, colors.primary, 14.dp)
                Spacer(Modifier.width(10.dp))
            }
            when {
                song.key in PlayerManager.encryptedKeys -> Text(
                    text = "加密",
                    fontSize = 10.sp,
                    color = colors.error,
                )

                onToggleFavorite != null -> Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(
                        icon = if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                        tint = if (favorite) Color(0xFFFF5A7A) else colors.onSurfaceVariantSummary,
                        size = 18.dp,
                    )
                }

                else -> Text(
                    text = song.durationText,
                    fontSize = 11.5.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    showFavorite: Boolean = false,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = song.cover(240),
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(15.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) colors.primary else colors.onSurface,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = listOf(song.singer, song.albumName).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (isCurrent && isPlaying) {
            TintedIcon(AppIcons.Play, colors.primary, 14.dp)
            Spacer(Modifier.width(8.dp))
        }
        // 已经踩过坑的加密曲目提前标出来，省得用户点一次、卡一下、再被跳过。
        // 只有真正播放失败过才会进这个集合，所以不会误伤。
        if (song.key in PlayerManager.encryptedKeys) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.error.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = "加密", fontSize = 10.sp, color = colors.error)
            }
            Spacer(Modifier.width(8.dp))
        } else {
            // 时长始终保留：列表浏览时它是重要的决策信息，不能被收藏键挤掉。
            Text(
                text = song.durationText,
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (showFavorite && onToggleFavorite != null) {
            // 收藏是行内独立热区，别让它冒泡成「播放这一首」。
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(
                    icon = if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                    tint = if (favorite) colors.primary else colors.onSurfaceVariantSummary,
                    size = 19.dp,
                )
            }
        }
    }
}

/**
 * 状态栏 / 导航栏图标的明暗。
 *
 * 页面是深色（播放页、歌词页）时必须把图标切成白色，否则系统按「浅色主题」
 * 画出来的黑色图标会直接消失在深色背景里 —— 这就是「沉浸以后没适配」的一半。
 *
 * 用 DisposableEffect 记录并还原上一个值：播放页/歌词页是盖在主页之上的，
 * 退出时要把控制权还给下面的页面。
 */
@Composable
fun SystemBarIcons(light: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(light, activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        val prevStatus = controller.isAppearanceLightStatusBars
        val prevNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        onDispose {
            controller.isAppearanceLightStatusBars = prevStatus
            controller.isAppearanceLightNavigationBars = prevNav
        }
    }
}
