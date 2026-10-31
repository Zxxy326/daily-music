package com.dailymusic.app.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 歌词动效（横屏全屏）。
 *
 * 参考 QQ 音乐的「歌词动效」：**整屏横过来**，一张卡片铺满，左右滑切下一张；
 * 顶部不写模式名，操作区（返回 / 歌名 / 播放控制 / 圆点）5 秒不动就自动淡出，
 * 轻点屏幕再唤回来。
 *
 * 两个效果都是纯 Compose 绘制，没有视频素材：
 *
 * - **WAVE 动感波光**：模糊封面 + 青蓝罩打底，画 5 条**宽而柔**的光带往外推
 *   （不是细圆环 —— 细环看起来像雷达扫描），歌词后面垫一层放大 2 倍的「幽灵字」。
 * - **KTV 模式**：封面当画面（轻模糊 + 压暗），歌名做成大字水印，歌词贴底逐字扫色。
 *
 * 逐字扫色复用竖屏歌词页的 [sweepBrush]，两个模式的「唱到哪」永远是同一个基准。
 */
internal enum class LyricEffect(val label: String) {
    WAVE("WAVE 动感波光"),
    KTV("KTV 模式"),
}

@Composable
internal fun LyricEffectScreen(
    song: Song,
    lyrics: Lyrics,
    position: Long,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current
    val view = LocalView.current

    // 强制横屏。MainActivity 在 manifest 里声明了 configChanges="orientation|screenSize…"，
    // 所以旋转不会重建 Activity，动效页的状态不会丢。
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 沉浸：藏掉状态栏和导航栏。手势条会以「临时浮层」的形式出现，滑一下能看到。
    DisposableEffect(view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val pager = rememberPagerState(pageCount = { LyricEffect.entries.size })

    // 操作区 5 秒自动隐藏。用 tick 自增来重置计时（Boolean true→true 不会重启 LaunchedEffect）。
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    val wake = {
        chromeVisible = true
        chromeTick++
    }
    LaunchedEffect(chromeTick, chromeVisible) {
        if (!chromeVisible) return@LaunchedEffect
        delay(5_000)
        chromeVisible = false
    }
    // 左右滑切换动效时把操作区叫回来：滑动不会触发「轻点」检测，
    // 不叫的话用户换了一页却看不到歌名和播放键，会以为界面卡住了。
    LaunchedEffect(pager.currentPage) { wake() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 轻点唤回操作区。这里**不消费**任何事件，只观察按下→抬起的位移，
            // 所以左右滑切卡片仍然由 pager 处理。
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
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            EffectPage(
                effect = LyricEffect.entries[page],
                song = song,
                lyrics = lyrics,
                position = position,
            )
        }

        // 左上角返回，跟着操作区一起隐藏。
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(AppIcons.ArrowBack, Color.White, 20.dp)
            }
        }

        // 底部操作区：歌名 + 控制键 + 圆点。
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(220)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 26.dp, end = 26.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PagerDots(pager.currentPage)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(
                        url = song.cover(160),
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.singer,
                            fontSize = 10.5.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ControlIcon(AppIcons.SkipPrevious, 20.dp) { PlayerManager.previous() }
                    Spacer(Modifier.width(14.dp))
                    PlayButton()
                    Spacer(Modifier.width(14.dp))
                    ControlIcon(AppIcons.SkipNext, 20.dp) { PlayerManager.next() }
                    Spacer(Modifier.width(14.dp))
                    ControlIcon(
                        icon = if (Library.isFavorite(song.key)) AppIcons.Heart else AppIcons.HeartOutline,
                        size = 18.dp,
                    ) { Library.toggleFavorite(song) }
                }
            }
        }
    }
}

@Composable
private fun PagerDots(current: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LyricEffect.entries.forEachIndexed { index, _ ->
            val active = current == index
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(5.dp)
                    .width(if (active) 18.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.32f)),
            )
        }
    }
}

@Composable
private fun PlayButton() {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { PlayerManager.toggle(context) },
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(
            icon = if (PlayerManager.isPlaying) AppIcons.Pause else AppIcons.Play,
            tint = Color.White,
            size = 26.dp,
        )
    }
}

@Composable
private fun ControlIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(icon, Color.White.copy(alpha = 0.92f), size)
    }
}

// ---------------------------------------------------------------- 页面

@Composable
private fun EffectPage(
    effect: LyricEffect,
    song: Song,
    lyrics: Lyrics,
    position: Long,
) {
    when (effect) {
        LyricEffect.WAVE -> WavePage(song, lyrics, position)
        LyricEffect.KTV -> KtvPage(song, lyrics, position)
    }
}

// ---------------------------------------------------------------- WAVE

/**
 * 波光：模糊封面 → 青蓝径向罩 → 五条宽光带 → 幽灵大字 → 当前歌词。
 *
 * 和上一版最大的差别是**光带**：上一版是 1.2~5dp 的细圆环，像雷达扫描；
 * QQ 那个是「水面上的光」，每条带宽约半径的 9%，还叠三层做出柔边。
 */
@Composable
private fun WavePage(song: Song, lyrics: Lyrics, position: Long) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(
            url = song.cover(640),
            modifier = Modifier.fillMaxSize().blur(42.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Color(0xFFB9F7FA).copy(alpha = 0.62f),
                    0.42f to Color(0xFF1FB2CB).copy(alpha = 0.76f),
                    1f to Color(0xFF064F60).copy(alpha = 0.92f),
                ),
            ),
        )

        val transition = rememberInfiniteTransition(label = "wave")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_200, easing = LinearEasing)),
            label = "wave-phase",
        )

        Canvas(Modifier.fillMaxSize()) {
            // 圆心略偏左：QQ 那个效果的重心不在正中，正中的话会显得很「靶心」。
            val c = Offset(size.width * 0.44f, size.height * 0.52f)
            val maxR = size.maxDimension * 1.05f
            // 中心一团柔光
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = c,
                    radius = maxR * 0.5f,
                ),
                radius = maxR * 0.5f,
                center = c,
            )
            repeat(5) { i ->
                val p = (phase + i / 5f) % 1f
                val r = maxR * (0.14f + 0.86f * p)
                val a = (1f - p) * 0.15f
                // 一条宽光带 = 三层同色细带叠在一起，边缘自然过渡。
                for (k in -1..1) {
                    drawCircle(
                        color = Color(0xFFCFFBFF).copy(alpha = a / 3f),
                        radius = r + k * r * 0.05f,
                        center = c,
                        style = Stroke(width = (r * 0.09f).coerceAtLeast(2f)),
                    )
                }
            }
        }

        val line = currentLine(lyrics, position)
        val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name

        // 幽灵大字：把当前句放大垫在后面，只留一点亮度。
        // 这是 QQ 那个效果「有纵深」的关键 —— 只有前景一句话会显得很平。
        Text(
            text = text,
            // 不设左右内边距：长句让它在屏幕边缘自然裁掉，才有「字比画面大」的感觉。
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .blur(8.dp),
            fontSize = 88.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF04303A).copy(alpha = 0.26f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )

        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = waveSize(text),
                fontWeight = FontWeight.ExtraBold,
                lineHeight = waveSize(text) * 1.3f,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.blur(12.dp),
            )
            BasicText(
                text = text,
                style = TextStyle(
                    brush = line?.let { sweepBrush(it, position) } ?: SolidColor(Color.White),
                    fontSize = waveSize(text),
                    lineHeight = waveSize(text) * 1.3f,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

private fun waveSize(text: String) = when {
    text.length <= 6 -> 48.sp
    text.length <= 10 -> 40.sp
    text.length <= 18 -> 32.sp
    else -> 26.sp
}

// ---------------------------------------------------------------- KTV

/**
 * KTV：封面当画面（轻模糊 + 压暗），歌名做大字水印，歌词贴底逐字扫色。
 *
 * 不把封面彻底模糊掉 —— KTV 的味道就在「看得见画面」，靠一层黑罩保证白字读得出来。
 */
@Composable
private fun KtvPage(song: Song, lyrics: Lyrics, position: Long) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(
            url = song.cover(640),
            modifier = Modifier.fillMaxSize().blur(3.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.50f),
                    0.45f to Color.Black.copy(alpha = 0.30f),
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
        )
        Text(
            text = song.name,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 30.dp),
            fontSize = 58.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 5.sp,
            color = Color.White.copy(alpha = 0.18f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )

        val line = currentLine(lyrics, position)
        val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 40.dp)
                .padding(bottom = 118.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    fontSize = ktvSize(text),
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = ktvSize(text) * 1.3f,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.32f),
                    modifier = Modifier.blur(10.dp),
                )
                BasicText(
                    text = text,
                    style = TextStyle(
                        brush = line?.let { sweepBrush(it, position) } ?: SolidColor(Color.White),
                        fontSize = ktvSize(text),
                        lineHeight = ktvSize(text) * 1.3f,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            line?.translation?.takeIf { it.isNotBlank() }?.let { tr ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.66f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun ktvSize(text: String) = when {
    text.length <= 10 -> 34.sp
    text.length <= 18 -> 28.sp
    else -> 22.sp
}

/** 当前该高亮的那一行；间奏时 `anchorIndex` 会停在上一句，正好符合「上一句一直亮」。 */
private fun currentLine(lyrics: Lyrics, position: Long) =
    lyrics.lines.getOrNull(lyrics.anchorIndex(position).coerceAtLeast(0))
