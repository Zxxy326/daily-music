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
import kotlin.math.abs
import androidx.compose.ui.graphics.graphicsLayer
import com.dailymusic.app.data.LyricLine

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
 * WAVE 动感波光（按 QQ 音乐那个效果的视频逐帧分析复刻）。
 *
 * 从视频里量出来的四组参数：
 *
 * | 量 | 实测（1280 宽） | 归一化 |
 * |---|---|---|
 * | 波峰间距 | ~124 px | **0.097 × 宽** |
 * | 波峰移动速度 | ~90 px/s | 0.07 × 宽 / 秒 |
 * | 单圈走完 0.58 宽的周期 | — | 8.3 秒（6 圈错相 → 每 1.4 秒出一圈） |
 * | 光带宽度 | ~45 px | ~0.035 × 宽 |
 *
 * **两个波源，一左一右、都在屏幕外**：左源发的圈往右荡，右源发的圈往左荡，
 * 在中间交汇 —— 这就是「从左边开始荡、余波荡到右边，同时右边也在往左荡」的观感。
 * 圆心在屏幕正中是不对的，会变成靶心。
 *
 * 配色也是从视频里取的：底 `#151545` 深靛蓝、光带 `#4750CE`、歌词 `#979BFE`。
 */
@Composable
private fun WavePage(song: Song, lyrics: Lyrics, position: Long) {
    Box(Modifier.fillMaxSize()) {
        // 底：封面重度模糊 + 深靛蓝罩。视频里几乎看不出封面内容，只有一团蓝紫。
        RemoteImage(
            url = song.cover(640),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                .blur(56.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(Modifier.fillMaxSize().background(Color(0xFF14143F).copy(alpha = 0.66f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Color(0xFF2C2E8C).copy(alpha = 0.42f),
                    1f to Color(0xFF07071C).copy(alpha = 0.72f),
                ),
            ),
        )

        val transition = rememberInfiniteTransition(label = "wave")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // 9.6 秒走完一圈的全程；5 圈错相 → 每 1.9 秒出一条新波。
            animationSpec = infiniteRepeatable(tween(9_600, easing = LinearEasing)),
            label = "wave-phase",
        )

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            // 两个源各自以 0.135 宽为间距发圈，左右交错后视觉间距约 0.067 宽 ——
            // 间距再小就成了密密麻麻的细线，视频里是四条左右的大带子。
            val spacing = w * 0.135f
            val maxR = spacing * 5f
            val band = w * 0.052f
            // 两个源都在屏幕外一点点，纵向略高于中线（视频里波纹的重心偏上）。
            val sources = listOf(
                Offset(-w * 0.04f, size.height * 0.46f),
                Offset(w * 1.04f, size.height * 0.54f),
            )
            sources.forEach { c ->
                repeat(5) { i ->
                    val p = (phase + i / 5f) % 1f
                    val r = maxR * p
                    // 越往外越淡：视频里靠近边缘的带最亮，荡到对面几乎看不见。
                    val a = 0.26f * (1f - p) * (1f - p * 0.45f)
                    if (a <= 0.002f) return@repeat
                    // 一条带画 5 层，边缘自然过渡（硬描边会像雷达扫描）。
                    for (k in -2..2) {
                        val fade = 1f - abs(k) / 3f
                        drawCircle(
                            color = Color(0xFF6E77E8).copy(alpha = a * fade),
                            radius = r + k * band * 0.5f,
                            center = c,
                            style = Stroke(width = band * 0.42f),
                        )
                    }
                }
            }
        }

        val anchor = lyrics.anchorIndex(position).coerceAtLeast(0)
        val line = lyrics.lines.getOrNull(anchor)
        val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name
        val shown = spacedWords(line) ?: text
        // 幽灵字用**上一句**：视频里主歌词换句之后，上一句会以放大、压暗的形态留在背后。
        val ghost = lyrics.lines.getOrNull(anchor - 1)?.text?.takeIf { it.isNotBlank() }
            ?: text

        Text(
            text = ghost,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .blur(5.dp),
            fontSize = waveSize(ghost) * 1.9f,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF05052A).copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )

        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 光晕：主歌词的模糊副本，浅色，让字「浮」在波纹上。
            Text(
                text = shown,
                fontSize = waveSize(shown),
                fontWeight = FontWeight.Bold,
                lineHeight = waveSize(shown) * 1.35f,
                textAlign = TextAlign.Center,
                color = Color(0xFFB9BDFF).copy(alpha = 0.55f),
                modifier = Modifier.blur(14.dp),
            )
            BasicText(
                text = shown,
                style = TextStyle(
                    brush = line?.let { waveBrush(it, position) } ?: SolidColor(Color(0xFFC9CCFF)),
                    fontSize = waveSize(shown),
                    lineHeight = waveSize(shown) * 1.35f,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

/**
 * 按「词」切开发音：`我真的 不能 不能放开手`。
 *
 * QQ 那个效果的歌词是**一个词一个块**、块之间留空的，不是整句连排。
 * KRC 自带逐字时间轴，把词边界上的中文字之间插一个空格就能还原这个排版。
 * 中英混排时不插（`Let you go` 本来就有空格）。
 */
private fun spacedWords(line: LyricLine?): String? {
    if (line == null || line.words.size <= 1) return line?.text
    val sb = StringBuilder()
    line.words.forEachIndexed { i, w ->
        if (i > 0) {
            val prev = line.words[i - 1].text.lastOrNull()
            val next = w.text.firstOrNull()
            if (prev != null && next != null && isCjk(prev) && isCjk(next)) sb.append(' ')
        }
        sb.append(w.text)
    }
    return sb.toString()
}

private fun isCjk(c: Char): Boolean =
    c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

/** 已唱部分偏白、未唱部分偏灰紫，和视频里的双色一致。 */
private fun waveBrush(line: LyricLine, position: Long): Brush {
    val p = sweepFraction(line, position).coerceIn(0f, 1f)
    val sung = Color(0xFFE8EAFF)
    val unsung = Color(0xFF8F94E0).copy(alpha = 0.75f)
    return Brush.horizontalGradient(0f to sung, p to sung, p to unsung, 1f to unsung)
}

private fun waveSize(text: String) = when {
    text.length <= 6 -> 46.sp
    text.length <= 10 -> 38.sp
    text.length <= 16 -> 32.sp
    else -> 27.sp
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
