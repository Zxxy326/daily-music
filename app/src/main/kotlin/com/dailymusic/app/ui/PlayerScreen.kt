package com.dailymusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.LyricLine
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayOrder
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

// 歌词页固定深色（视觉稿第 ③ 屏就是这么定的），所以这套颜色不跟随系统。
internal val LyricSung = Color.White

/**
 * 当前行「还没唱到」那半截的颜色。
 *
 * 现在只有当前行走扫色（远行给纯白 + 外层 alpha），所以这个值实际上等于
 * 「当前行的待唱部分有多亮」。0.28 太暗 —— 整句会读成「唱过的白 + 没唱的灰」，
 * Apple Music 是整句都看得清、只是唱过的更亮，所以取 0.45。
 */
internal val LyricUnsung = Color.White.copy(alpha = 0.45f)

/**
 * 播放页，版式对齐 `design/mockup.html` 第 ② 屏。
 *
 * 和旧版的差别：顶部是「正在播放 + 歌名」而不是「歌名 + 歌手」；封面改成
 * 屏宽 78% 的大圆角方图；进度条是 8dp 药丸 + 19dp 白点，右侧显示**剩余时间**；
 * 播放键换成主题色实心圆；底部多一条「音质徽章 + 工具图标」。
 *
 * 歌词不再挤在封面下面 —— 点封面进入独立的 [LyricsScreen]（视觉稿第 ③ 屏）。
 */
@Composable
fun PlayerScreen(onClose: () -> Unit, onOpenLyrics: () -> Unit) {
    val context = LocalContext.current
    val song = PlayerManager.current ?: return
    val dark = isSystemInDarkTheme()
    val colors = MiuixTheme.colorScheme

    val fg = if (dark) Color.White else Color(0xFF0A0A0A)
    val fg2 = fg.copy(alpha = 0.58f)
    val fg3 = fg.copy(alpha = 0.38f)

    // 底部三个图标和右上角「更多」各自对应一个弹层。
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    // 手势：下滑退出、左右滑切歌。
    //
    // 跟手位移用 Animatable 驱动：往下拖时整页跟着走，松手没到阈值就弹回去 ——
    // 只做「松手才判定」的话，手指在屏幕上滑了半天画面纹丝不动，像没反应。
    val dragY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragY.value.roundToInt()) }
            .background(if (dark) Color.Black else Color(0xFFF2F2F4))
            .pointerInput(Unit) {
                val closeThreshold = 140.dp.toPx()
                val swipeThreshold = 90.dp.toPx()
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dx += amount.x
                        dy += amount.y
                        // 只有「明显在往下拖」才跟手；横向滑是切歌，页面不该动。
                        if (dy > 0f && abs(dy) > abs(dx)) {
                            scope.launch { dragY.snapTo((dragY.value + amount.y).coerceAtLeast(0f)) }
                        }
                    },
                    onDragEnd = {
                        when {
                            abs(dx) > abs(dy) && abs(dx) > swipeThreshold -> {
                                if (dx > 0) PlayerManager.previous() else PlayerManager.next()
                                scope.launch { dragY.animateTo(0f, spring()) }
                            }

                            dragY.value > closeThreshold -> onClose()
                            else -> scope.launch { dragY.animateTo(0f, spring()) }
                        }
                    },
                    onDragCancel = { scope.launch { dragY.animateTo(0f, spring()) } },
                )
            },
    ) {
        // 背景：封面放大模糊 + 一层雾，让每首歌有自己的氛围色。
        RemoteImage(
            url = song.cover(480),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                .blur(70.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                // 视觉稿是 0% / 46% / 78% 三个 stop —— 用默认均分会把雾化拖到屏幕最底部，
                // 结果底栏那块还是蓝灰色，而稿子里 78% 以下就是干净的页面底色。
                Brush.verticalGradient(
                    colorStops = if (dark) {
                        arrayOf(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.46f to Color.Black.copy(alpha = 0.72f),
                            0.78f to Color.Black,
                        )
                    } else {
                        arrayOf(
                            0f to Color(0xFFF2F2F4).copy(alpha = 0.30f),
                            0.46f to Color(0xFFF2F2F4).copy(alpha = 0.86f),
                            0.78f to Color(0xFFF2F2F4),
                        )
                    },
                ),
            ),
        )

        // 竖排的间距不能全靠一个 weight 兜底。
        //
        // 视觉稿是 392×818，从上到下是「五段留白夹着 封面 / 信息 / 进度 / 控件」。
        // 真机是 411×914dp，比稿子高出一截；旧写法只在控件和底栏之间放了一个
        // weight(1f)，多出来的 100 多 dp 全灌进那一段，结果上半屏挤在一起、
        // 下半屏一大片空 —— 看起来就是「控件太靠上」。
        //
        // 现在把多余高度按稿子里各段留白的比例分摊给前四段，最后一段仍用 weight
        // 兜底（这样即使高度估算偏了也不会把底栏顶出屏幕）。
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
        ) {
            // 780dp 是「内容刚好不拥挤」的基准高度，超出的部分才需要分摊。
            val extra = (maxHeight - 780.dp).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    song = song,
                    fg = fg,
                    fg3 = fg3,
                    onClose = onClose,
                    onMore = { showMore = true },
                )
                Spacer(Modifier.height(34.dp + extra * 0.20f))

                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RemoteImage(
                        url = song.cover(600),
                        modifier = Modifier
                            // 视觉稿封面固定 300dp；列已有 26dp 横向内边距，
                            // 所以按可用宽度取 0.835 才刚好落在 300dp，而不是写死尺寸在小屏上溢出。
                            .fillMaxWidth(0.835f)
                            .aspectRatio(1f)
                            .shadow(30.dp, RoundedCornerShape(38.dp), clip = false)
                            // 涟漪必须按封面圆角裁，否则点封面时四个角会露出直角灰块。
                            .clip(RoundedCornerShape(38.dp))
                            .clickable(onClick = onOpenLyrics),
                        shape = RoundedCornerShape(38.dp),
                    )
                }

                Spacer(Modifier.height(28.dp + extra * 0.16f))
                SongInfo(song = song, fg = fg, fg2 = fg2)
                Spacer(Modifier.height(40.dp + extra * 0.22f))

                ProgressBlock(
                    position = PlayerManager.positionMs,
                    duration = PlayerManager.durationMs,
                    fg3 = fg3,
                ) { PlayerManager.seekTo(it) }

                Spacer(Modifier.height(32.dp + extra * 0.18f))
                ControlBlock(
                    playing = PlayerManager.isPlaying,
                    fg = fg,
                    accent = colors.primary,
                    onAccent = colors.onPrimary,
                    onToggle = { PlayerManager.toggle(context) },
                )
                Spacer(Modifier.weight(1f))
                BottomTools(
                    quality = PlayerManager.actualQualityLabel,
                    fg = fg,
                    fg3 = fg3,
                    accent = colors.primary,
                    sleepLabel = if (SleepTimer.active) SleepTimer.label else null,
                    onOpenQueue = { showQueue = true },
                    onOpenSleep = { showSleep = true },
                    onOpenComments = { showComments = true },
                )
                Spacer(Modifier.height(28.dp))
            }
        }

        // 弹层都走 Dialog，所以放在 Box 的哪一层都不影响层级 —— 这里只是就近。
        if (showQueue) QueueSheet { showQueue = false }
        if (showSleep) SleepTimerSheet { showSleep = false }
        if (showComments) CommentsScreen(song) { showComments = false }
        if (showMore) MoreSheet(song) { showMore = false }
    }
}

@Composable
private fun PlayerTopBar(
    song: Song,
    fg: Color,
    fg3: Color,
    onClose: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.CenterStart,
        ) {
            TintedIcon(AppIcons.ChevronDown, fg, 26.dp)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "正在播放",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = fg3,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onMore),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TintedIcon(AppIcons.MoreHoriz, fg3, 20.dp)
        }
    }
}

@Composable
private fun SongInfo(song: Song, fg: Color, fg2: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                lineHeight = 29.sp,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOf(song.singer, song.albumName).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(14.dp))
        val favorite = Library.isFavorite(song.key)
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { Library.toggleFavorite(song) },
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(
                icon = if (favorite) AppIcons.Heart else AppIcons.HeartOutline,
                tint = if (favorite) Color(0xFFEF5F7A) else fg.copy(alpha = 0.5f),
                size = 24.dp,
            )
        }
    }
}

/** 进度条：8dp 药丸轨道 + 19dp 白色圆点，右侧显示剩余时间（视觉稿是 `-2:20`）。 */
@Composable
private fun ProgressBlock(
    position: Long,
    duration: Long,
    fg3: Color,
    onSeek: (Long) -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val base = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = dragFraction ?: base

    fun seekAt(x: Float, width: Int) {
        if (width <= 0 || duration <= 0L) return
        onSeek(((x / width).coerceIn(0f, 1f) * duration).toLong())
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset -> seekAt(offset.x, size.width) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = if (duration > 0L) (offset.x / size.width).coerceIn(0f, 1f) else 0f
                        },
                        onDragEnd = {
                            dragFraction?.let { f ->
                                if (duration > 0L) onSeek((f * duration).toLong())
                            }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        dragFraction = if (duration > 0L) {
                            (change.position.x / size.width).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val h = size.height
                // 轨道用视觉稿的 --pill（6% 黑 / 9% 白），这里略提一点保证在雾化背景上可见。
                drawRoundRect(
                    color = fg3.copy(alpha = 0.10f),
                    size = size.copy(height = h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                )
                val w = size.width * fraction
                if (w > 0f) {
                    drawRoundRect(
                        color = accent,
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                    )
                }
                // 圆点：视觉稿是纯白 19dp + 一层软阴影。
                // 双色圆环在白底轨道上会显脏，所以底下垫一层半透明黑当阴影，再盖实心白。
                val cx = (size.width * fraction).coerceIn(9.5.dp.toPx(), size.width - 9.5.dp.toPx())
                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = 10.5.dp.toPx(),
                    center = Offset(cx, h / 2 + 1.dp.toPx()),
                )
                drawCircle(Color.White, radius = 9.5.dp.toPx(), center = Offset(cx, h / 2))
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = fmtTime(position), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg3)
            Text(
                text = "-" + fmtTime((duration - position).coerceAtLeast(0L)),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg3,
            )
        }
    }
}

@Composable
private fun ControlBlock(
    playing: Boolean,
    fg: Color,
    accent: Color,
    onAccent: Color,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).clickable { PlayerManager.previous() },
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(AppIcons.SkipPrevious, fg, 26.dp)
        }
        Spacer(Modifier.width(34.dp))
        Box(
            modifier = Modifier
                .size(72.dp)
                .shadow(16.dp, CircleShape, clip = false, ambientColor = accent, spotColor = accent)
                .background(accent, CircleShape)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(
                icon = if (playing) AppIcons.Pause else AppIcons.Play,
                tint = onAccent,
                size = 28.dp,
            )
        }
        Spacer(Modifier.width(34.dp))
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).clickable { PlayerManager.next() },
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(AppIcons.SkipNext, fg, 26.dp)
        }
    }
}

/**
 * 底部工具行：左边音质徽章（睡眠定时开着时再多一枚倒计时徽章），
 * 右边三个图标 —— 播放队列 / 睡眠定时 / 评论。
 *
 * 歌词入口在封面上，所以这里第一个图标不再兼职「打开歌词」。
 */
@Composable
private fun BottomTools(
    quality: String,
    fg: Color,
    fg3: Color,
    accent: Color,
    sleepLabel: String?,
    onOpenQueue: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenComments: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (quality.isNotBlank()) {
                Badge(text = quality, color = accent)
            }
            if (sleepLabel != null) {
                Spacer(Modifier.width(8.dp))
                // 定时开着必须看得见 —— 否则用户半夜被停住播放会以为播放器坏了。
                Badge(text = sleepLabel, color = accent)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 播放顺序：三档循环切换，图标跟着变，非「列表循环」时点亮
            // 这个作用域里的 `context` 是上层传进来的 lambda，不是 Context，另取一个
            val ctx = LocalContext.current
            val order = PlayerManager.playOrder
            ToolIcon(
                icon = when (order) {
                    PlayOrder.LIST -> AppIcons.Repeat
                    PlayOrder.ONE -> AppIcons.RepeatOne
                    PlayOrder.SHUFFLE -> AppIcons.Shuffle
                },
                tint = if (order == PlayOrder.LIST) fg3 else accent,
            ) { PlayerManager.cyclePlayOrder(ctx) }
            Spacer(Modifier.width(14.dp))
            ToolIcon(AppIcons.QueueMusic, fg3) { onOpenQueue() }
            Spacer(Modifier.width(14.dp))
            ToolIcon(AppIcons.Clock, if (sleepLabel != null) accent else fg3) { onOpenSleep() }
            Spacer(Modifier.width(14.dp))
            ToolIcon(AppIcons.Comment, fg3) { onOpenComments() }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        // 40dp 触摸区裁成圆：不裁的话点一下就是一个方块灰底（视觉上非常脏）。
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(icon, tint, size)
    }
}

// ==================== 歌词页 ====================

/**
 * 歌词页，版式对齐 `design/mockup.html` 第 ③ 屏。
 *
 * 整首歌词都在 LazyColumn 里，可以拖动翻看，当前行自动居中；用户松手 3 秒后
 * 自动回到当前行。这条「暂停跟随 → 延迟恢复」的逻辑是这个页面最容易翻车的地方：
 * 用 `isScrollInProgress` 判断会把**程序化滚动**也当成用户操作，等于自己取消自己。
 */
@Composable
fun LyricsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val song = PlayerManager.current ?: return
    val lyrics = PlayerManager.lyrics
    val position = PlayerManager.positionMs
    val duration = PlayerManager.durationMs

    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 歌词动效（横屏卡片）：WAVE 动感波光 / KTV 模式。
    var showEffect by remember { mutableStateOf(false) }

    // 沉浸：5 秒不动就把顶栏和底部控制收起来，只留歌词。轻点屏幕再叫回来。
    // 和「动效页」不同，这一页不锁方向，只是把 chrome 淡出。
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    val wake = { chromeVisible = true; chromeTick++ }
    LaunchedEffect(chromeTick, chromeVisible) {
        if (!chromeVisible) return@LaunchedEffect
        delay(5_000)
        chromeVisible = false
    }

    // 背景：模糊封面极慢地「呼吸」。
    // Apple Music 的歌词页不是一张静止底图 —— 播放时封面在十秒尺度上缓慢放大再回缩，
    // 完全静止的画面会让人误以为页面卡住了。
    val breath = rememberInfiniteTransition(label = "lyric-bg")
    val bgScale by breath.animateFloat(
        initialValue = 1.30f,
        targetValue = 1.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bg-scale",
    )

    // 暂停时把雾压深一档。Apple Music 暂停后整页会「沉」下去，
    // 用一层会过渡的暗雾表达「停住了」，比在界面上堆字干净。
    val veil by animateFloatAsState(
        targetValue = if (PlayerManager.isPlaying) 0.40f else 0.58f,
        animationSpec = tween(600),
        label = "veil",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08080C))
            // 轻点唤回顶栏/控制区。只观察按下→抬起的位移、不消费事件，
            // 所以点歌词跳转、上下拖歌词都照常。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - last).getDistance() > 18f) moved = true
                        last = change.position
                        if (!change.pressed) break
                    }
                    if (!moved) wake()
                }
            },
    ) {
        RemoteImage(
            url = song.cover(480),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = bgScale; scaleY = bgScale }
                .blur(90.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF08080C).copy(alpha = veil),
                        0.40f to Color(0xFF08080C).copy(alpha = (veil + 0.32f).coerceAtMost(0.94f)),
                        1f to Color(0xFF08080C).copy(alpha = 0.96f),
                    ),
                ),
            ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // 顶部：收起（向下箭头）/ 歌名+歌手 / 更多。
            // 用 ChevronDown 而不是返回箭头 —— 这一页是从播放页「展开」出来的，
            // 向下收回去才是它的方向感（进入/退出动画也改成了淡入淡出+轻微缩放）。
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(200)),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左右各留 76dp：右边现在有两颗按钮，左边只留一颗的话标题会被挤偏。
                Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterStart) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.ChevronDown, Color.White.copy(alpha = 0.9f), 26.dp)
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = song.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = song.singer,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                }
                Row(
                    modifier = Modifier.width(76.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 动效入口：星星图标，比「更多」亮一档，让人知道这里能点。
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable { showEffect = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.Sparkle, Color.White.copy(alpha = 0.85f), 19.dp)
                    }
                    Box(
                        modifier = Modifier.size(38.dp).clip(CircleShape).clickable { showMore = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(AppIcons.MoreHoriz, Color.White.copy(alpha = 0.55f), 20.dp)
                    }
                }
            }
            }

            // 歌词区：占满剩余高度，两侧 34dp 内边距
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 34.dp)) {
                when {
                    PlayerManager.lyricsLoading -> CenterHint("歌词加载中…")
                    lyrics.isEmpty -> CenterHint("这首歌没有歌词")
                    else -> LyricPanel(
                        lyrics = lyrics,
                        position = position,
                        onSeek = { PlayerManager.seekTo(it) },
                    )
                }
            }

            // 底部：细进度条 + 左右时间 + 控制行
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(200)),
            ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp).padding(bottom = 18.dp)) {
                ThinProgress(position = position, duration = duration) { PlayerManager.seekTo(it) }
                Spacer(Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = fmtTime(position),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.42f),
                    )
                    Text(
                        text = "-" + fmtTime((duration - position).coerceAtLeast(0L)),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.42f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolIcon(AppIcons.QueueMusic, Color.White.copy(alpha = 0.78f)) { showQueue = true }
                    Spacer(Modifier.weight(1f))
                    // 上一首/下一首在歌词页是「配角」，图标放 30dp；
                    // 中间的播放键仍是白色实心圆（Apple Music 在歌词页也是白键黑图标）。
                    ToolIcon(AppIcons.SkipPrevious, Color.White, 30.dp) { PlayerManager.previous() }
                    Spacer(Modifier.width(22.dp))
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            // 白键压在深色模糊底上，没有投影会显得浮不起来。
                            .shadow(
                                elevation = 10.dp,
                                shape = CircleShape,
                                clip = false,
                                ambientColor = Color.Black.copy(alpha = 0.40f),
                                spotColor = Color.Black.copy(alpha = 0.40f),
                            )
                            .background(Color.White, CircleShape)
                            .clip(CircleShape)
                            .clickable { PlayerManager.toggle(context) },
                        contentAlignment = Alignment.Center,
                    ) {
                        TintedIcon(
                            icon = if (PlayerManager.isPlaying) AppIcons.Pause else AppIcons.Play,
                            tint = Color(0xFF0A0A0A),
                            size = 23.dp,
                        )
                    }
                    Spacer(Modifier.width(22.dp))
                    ToolIcon(AppIcons.SkipNext, Color.White, 30.dp) { PlayerManager.next() }
                    Spacer(Modifier.weight(1f))
                    ToolIcon(
                        icon = AppIcons.Clock,
                        // 定时激活时换成主题色。之前用「纯白 vs 80% 白」，压在模糊封面上
                        // 几乎看不出差别，等于没有反馈。
                        tint = if (SleepTimer.active) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            Color.White.copy(alpha = 0.78f)
                        },
                    ) { showSleep = true }
                }
            }
            }
        }

        if (showQueue) QueueSheet { showQueue = false }
        if (showSleep) SleepTimerSheet { showSleep = false }
        if (showMore) MoreSheet(song) { showMore = false }

        // 动效页盖在整页之上（包括底部的进度条与控制键）。
        if (showEffect) {
            LyricEffectScreen(
                song = song,
                lyrics = lyrics,
                position = position,
                onClose = { showEffect = false },
            )
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
    }
}

/**
 * 歌词页底部的细进度条。
 *
 * 带一个 9dp 的白色圆点：Apple Music 的歌词页进度条是有「把手」的，
 * 只有一根线的话用户不知道当前位置到底在哪一格。
 */
@Composable
private fun ThinProgress(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0L && size.width > 0) {
                        onSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
            val h = size.height
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                size = size,
                cornerRadius = r,
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                size = androidx.compose.ui.geometry.Size(size.width * fraction, h),
                cornerRadius = r,
            )
            // 圆点：画在已播放的末端。左右各留 4.5dp 半径，避免首尾被裁掉半个。
            val knobR = 4.5.dp.toPx()
            val cx = (size.width * fraction).coerceIn(knobR, size.width - knobR)
            drawCircle(color = Color.White, radius = knobR, center = Offset(cx, h / 2))
        }
    }
}

/**
 * 歌词面板：整首歌词都在列表里，可拖动翻看，当前行自动居中。
 *
 * 1. 上下 `contentPadding` 各留半个视口高 —— 这样第一行和最后一行也能滚到正中间；
 * 2. 居中不用 `scrollToItem(scrollOffset = ...)`：那个 offset 会被 clamp 到非负，
 *    负值（把行往下推）根本不生效。改成**闭环修正** —— 滚到该行后量出「行中心」
 *    与「视口中心」的实际差值，再用 `scrollBy` 补掉，最多迭代 3 次收敛；
 * 3. 滚动用 **spring** 而不是默认 tween：Apple Music 换行是「滑过去再停住」，
 *    带一点点过冲，tween 匀速收尾会显得很硬；
 * 4. 用户一动手就暂停跟随，松手 3 秒后自动回到当前行；点某一行直接跳到那一句。
 */
@Composable
private fun LyricPanel(
    lyrics: Lyrics,
    position: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 高亮用 anchor 而不是 indexAt：间奏时 indexAt 返回 -1，
    // 整页歌词会一起暗下去、下一句突然亮起来，闪一下很难看。
    // Apple Music 是「上一句一直亮到下一句开口」，anchor 正好是这个语义。
    val anchor = lyrics.anchorIndex(position).coerceAtLeast(0)
    val listState = rememberLazyListState()

    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // 必须监听「拖动交互」而不是 `isScrollInProgress`：后者在**程序化滚动**时
        // 也是 true，会自动把 follow 关掉，等于自己取消自己。
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    delay(3_000)
                    follow = true
                }
            }
        }
    }

    BoxWithConstraints(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = maxHeight / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(lyrics.lines.size) { i ->
                LyricLineItem(
                    line = lyrics.lines[i],
                    distance = abs(i - anchor),
                    position = position,
                    onSeek = { onSeek(lyrics.lines[i].startMs) },
                )
            }
        }

        LaunchedEffect(anchor, follow) {
            if (!follow || anchor < 0) return@LaunchedEffect
            // 首次组合时 LazyColumn 还没完成布局，此时任何滚动请求都会被忽略。
            if (listState.layoutInfo.totalItemsCount == 0) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            }
            repeat(3) { round ->
                val info = listState.layoutInfo
                val target = info.visibleItemsInfo.firstOrNull { it.index == anchor }
                if (target == null) {
                    listState.scrollToItem(anchor)
                    withFrameNanos { }
                    return@repeat
                }
                val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val delta = target.offset + target.size / 2 - viewportCenter
                if (abs(delta) <= 2) return@LaunchedEffect
                if (round == 0) {
                    listState.animateScrollBy(
                        delta.toFloat(),
                        // dampingRatio 略小于 1：末尾有一点点过冲，像歌词被「吸」到位。
                        spring(dampingRatio = 0.88f, stiffness = 210f),
                    )
                } else {
                    listState.scrollBy(delta.toFloat())
                    withFrameNanos { }
                }
            }
        }
    }
}

/**
 * 单行歌词，按与当前行的距离做三档衰减。
 *
 * Apple Music 的歌词不是「一句亮、其余全灰」，而是**越远越淡**：
 * 相邻行还能读清，隔两行只剩轮廓，再远几乎是暗纹。这种梯度是它「有景深」的来源。
 *
 * 字号不直接改（每帧重排会抖），而是用 `graphicsLayer` 缩放：当前行 1.34 倍 ≈ 25sp，
 * 布局高度仍按 19sp 算，所以换行时列表不会跳。放大可能压到相邻行，
 * 因此当前行的上下留白同步放大到 15dp（`animateDpAsState` 跟着一起过渡）。
 */
@Composable
private fun LyricLineItem(
    line: LyricLine,
    distance: Int,
    position: Long,
    onSeek: () -> Unit,
) {
    val active = distance == 0
    val alphaTarget = when {
        active -> 1f
        distance == 1 -> 0.46f
        distance == 2 -> 0.24f
        else -> 0.12f
    }
    val alpha by animateFloatAsState(
        targetValue = alphaTarget,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyric-alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1.34f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
        label = "lyric-scale",
    )
    val vPad by animateDpAsState(
        targetValue = if (active) 15.dp else 7.dp,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "lyric-pad",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            // 点一句就跳到那一句（Apple Music 的行为）。
            // 用无 ripple 的 clickable：歌词页是深色大字，方形的涟漪很出戏。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSeek,
            )
            .padding(vertical = vPad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = line.text,
            style = TextStyle(
                // 当前行走卡拉 OK 扫色；其余行给纯白，靠外层 alpha 压暗。
                brush = if (active) sweepBrush(line, position) else SolidColor(Color.White),
                fontSize = 21.sp,
                lineHeight = 31.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        if (active) {
            line.translation?.let { tr ->
                Text(
                    text = tr,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.50f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
            line.romaji?.let { rj ->
                Text(
                    text = rj,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.36f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 把「已唱到第几个字」换算成 0..1 的横向进度，做成硬边渐变给文字做卡拉 OK 扫色。
 * 按字宽加权（而不是按词数均分），中英文混排时才不会越扫越偏。
 */
internal fun sweepFraction(line: LyricLine, position: Long): Float {
    val total = line.text.length
    if (total <= 0) return 0f
    if (position <= line.startMs) return 0f
    var charsBefore = 0
    for (w in line.words) {
        val wEnd = w.startMs + w.durationMs
        if (position < w.startMs) return charsBefore.toFloat() / total
        if (position <= wEnd) {
            val f = if (w.durationMs > 0L) {
                ((position - w.startMs).toFloat() / w.durationMs).coerceIn(0f, 1f)
            } else {
                1f
            }
            return (charsBefore + w.text.length * f) / total
        }
        charsBefore += w.text.length
    }
    return 1f
}

internal fun sweepBrush(line: LyricLine, position: Long): Brush {
    val p = sweepFraction(line, position).coerceIn(0f, 1f)
    return Brush.horizontalGradient(
        0f to LyricSung,
        p to LyricSung,
        p to LyricUnsung,
        1f to LyricUnsung,
    )
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
