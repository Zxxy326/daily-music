package com.dailymusic.tv.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.SolidColor
import com.dailymusic.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 封面缓存。TV 上同屏封面数量有限，一个简单的 URL → Bitmap 就够。 */
private val imageCache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
        size > 48
}

@Composable
fun TvImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bitmap = imageCache[url] ?: run {
            val bytes = runCatching { Http.getBytes(url) }.getOrNull() ?: return@run null
            withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()
                    ?.asImageBitmap()
            }?.also { imageCache[url] = it }
        }
    }

    Box(
        modifier = modifier.clip(shape).background(TvColor.cardHigh),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            Image(
                painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(AppIcons.MusicNote),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.3f),
                colorFilter = ColorFilter.tint(TvColor.textDim.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * 可聚焦容器。
 *
 * 电视的焦点态必须一眼看出来：放大 + 一圈浅蓝描边。
 * 描边用 `border` 画在 clip 之后，圆角跟着形状走，不会露出直角。
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    scaleUp: Float = 1.04f,
    borderColor: Color = TvColor.accent,
    borderWidth: androidx.compose.ui.unit.Dp = 2.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    // 只有一个焦点目标：clickable 自带的那个。
    // 之前同时写了 focusable() + clickable()，等于两个 focus target，
    // 焦点落在 clickable 上、onFocusChanged 监听的是 focusable，永远收不到。
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) scaleUp else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "tv-focus-scale",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(
                if (focused) Modifier.border(borderWidth, borderColor, shape) else Modifier,
            )
            // onFocusChanged 必须在 clickable **之前**：它监听的是紧随其后的那个
            // focus target，也就是 clickable 创建的那个。
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        content = content,
    )
}

/**
 * 快捷入口卡片。
 *
 * 竖排（图标在上、两行文字在下）而不是横排：五个入口并排时每个只有 150dp 左右，
 * 横排的图标 + 文字挤在一起，标题只能显示成省略号。竖排能把宽度全留给文字。
 */
@Composable
fun TvPill(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(TvColor.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(TvColor.accent),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = title,
                style = TvText.h3.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = subtitle,
                style = TvText.caption.copy(fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 文字按钮。primary = 浅蓝实心，否则深色描边。 */
@Composable
fun TvButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        scaleUp = 1.06f,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (primary) TvColor.accent else TvColor.card)
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = text,
                style = if (primary) {
                    TvText.button
                } else {
                    TvText.button.copy(color = TvColor.text)
                },
            )
        }
    }
}

/** 导航项。选中态用浅蓝描边，焦点态再加底色 —— 两者要能同时看出来。 */
@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .width(168.dp)
            .size(width = 168.dp, height = 52.dp)
            .clip(shape)
            .background(if (focused) TvColor.cardHigh else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, TvColor.accentDeep, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(
                if (selected || focused) TvColor.accent else TvColor.textDim,
            ),
        )
        Spacer(Modifier.width(14.dp))
        BasicText(
            text = label,
            style = TvText.h3.copy(
                color = if (selected || focused) TvColor.text else TvColor.textDim,
            ),
        )
    }
}

/** 歌曲行：序号 + 小封面 + 歌名/歌手。 */
@Composable
fun SongRow(
    index: Int,
    name: String,
    singer: String,
    cover: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        scaleUp = 1.02f,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(TvColor.card.copy(alpha = 0.6f))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "%02d".format(index + 1),
                style = TvText.caption.copy(color = TvColor.textDim),
            )
            Spacer(Modifier.width(18.dp))
            TvImage(cover, Modifier.size(46.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                BasicText(
                    text = name,
                    style = TvText.h3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = singer,
                    style = TvText.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 图标按钮（播放条用）。 */
@Composable
fun IconAction(
    icon: ImageVector,
    size: Int,
    tint: Color = TvColor.text,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(size.dp / 2))
            .background(if (focused) TvColor.cardHigh else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
            contentDescription = null,
            modifier = Modifier.size((size * 0.55f).dp),
            colorFilter = ColorFilter.tint(if (focused) TvColor.accent else tint),
        )
    }
}
