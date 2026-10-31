package com.dailymusic.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailymusic.app.data.Library
import com.dailymusic.app.data.Lyrics
import com.dailymusic.app.data.Song
import com.dailymusic.app.playback.PlayerManager
import top.yukonga.miuix.kmp.basic.Text

/**
 * 歌词动效（横屏卡片）。
 *
 * 参考 QQ 音乐的「歌词动效」：一屏只有一张 16:9 的卡片，左右各露出邻卡的一条边，
 * 底部一排圆点。卡片自己就是完整的播放面板 —— 左下角歌名/歌手，右下角四个控制键，
 * 这样在动效页里不用退出去也能切歌。
 *
 * 这里只做两个效果（WAVE / KTV），都是纯 Compose 绘制，没有视频素材：
 *
 * - **WAVE 动感波光**：封面重度模糊 + 青绿径向渐变打底，再叠五圈往外扩散的白色波纹，
 *   当前歌词居中，背后垫一份模糊的同款文字当「光晕」。
 * - **KTV 模式**：封面当画面（轻微模糊 + 压暗），歌名做成大字水印，歌词贴在底部逐字扫色。
 *
 * 逐字扫色复用竖屏歌词页的 [sweepBrush]，所以两个模式的「唱到哪」永远是同一个基准。
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
    val pager = rememberPagerState(pageCount = { LyricEffect.entries.size })
    val effect = LyricEffect.entries[pager.currentPage.coerceIn(0, LyricEffect.entries.size - 1)]

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050507))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 卡片宽度 = 屏宽 - 左右各 22dp 的露出量；高度按 16:9 算。
        // 用显式高度而不是 weight，卡片才能保持横屏比例而不是被拉成方形。
        val cardHeight = (maxWidth - 44.dp) * 9f / 16f

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(AppIcons.ChevronDown, Color.White.copy(alpha = 0.9f), 24.dp)
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "歌词动效 · ${effect.label}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(5.dp))
                    Gem()
                }
                Spacer(Modifier.size(38.dp))
            }

            Spacer(Modifier.weight(0.9f))

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().height(cardHeight),
                contentPadding = PaddingValues(horizontal = 22.dp),
                pageSpacing = 12.dp,
            ) { page ->
                EffectCard(
                    effect = LyricEffect.entries[page],
                    song = song,
                    lyrics = lyrics,
                    position = position,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LyricEffect.entries.forEachIndexed { index, _ ->
                    val active = pager.currentPage == index
                    val w by animateDpAsState(if (active) 16.dp else 5.dp, label = "dot-w")
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .height(5.dp)
                            .width(w)
                            .clip(CircleShape)
                            .background(if (active) Color.White else Color.White.copy(alpha = 0.26f)),
                    )
                }
            }

            // 这一页只有一个手势（左右滑），不写出来大部分人不会去试。
            Spacer(Modifier.height(10.dp))
            Text(
                text = "左右滑动切换动效",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.28f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))
        }
    }
}

/** 标题右边那颗绿色宝石，纯装饰（对齐 QQ 动效页的会员标识）。 */
@Composable
private fun Gem() {
    Box(
        Modifier
            .size(9.dp)
            .rotate(45f)
            .background(
                Brush.linearGradient(listOf(Color(0xFF7BF5A8), Color(0xFF19B25C))),
                RoundedCornerShape(2.dp),
            ),
    )
}

@Composable
private fun EffectCard(
    effect: LyricEffect,
    song: Song,
    lyrics: Lyrics,
    position: Long,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0B0B10)),
    ) {
        when (effect) {
            LyricEffect.WAVE -> {
                WaveBackdrop(song)
                WaveLyrics(
                    lyrics = lyrics,
                    song = song,
                    position = position,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 30.dp),
                )
            }

            LyricEffect.KTV -> {
                KtvBackdrop(song)
                KtvLyrics(
                    lyrics = lyrics,
                    song = song,
                    position = position,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 40.dp),
                )
            }
        }

        CardChrome(song, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------- WAVE

/**
 * 波光底：模糊封面 → 青绿径向渐变 → 五圈扩散的白色波纹。
 *
 * 波纹不是「一圈一圈从头画」：五个圆共用同一个 0..1 的相位、各自错开 1/5，
 * 所以看起来永远是连续不断地往外推，而不是一波停了再来一波。
 */
@Composable
private fun WaveBackdrop(song: Song) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(
            url = song.cover(480),
            modifier = Modifier.fillMaxSize().blur(26.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    0f to Color(0xFF5AE6EE).copy(alpha = 0.62f),
                    0.45f to Color(0xFF0E8DA6).copy(alpha = 0.78f),
                    1f to Color(0xFF032A35).copy(alpha = 0.92f),
                ),
            ),
        )

        val transition = rememberInfiniteTransition(label = "wave")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5_200, easing = LinearEasing)),
            label = "wave-phase",
        )

        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.maxDimension * 0.62f
            // 中心一团会呼吸的光，让波纹有「从水里透上来」的感觉。
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = c,
                    radius = maxR * 0.55f,
                ),
                radius = maxR * 0.55f,
                center = c,
            )
            repeat(5) { i ->
                val p = (phase + i / 5f) % 1f
                val r = maxR * (0.10f + 0.90f * p)
                // 越往外越淡、越细：波纹扩散开之后本来就该散掉。
                drawCircle(
                    color = Color.White.copy(alpha = (1f - p) * 0.30f),
                    radius = r,
                    center = c,
                    style = Stroke(width = (1.2f + 3.4f * (1f - p)).dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun WaveLyrics(
    lyrics: Lyrics,
    song: Song,
    position: Long,
    modifier: Modifier = Modifier,
) {
    val line = currentLine(lyrics, position)
    val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name
    val size = when {
        text.length <= 8 -> 25.sp
        text.length <= 14 -> 20.sp
        else -> 17.sp
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        // 光晕：同一句模糊一份垫在后面。QQ 的波光歌词是「发光」的，不是单纯加粗。
        Text(
            text = text,
            fontSize = size,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = size * 1.32f,
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.blur(13.dp),
        )
        BasicText(
            text = text,
            style = TextStyle(
                brush = line?.let { sweepBrush(it, position) } ?: SolidColor(Color.White),
                fontSize = size,
                lineHeight = size * 1.32f,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

// ---------------------------------------------------------------- KTV

/**
 * KTV 底：封面当画面，轻微模糊 + 压暗，再压一行半透明的歌名水印。
 *
 * 不把封面彻底模糊掉 —— KTV 模式的味道就在「看得见画面」，
 * 全靠一层 45% 的黑罩保证白字读得出来。
 */
@Composable
private fun KtvBackdrop(song: Song) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(
            url = song.cover(480),
            modifier = Modifier.fillMaxSize().blur(2.dp),
            shape = RoundedCornerShape(0.dp),
            showFallbackIcon = false,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.42f),
                    0.45f to Color.Black.copy(alpha = 0.26f),
                    1f to Color.Black.copy(alpha = 0.62f),
                ),
            ),
        )
        Text(
            text = song.name,
            // 水印往上抬 18%：它压在卡片正中时会和底部那行歌词挤在一起。
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-18).dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = Color.White.copy(alpha = 0.20f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun KtvLyrics(
    lyrics: Lyrics,
    song: Song,
    position: Long,
    modifier: Modifier = Modifier,
) {
    val line = currentLine(lyrics, position)
    val text = line?.text?.takeIf { it.isNotBlank() } ?: song.name
    val size = when {
        text.length <= 10 -> 18.sp
        text.length <= 18 -> 16.sp
        else -> 14.sp
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = size,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = size * 1.3f,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.blur(9.dp),
            )
            BasicText(
                text = text,
                style = TextStyle(
                    brush = line?.let { sweepBrush(it, position) } ?: SolidColor(Color.White),
                    fontSize = size,
                    lineHeight = size * 1.3f,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
        line?.translation?.takeIf { it.isNotBlank() }?.let { tr ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr,
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------- 卡片内的迷你控制条

@Composable
private fun CardChrome(song: Song, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 底部压一层渐变，白色小字压在浅色封面上才读得清。
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                ),
            )
            .padding(start = 9.dp, end = 6.dp, top = 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(
            url = song.cover(120),
            modifier = Modifier.size(26.dp),
            shape = RoundedCornerShape(7.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.name,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.singer,
                fontSize = 8.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        EffectControl(AppIcons.SkipPrevious, 15.dp) { PlayerManager.previous() }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable { PlayerManager.toggle(context) },
            contentAlignment = Alignment.Center,
        ) {
            TintedIcon(
                icon = if (PlayerManager.isPlaying) AppIcons.Pause else AppIcons.Play,
                tint = Color.White,
                size = 14.dp,
            )
        }
        EffectControl(AppIcons.SkipNext, 15.dp) { PlayerManager.next() }
        EffectControl(
            icon = if (Library.isFavorite(song.key)) AppIcons.Heart else AppIcons.HeartOutline,
            size = 13.dp,
        ) { Library.toggleFavorite(song) }
    }
}

@Composable
private fun EffectControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        // 触摸区固定 26dp 并裁圆：卡片里控件很小，方块涟漪会糊成一片。
        modifier = Modifier.size(26.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(icon, Color.White.copy(alpha = 0.9f), size)
    }
}

/** 当前该高亮的那一行；间奏时 `anchorIndex` 会停在上一句，正好符合「上一句一直亮」。 */
private fun currentLine(lyrics: Lyrics, position: Long) =
    lyrics.lines.getOrNull(lyrics.anchorIndex(position).coerceAtLeast(0))
