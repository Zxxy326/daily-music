package com.dailymusic.app.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.dailymusic.app.data.LyricLine
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import com.dailymusic.app.playback.SpectrumTap
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.abs
import androidx.compose.ui.graphics.graphicsLayer

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
 * WAVE 动感波光。
 *
 * 视觉稿在 `design/wave-preview.html`，那边的数值就是这里的数值 —— 调参数时两边一起改。
 *
 * 四件事决定了它长什么样：
 *
 * 1. **配色全部来自封面**（[rememberCoverPalette]）。不是给封面盖一层蓝罩，
 *    而是取主色再压暗推导出底色 / 辉光 / 光带 / 水印四档，所以换一首歌整个画面跟着换。
 * 2. **两个波源在屏幕外左右各一个、与歌词同高**。同心圆会变成雷达扫描，
 *    左右各发各的才会在中间「撞」出层次。波带用径向渐变画（不是描边），
 *    截面中间实、两边虚。
 * 3. **深浅是「旅程中段最亮」**：刚离开波源时淡入、快走到歌词时淡出，
 *    所以中间那条横线附近最亮，两头都暗 —— 也正是「中间留空」的由来。
 * 4. **荡的频率由频谱驱动**（[SpectrumTap]）：低频一响，相位推进立刻加速、亮度抬高、
 *    环数多推一条；中频决定波带宽度；高频决定环与环的明暗差。
 *    安静段落约 7.7 秒荡完一圈，鼓点重的地方能压到 2.5 秒。
 */
@Composable
private fun WavePage(song: Song, lyrics: Lyrics, position: Long) {
    val palette = rememberCoverPalette(song.cover(640))

    // 相位在帧回调里积分，不用 infiniteRepeatable：固定周期做不出「跟着鼓点走」。
    var phase by remember { mutableFloatStateOf(0f) }
    var bass by remember { mutableFloatStateOf(0f) }
    var mid by remember { mutableFloatStateOf(0f) }
    var high by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                bass = SpectrumTap.bass
                mid = SpectrumTap.mid
                high = SpectrumTap.high
                val speed = 0.130f * (0.55f + 1.90f * bass + 0.45f * mid)
                phase = (phase + dt * speed) % 1f
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
                    0.52f to palette.base,
                    1f to palette.deep,
                ),
            ),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                // 竖向压暗要用 DstIn 混合，必须先在离屏层里画。
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            drawWaveField(phase, bass, mid, high, palette)
        }

        // 中间压暗垫：波纹本来就淡出，再垫一层保证歌词区绝对干净。
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.90f)
                .background(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = 0.44f),
                        0.46f to Color.Black.copy(alpha = 0.28f),
                        0.74f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )

        val anchor = lyrics.anchorIndex(position).coerceAtLeast(0)
        val line = lyrics.lines.getOrNull(anchor)
        val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name

        WaveWatermark(text, wPx, hPx, palette)
        WaveLyrics(line, position, text, palette, wPx, hPx)
    }
}

/** 背景水印：这一句的**首字**（英文取首个单词），放大到近屏高、压到几乎看不见。 */
@Composable
private fun BoxScope.WaveWatermark(
    text: String,
    wPx: Float,
    hPx: Float,
    palette: CoverPalette,
) {
    val mark = remember(text) { watermarkOf(text) }
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
        modifier = Modifier
            .align(Alignment.Center)
            .blur(14.dp),
    )
}

private fun isCjk(c: Char): Boolean =
    c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0x3040..0x30FF

private fun watermarkOf(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return ""
    val first = t.first()
    // 英文取首个单词，中文取首字 —— 一个字母当水印没有辨识度
    return if (first.code < 0x80) {
        t.takeWhile { it.code < 0x80 && !it.isWhitespace() }.ifEmpty { first.toString() }
    } else {
        first.toString()
    }
}

/**
 * 歌词：**逐字**入场，左半句从左边推、右半句从右边推，越靠外的字出发越晚，最后在正中汇合。
 *
 * 整句不插空格（KRC 的 words 本来就切好了，硬加空格会变成「我 真的 不能」那种断句），
 * 太长就靠 [FlowRow] 自动换行 —— 字号先按字数收缩，缩到下限还放不下才换行。
 */
@Composable
private fun BoxScope.WaveLyrics(
    line: LyricLine?,
    position: Long,
    fallback: String,
    palette: CoverPalette,
    wPx: Float,
    hPx: Float,
) {
    val text = line?.text?.takeIf { it.isNotBlank() } ?: fallback
    val tokens = remember(text) { tokenizeLyric(text) }
    val chars = remember(text) { text.count { !it.isWhitespace() }.coerceAtLeast(1) }

    val density = LocalDensity.current
    val fontSize = remember(chars, wPx, hPx) {
        val px = minOf(hPx * 0.165f, maxOf(hPx * 0.052f, wPx * 0.66f / (chars * 1.03f)))
        with(density) { px.toSp() }
    }

    // 一句一个进度条：换句时从头走一遍，逐字靠 delay 错开。
    val enter = remember { Animatable(1f) }
    LaunchedEffect(text) {
        enter.snapTo(0f)
        enter.animateTo(1f, tween(920, easing = LinearEasing))
    }

    val sung = remember(line, position, tokens) { sungTokens(line, position, tokens) }

    FlowRow(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.68f),
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
                    color = if (sung[index]) Color.White else Color.White.copy(alpha = 0.46f),
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

/**
 * 逐字切分：汉字/假名一字一块，连续的英文数字合成一块，空格换成不断行空格。
 *
 * 用「一块一个 Text」而不是整句一个 Text，是为了让每个字能独立位移 ——
 * 整句只能整体平移，做不出「从两侧往中间靠拢」。
 */
private fun tokenizeLyric(text: String): List<String> {
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
            ch == ' ' -> {
                flush()
                out += "\u00A0"
            }
            isCjk(ch) -> {
                flush()
                out += ch.toString()
            }
            else -> buf.append(ch)
        }
    }
    flush()
    return out
}

/** 哪些块已经唱过了 —— 按词的时间轴，而不是按整句均分。 */
private fun sungTokens(line: LyricLine?, position: Long, tokens: List<String>): BooleanArray {
    val flags = BooleanArray(tokens.size)
    if (line == null || line.words.isEmpty()) {
        flags.fill(true)
        return flags
    }
    val starts = IntArray(tokens.size)
    var acc = 0
    for (i in tokens.indices) {
        starts[i] = acc
        acc += tokens[i].length
    }
    var offset = 0
    for (w in line.words) {
        val from = offset
        val to = offset + w.text.length
        offset = to
        if (position >= w.startMs + w.durationMs * 0.65f) {
            for (i in tokens.indices) {
                if (starts[i] in from until to) flags[i] = true
            }
        }
    }
    return flags
}

/**
 * 画波纹本体。参数与 `design/wave-preview.html` 的 `drawWaves` 一一对应。
 *
 * @param phase 0..1 的相位；环的半径 = `maxR * ((phase + i/rings) % 1)`
 */
private fun DrawScope.drawWaveField(
    phase: Float,
    bass: Float,
    mid: Float,
    high: Float,
    palette: CoverPalette,
) {
    val w = size.width
    val h = size.height

    val band = w * 0.075f * (1f + 0.28f * mid)      // 中频 → 波带宽度
    val maxR = w * 0.40f                            // 单侧最远只荡到 40% 屏宽
    val rings = 4 + if (bass > 0.72f) 1 else 0      // 鼓点重时多推一条
    val loud = 0.60f + 0.48f * bass                 // 低频 → 整体亮度

    // 两个波源都在屏幕外，且与歌词同高
    val sources = listOf(Offset(-w * 0.02f, h * 0.5f), Offset(w * 1.02f, h * 0.5f))

    for (center in sources) {
        for (i in 0 until rings) {
            val p = (phase + i / rings.toFloat()) % 1f
            val r = maxR * p

            // 深浅：旅程中段最亮，靠近波源淡入、靠近歌词淡出
            val fin = (p / 0.22f).coerceAtMost(1f)
            val fout = 1f - ((p - 0.58f) / 0.42f).coerceIn(0f, 1f)
            val a = 0.78f * loud * fin * fin * fout * fout
            if (a <= 0.004f) continue

            val ringVar = (if (i % 2 == 0) 1f else 0.84f) * (0.92f + 0.10f * high)

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

    // 上下两端淡一档：最亮的带停在中间那条横线上。
    // DstIn 按 source 的 alpha 保留 destination，所以这里用白色 + 不同 alpha 当遮罩。
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
