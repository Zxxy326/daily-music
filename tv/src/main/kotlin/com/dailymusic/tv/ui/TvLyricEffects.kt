package com.dailymusic.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.core.Http
import com.dailymusic.app.data.KugouApi
import com.dailymusic.app.data.LyricLine
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SpectrumShaper
import com.dailymusic.app.playback.SpectrumTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp

/**
 * TV 端歌词动效（横屏全屏），对齐手机端的三种效果：
 *
 * - **动感涟漪 WAVE**：封面取色打底，频谱驱动的同心波带从左右两侧往中间荡，歌词在中间汇合。
 * - **简约 KTV**：模糊封面 + 描边歌名，两句歌词一左一右逐字点亮（成对换页）。
 * - **精简时钟 CLOCK**：模糊封面 + 辉光，中间一个大号时间当背景板。
 *
 * 和手机端的差异（TV 适配）：
 * - 左右方向键切换效果，不用触摸横滑；顶部有 3 个可聚焦的效果按钮 + 当前手势提示。
 * - 不强制旋转（TV 本来就是横屏），不处理状态栏/导航栏沉浸（TV 没有）。
 * - 字体用系统默认粗体（手机端用的方圆体在 tv 模块里没有资源引用）。
 * - 取色 / token 化 / 扫色等辅助函数在这里自带，避免跨模块依赖 app。
 */

internal enum class TvLyricEffect(val label: String) {
    WAVE("动感涟漪"),
    KTV("简约"),
    CLOCK("精简时钟"),
}

/**
 * 歌词动效全屏页。
 *
 * @param song 当前歌曲
 * @param onClose 返回（退出全屏效果，回到普通播放页）
 */
@Composable
fun TvLyricEffectScreen(
    song: Song,
    onClose: () -> Unit,
) {
    // 歌词自己加载（和 TvNowPlaying 同一接口），失败就退化显示歌名/时间。
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(song.key) {
        lyrics = runCatching { KugouApi.lyric(song) }.getOrNull()
    }

    var index by remember { mutableIntStateOf(0) }
    val effects = TvLyricEffect.entries
    val effect = effects[index]

    // 顶部按钮的焦点：首次进入给当前效果按钮，便于「进来就能按 OK / 左右切」。
    val focusRequesters = remember { List(effects.size) { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { focusRequesters[0].requestFocus() } }

    // 纯焦点驱动切换：左右方向键让焦点在三个 tab 间移动，哪个 tab 获得焦点
    // 就把 index 设成它。之前 Box.onKeyEvent 也改 index + tab 双焦点目标，
    // 一次按键会被处理两次（跳 2 格），这里统一交给焦点系统，一次只走一格。
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 返回键兜底：用 onPreviewKeyEvent 在焦点 tab 的 clickable 之前拦截 Back，
            // 避免 tab 消费掉导致 RootScreen 的 BackHandler 收不到（实测退出失效）。
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) { onClose(); true } else false
            },
    ) {
        val position = PlayerManager.positionMs
        when (effect) {
            TvLyricEffect.WAVE -> TvWavePage(song, lyrics, position)
            TvLyricEffect.KTV -> TvKtvPage(song, lyrics, position)
            TvLyricEffect.CLOCK -> TvClockPage(song, position)
        }

        // 顶部：返回 + 三个效果按钮（遥控器可聚焦切换）。
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconAction(AppIcons.ArrowBack, 46) { onClose() }
            effects.forEachIndexed { i, e ->
                EffectTab(
                    label = e.label,
                    selected = i == index,
                    modifier = Modifier.focusRequester(focusRequesters[i]),
                    onFocus = { index = i },
                ) { index = i }
            }
        }

        // 底部小字：切页提示（不遮挡画面）。
        BasicText(
            text = "◀ ▶ 切换效果　·　返回键退出",
            style = TextStyle(fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun EffectTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    BasicText(
        label,
        style = TextStyle(
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        ),
        modifier = modifier
            .background(
                when {
                    focused -> Color.White.copy(alpha = 0.32f)
                    selected -> Color.White.copy(alpha = 0.22f)
                    else -> Color.Black.copy(alpha = 0.35f)
                },
                androidx.compose.foundation.shape.RoundedCornerShape(99.dp),
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
            // 单个焦点目标：onFocusChanged 必须在 clickable 之前，监听 clickable
            // 创建的那个 target。不要再加 focusable()，否则变成两个 target 会错位。
            .onFocusChanged { if (it.isFocused) { focused = true; onFocus() } else focused = false }
            .clickable(onClick = onClick),
    )
}

// ================================================================ WAVE

@Composable
private fun TvWavePage(song: Song, lyrics: Lyrics?, position: Long) {
    val palette = rememberTvCoverPalette(song.cover(640))
    var phase by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var fast by remember { mutableFloatStateOf(0f) }
    var slow by remember { mutableFloatStateOf(0f) }
    var beatEnv by remember { mutableFloatStateOf(0f) }

    val shaper = remember { SpectrumShaper() }
    val latestPos = rememberUpdatedState(position)
    val latestLyrics = rememberUpdatedState(lyrics)

    LaunchedEffect(Unit) {
        var last = 0L
        var speedSmooth = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                shaper.update(SpectrumTap.bins)

                val raw = SpectrumTap.bassDb
                fast += (raw - fast) * (1f - exp(-dt / 0.08f))
                slow += (raw - slow) * (1f - exp(-dt / 1.20f))
                val rise = ((fast - slow) - 0.8f).coerceAtLeast(0f) / 8f
                beatEnv = maxOf(beatEnv * exp(-dt / 0.28f), rise).coerceIn(0f, 1f)
                level = 0.38f + 0.44f * beatEnv

                val lyr = latestLyrics.value
                val pos = latestPos.value
                val line = lyr?.lines?.getOrNull(lyr.anchorIndex(pos).coerceAtLeast(0))
                val pulse = tvWordPulse(line, pos)
                val target = (0.06f + 0.50f * level + 0.04f * pulse).coerceAtMost(0.58f)
                val k = if (target > speedSmooth) 1f - exp(-dt / 0.16f) else 1f - exp(-dt / 0.50f)
                speedSmooth += (target - speedSmooth) * k
                phase = (phase + dt * speedSmooth) % 1f
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()

        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to palette.glow,
                    0.55f to palette.base,
                    1f to palette.deep,
                    center = Offset(wPx * 0.5f, hPx * 0.5f),
                    radius = maxOf(wPx, hPx) * 0.62f,
                ),
            ),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) { tvDrawWaveField(phase, level, shaper, palette) }

        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.90f)
                .background(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.26f),
                        0.46f to Color.Black.copy(alpha = 0.14f),
                        0.74f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )

        val anchor = lyrics?.anchorIndex(position)?.coerceAtLeast(0) ?: -1
        val line = lyrics?.lines?.getOrNull(anchor)
        val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name
        TvWaveWatermark(text, wPx, hPx, palette)
        TvWaveLyrics(line, position, text, palette, wPx, hPx)
    }
}

@Composable
private fun BoxScope.TvWaveWatermark(text: String, wPx: Float, hPx: Float, palette: TvCoverPalette) {
    val mark = remember(text) { tvWatermarkOf(text) }
    if (mark.isEmpty()) return
    val density = LocalDensity.current
    val sizePx = minOf(hPx * 0.95f, wPx * 0.34f / mark.length * 1.05f)
    BasicText(
        text = mark,
        style = TextStyle(
            fontSize = with(density) { sizePx.toSp() },
            fontWeight = FontWeight.Black,
            color = palette.watermark,
        ),
        modifier = Modifier.align(Alignment.Center).blur(14.dp),
    )
}

private fun tvWordPulse(line: LyricLine?, position: Long): Float {
    if (line == null) return 0f
    var last = -1L
    for (w in line.words) {
        if (w.startMs <= position && w.startMs > last) last = w.startMs
    }
    if (last < 0) return 0f
    val dt = (position - last).toFloat()
    return if (dt > 900f) 0f else exp(-dt / 260f)
}

@Composable
private fun BoxScope.TvWaveLyrics(
    line: LyricLine?,
    position: Long,
    fallback: String,
    palette: TvCoverPalette,
    wPx: Float,
    hPx: Float,
) {
    val text = line?.text?.takeIf { it.isNotBlank() } ?: fallback
    val tokens = remember(text) { tvTokenizeLyric(text) }
    val density = LocalDensity.current
    val fontSize = remember(text, wPx, hPx) {
        val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val px = minOf(hPx * 0.165f, maxOf(hPx * 0.052f, wPx * 0.66f / (chars * 1.03f))) * 0.72f
        with(density) { px.toSp() }
    }

    val enter = remember { Animatable(1f) }
    LaunchedEffect(text) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(920, easing = LinearEasing))
    }

    FlowRow(
        modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.68f),
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center,
    ) {
        val mid = (tokens.size - 1) / 2f
        tokens.forEachIndexed { index, token ->
            val side = if (index <= mid) -1f else 1f
            val delay = if (mid < 0.5f) 0f else (abs(index - mid) / mid) * 0.26f
            BasicText(
                text = token,
                style = TextStyle(
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.28f,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    brush = tvTokenSweepBrush(
                        line, position, tokens, index,
                        sungColor = Color.White,
                        dimColor = Color.White.copy(alpha = 0.46f),
                    ),
                ),
                modifier = Modifier.graphicsLayer {
                    val p = ((enter.value - delay) / 0.46f).coerceIn(0f, 1f)
                    val e = 1f - (1f - p) * (1f - p) * (1f - p)
                    translationX = side * (1f - e) * wPx * 0.10f
                    alpha = 0.03f + 0.97f * e
                },
            )
        }
    }
}

private const val TV_RING_SLOTS = 6
private const val TV_RING_BASE = 5

private fun DrawScope.tvDrawWaveField(
    phase: Float,
    drive: Float,
    shaper: SpectrumShaper,
    palette: TvCoverPalette,
) {
    val w = size.width
    val h = size.height

    val band = w * 0.075f * (0.78f + 0.42f * drive)
    val maxR = w * 0.40f
    val loud = 0.16f + 0.62f * drive
    val extra = ((drive - 0.56f) / 0.24f).coerceIn(0f, 1f)

    val sources = listOf(Offset(-w * 0.02f, h * 0.5f), Offset(w * 1.02f, h * 0.5f))
    for (center in sources) {
        for (i in 0 until TV_RING_SLOTS) {
            val offset = if (i < TV_RING_BASE) i / TV_RING_BASE.toFloat() else 0.1f
            val segFrom = (i * SpectrumTap.BARS / TV_RING_BASE).coerceIn(0, SpectrumTap.BARS - 1)
            val segTo = ((i + 1) * SpectrumTap.BARS / TV_RING_BASE).coerceIn(segFrom + 1, SpectrumTap.BARS)
            val tint = (shaper.band(segFrom, segTo) - drive).coerceIn(-0.30f, 0.30f)
            val tone = 0.94f + 0.20f * tint
            val gate = if (i < TV_RING_BASE) (0.35f + 0.65f * drive) * tone
            else extra * (0.35f + 0.65f * drive) * tone
            if (gate <= 0.01f) continue
            val p = (phase + offset) % 1f
            val r = maxR * p

            val fin = (p / 0.22f).coerceAtMost(1f)
            val fout = 1f - ((p - 0.62f) / 0.38f).coerceIn(0f, 1f)
            val a = 0.78f * loud * gate * fin * fin * fout * fout
            if (a <= 0.004f) continue

            val ringVar = (if (i % 2 == 0) 1f else 0.80f) * (0.80f + 0.40f * drive)
            val outer = r + band * 0.5f
            if (outer <= 1f) continue
            val innerEdge = ((r - band * 0.5f) / outer).coerceIn(0.0001f, 0.98f)
            val peak = (r / outer).coerceIn(innerEdge + 0.002f, 0.985f)
            val tail = (peak + (1f - peak) * 0.55f).coerceIn(peak + 0.002f, 0.999f)

            val brush = Brush.radialGradient(
                0f to Color.Transparent,
                innerEdge to Color.Transparent,
                (innerEdge + (peak - innerEdge) * 0.55f) to palette.wave.copy(alpha = a * 0.42f * ringVar),
                peak to palette.waveIn.copy(alpha = a * ringVar),
                tail to palette.wave.copy(alpha = a * 0.40f * ringVar),
                1f to Color.Transparent,
                center = center,
                radius = outer,
            )
            drawCircle(brush = brush, radius = outer, center = center)
        }
    }

    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0f),
            0.26f to Color.White.copy(alpha = 0.45f),
            0.50f to Color.White,
            0.74f to Color.White.copy(alpha = 0.45f),
            1f to Color.White.copy(alpha = 0f),
        ),
        blendMode = BlendMode.DstIn,
    )
}

// ================================================================ KTV

@Composable
private fun TvKtvPage(song: Song, lyrics: Lyrics?, position: Long) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()

        Image(
            bitmap = rememberCoverBitmap(song.cover(640)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(16.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.50f),
                    0.40f to Color.Black.copy(alpha = 0.34f),
                    1f to Color.Black.copy(alpha = 0.74f),
                ),
            ),
        )

        val lines = lyrics?.lines.orEmpty()
        val anchor = lyrics?.anchorIndex(position)?.coerceAtLeast(0) ?: 0
        val pairStart = (anchor / 2) * 2

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = with(LocalDensity.current) { (hPx * 0.14f).toDp() }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvOutlinedText(
                text = song.name,
                style = TextStyle(
                    fontSize = with(LocalDensity.current) { (hPx * 0.13f).toSp() },
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = Color.Transparent,
                strokeWidth = 3f,
                strokeColor = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
            )
            Spacer(Modifier.height(with(LocalDensity.current) { (hPx * 0.016f).toDp() }))
            BasicText(
                text = song.singer.uppercase(),
                style = TextStyle(
                    fontSize = with(LocalDensity.current) { (hPx * 0.020f).toSp() },
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.60f),
                ),
                maxLines = 1,
            )
        }

        val fs = with(LocalDensity.current) { (hPx * 0.058f).toSp() }
        val lineGap = with(LocalDensity.current) { (hPx * 0.045f).toDp() }

        AnimatedContent(
            targetState = pairStart,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(0.85f)
                .padding(top = with(LocalDensity.current) { (hPx * 0.56f).toDp() }),
            transitionSpec = {
                (fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 5 }) togetherWith
                    (fadeOut(tween(280)) + slideOutVertically(tween(280)) { -it / 5 })
            },
            label = "tv-ktv-pair",
        ) { start ->
            Column(Modifier.fillMaxWidth()) {
                TvKtvLyricLine(lines.getOrNull(start), position, fs, alignEnd = false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(lineGap))
                TvKtvLyricLine(lines.getOrNull(start + 1), position, fs, alignEnd = true, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TvKtvLyricLine(
    line: LyricLine?,
    position: Long,
    fontSize: TextUnit,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = line?.text.orEmpty()
    if (text.isBlank()) return
    val tokens = remember(text) { tvTokenizeLyric(text) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        tokens.forEachIndexed { i, token ->
            TvOutlinedText(
                text = token,
                style = TextStyle(
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.26f,
                    fontWeight = FontWeight.Bold,
                ),
                brush = tvTokenSweepBrush(
                    line, position, tokens, i,
                    sungColor = Color.White,
                    dimColor = Color.White.copy(alpha = 0.55f),
                ),
                strokeWidth = 5f,
                strokeColor = Color.Black.copy(alpha = 0.90f),
            )
        }
    }
}

@Composable
private fun TvOutlinedText(
    text: String,
    style: TextStyle,
    color: Color = Color.White,
    brush: Brush? = null,
    strokeWidth: Float,
    strokeColor: Color,
    maxLines: Int = Int.MAX_VALUE,
) {
    Box {
        BasicText(
            text = text,
            style = style.copy(
                color = strokeColor,
                drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round, cap = StrokeCap.Round),
            ),
            maxLines = maxLines,
        )
        BasicText(
            text = text,
            style = if (brush != null) style.copy(brush = brush) else style.copy(color = color),
            maxLines = maxLines,
        )
    }
}

// ================================================================ CLOCK

@Composable
private fun TvClockPage(song: Song, position: Long) {
    val palette = rememberTvCoverPalette(song.cover(640))
    var clock by remember { mutableStateOf(tvClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = tvClockText()
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        Image(
            bitmap = rememberCoverBitmap(song.cover(640)),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(26.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to palette.glow.copy(alpha = 0.42f),
                    0.45f to palette.base.copy(alpha = 0.34f),
                    1f to Color.Black.copy(alpha = 0.62f),
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.34f),
                    0.45f to Color.Black.copy(alpha = 0.18f),
                    1f to Color.Black.copy(alpha = 0.44f),
                ),
            ),
        )
        BasicText(
            text = clock,
            style = TextStyle(
                fontSize = with(LocalDensity.current) { (hPx * 0.26f).toSp() },
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
        )
    }
}

private fun tvClockText(): String {
    val now = java.util.Calendar.getInstance()
    fun two(v: Int) = if (v < 10) "0$v" else "$v"
    return "${two(now.get(java.util.Calendar.HOUR_OF_DAY))}:${two(now.get(java.util.Calendar.MINUTE))}:${two(now.get(java.util.Calendar.SECOND))}"
}

// ================================================================ 歌词辅助

private fun tvIsCjk(c: Char): Boolean =
    c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0x3040..0x30FF

private fun tvTokenizeLyric(text: String): List<String> {
    val out = ArrayList<String>(text.length)
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out += buf.toString()
            buf.clear()
        }
    }
    for (ch in text) {
        when {
            ch == ' ' -> { flush(); out += "\u00A0" }
            tvIsCjk(ch) -> { flush(); out += ch.toString() }
            else -> buf.append(ch)
        }
    }
    flush()
    return out
}

/** 与手机端 sweepFraction 一致：已唱字数 / 总字数，按逐字时间轴推进。 */
private fun tvSweepFraction(line: LyricLine, position: Long): Float {
    val total = line.text.length
    if (total <= 0) return 0f
    if (position <= line.startMs) return 0f
    if (line.words.isEmpty()) {
        return ((position - line.startMs).toFloat() / line.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    }
    var charsBefore = 0
    for (w in line.words) {
        val wEnd = w.startMs + w.durationMs
        if (position < w.startMs) return charsBefore.toFloat() / total
        if (position <= wEnd) {
            val f = if (w.durationMs > 0L) ((position - w.startMs).toFloat() / w.durationMs).coerceIn(0f, 1f) else 1f
            return (charsBefore + w.text.length * f) / total
        }
        charsBefore += w.text.length
    }
    return 1f
}

private fun tvTokenSweepBrush(
    line: LyricLine?,
    position: Long,
    tokens: List<String>,
    index: Int,
    sungColor: Color,
    dimColor: Color,
): Brush {
    if (line == null || line.words.isEmpty()) return SolidColor(sungColor)
    val total = tokens.sumOf { it.length }.coerceAtLeast(1)
    val p = tvSweepFraction(line, position)
    var before = 0
    for (i in 0 until index) before += tokens[i].length
    val len = tokens[index].length.coerceAtLeast(1)
    val a = before.toFloat() / total
    val b = (before + len).toFloat() / total
    val local = ((p - a) / (b - a)).coerceIn(0f, 1f)
    return when {
        local <= 0f -> SolidColor(dimColor)
        local >= 1f -> SolidColor(sungColor)
        else -> Brush.horizontalGradient(
            0f to sungColor,
            local to sungColor,
            (local + 0.35f).coerceAtMost(1f) to dimColor,
            1f to dimColor,
        )
    }
}

private fun tvWatermarkOf(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    val first = t.first()
    return if (first.code < 0x80) {
        t.takeWhile { it.code < 0x80 && !it.isWhitespace() }.ifEmpty { first.toString() }
    } else {
        first.toString()
    }
}

// ================================================================ 封面取色

private data class TvCoverPalette(
    val base: Color,
    val deep: Color,
    val glow: Color,
    val wave: Color,
    val waveIn: Color,
    val watermark: Color,
) {
    companion object {
        val Fallback = build(Color(0xFF4A54C8))
    }
}

private fun TvCoverPalette.Companion.build(dom: Color) = TvCoverPalette(
    base = tvLerp(dom, Color.Black, 0.58f),
    deep = tvLerp(dom, Color.Black, 0.80f),
    glow = tvLerp(dom, Color.Black, 0.34f),
    wave = tvLerp(dom, Color.White, 0.52f),
    waveIn = tvLerp(dom, Color.White, 0.92f),
    watermark = tvLerp(dom, Color.White, 0.55f).copy(alpha = 0.11f),
)

private fun tvLerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha,
)

private val tvPaletteCache = object : LinkedHashMap<String, TvCoverPalette>(24, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, TvCoverPalette>?) = size > 32
}

@Composable
private fun rememberTvCoverPalette(url: String?): TvCoverPalette {
    var palette by remember(url) { mutableStateOf<TvCoverPalette?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        tvPaletteCache[url]?.let { palette = it; return@LaunchedEffect }
        val bmp = tvLoadBitmap(url) ?: return@LaunchedEffect
        val p = withContext(Dispatchers.Default) { tvExtractPalette(bmp) }
        tvPaletteCache[url] = p
        palette = p
    }
    return palette ?: TvCoverPalette.Fallback
}

private fun tvExtractPalette(bmp: Bitmap): TvCoverPalette {
    val step = (minOf(bmp.width, bmp.height) / 48).coerceAtLeast(1)
    var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
    var y = 0
    while (y < bmp.height) {
        var x = 0
        while (x < bmp.width) {
            val c = bmp.getPixel(x, y)
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            val mx = maxOf(rr, gg, bb)
            val mn = minOf(rr, gg, bb)
            val sat = if (mx == 0) 0f else (mx - mn).toFloat() / mx
            val value = mx / 255f
            val w = (0.12f + sat * 1.5f * value).toDouble()
            r += rr * w; g += gg * w; b += bb * w; weight += w
            x += step
        }
        y += step
    }
    if (weight <= 0.0) return TvCoverPalette.Fallback
    val raw = Color(
        red = (r / weight / 255.0).toFloat(),
        green = (g / weight / 255.0).toFloat(),
        blue = (b / weight / 255.0).toFloat(),
    )
    val lifted = tvLift(raw) ?: return TvCoverPalette.Fallback
    return TvCoverPalette.build(lifted)
}

private fun tvLift(color: Color): Color? {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] < 0.18f || hsv[2] < 0.20f) return null
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceIn(0.50f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private suspend fun tvLoadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = Http.getBytes(url) ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

private val tvBitmapCache = object : LinkedHashMap<String, androidx.compose.ui.graphics.ImageBitmap>(24, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, androidx.compose.ui.graphics.ImageBitmap>?) = size > 32
}

@Composable
private fun rememberCoverBitmap(url: String?): androidx.compose.ui.graphics.ImageBitmap {
    // 无封面时的兜底：1x1 透明图。remember 必须无条件调用，不能放在 ?: 之后。
    val placeholder = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap() }
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        tvBitmapCache[url]?.let { bmp = it; return@LaunchedEffect }
        val loaded = tvLoadBitmap(url) ?: return@LaunchedEffect
        val ib = loaded.asImageBitmap()
        tvBitmapCache[url] = ib
        bmp = ib
    }
    return bmp ?: placeholder
}
