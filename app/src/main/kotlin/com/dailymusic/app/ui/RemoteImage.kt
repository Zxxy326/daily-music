package com.dailymusic.app.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dailymusic.app.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 极简网络图片。
 *
 * 没用 Coil：本机 Gradle 缓存里没有它，而为了一个 `AsyncImage` 引入一整套图片管线
 * 在这个规模下不划算。这里只有「下载 → 解码 → LruCache → 显示」四步，60 行搞定，
 * 封面图也就 240/480 两种尺寸，够用。
 */
private val imageCache = object : LruCache<String, ImageBitmap>(64) {}

/** 封面 → 配色。一张图只算一次，跟着 url 缓存。 */
private val paletteCache = object : LruCache<String, CoverPalette>(32) {}

private suspend fun loadImage(url: String): ImageBitmap? {
    imageCache.get(url)?.let { return it }
    val bytes = Http.getBytes(url) ?: return null
    val decoded = withContext(Dispatchers.Default) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    } ?: return null
    return decoded.asImageBitmap().also { imageCache.put(url, it) }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    contentScale: ContentScale = ContentScale.Crop,
    showFallbackIcon: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        bitmap = loadImage(url)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh),
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
        } else if (showFallbackIcon) {
            Image(
                painter = rememberVectorPainter(AppIcons.MusicNote),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.38f),
                colorFilter = ColorFilter.tint(colors.onSurfaceVariantSummary),
            )
        }
    }
}

// ---------------------------------------------------------------- 封面取色

/**
 * 一套由封面主色推导出来的配色。
 *
 * 推导规则和 `design/wave-preview.html` 里那套完全一致，改一边记得改另一边：
 * 底色压到主色的 76% 黑、辉光 54%、光带提亮到白 52%、水印白 55% 且只有 11% 不透明度。
 * 直接拿主色当背景会很扎眼，压暗之后才像「这张封面的氛围」，而不是「这张封面的颜色」。
 */
data class CoverPalette(
    val dom: Color,
    val base: Color,
    val deep: Color,
    val glow: Color,
    val wave: Color,
    val waveIn: Color,
    val watermark: Color,
) {
    companion object {
        /** 没有封面、还没解码出来、取色失败 —— 都退回这套靛蓝。 */
        val Fallback = build(Color(0xFF4A54C8))
    }
}

private fun CoverPalette.Companion.build(dom: Color): CoverPalette = CoverPalette(
    dom = dom,
    // 0.76 那一版在实机上看是「一片死黑」：手机屏本来就比浏览器暗，
    // 封面主色也往往比预览里的合成色更暗。压到 0.58 / 0.80 才真的看得出颜色。
    base = lerp(dom, Color.Black, 0.58f),
    deep = lerp(dom, Color.Black, 0.80f),
    glow = lerp(dom, Color.Black, 0.34f),
    wave = lerp(dom, Color.White, 0.52f),
    waveIn = lerp(dom, Color.White, 0.92f),
    watermark = lerp(dom, Color.White, 0.55f).copy(alpha = 0.11f),
)

/**
 * 取封面主色。
 *
 * 不能用「全图平均」：专辑图经常大半是黑边或纯色块，平均下来是一坨灰。
 * 这里按 `饱和度 × 明度` 加权 —— 越鲜艳、越亮的像素话语权越大，
 * 得到的是「这张图看起来是什么颜色」，而不是「这张图算术上是什么颜色」。
 */
private fun extractPalette(image: ImageBitmap): CoverPalette {
    val bmp = image.asAndroidBitmap()
    val step = (minOf(bmp.width, bmp.height) / 48).coerceAtLeast(1)

    var r = 0.0
    var g = 0.0
    var b = 0.0
    var weightSum = 0.0

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
            r += rr * w
            g += gg * w
            b += bb * w
            weightSum += w
            x += step
        }
        y += step
    }

    if (weightSum <= 0.0) return CoverPalette.Fallback
    val raw = Color(
        red = (r / weightSum / 255.0).toFloat(),
        green = (g / weightSum / 255.0).toFloat(),
        blue = (b / weightSum / 255.0).toFloat(),
    )
    return CoverPalette.build(lift(raw) ?: return CoverPalette.Fallback)
}

/**
 * 把主色提到「能当背景用」的亮度 / 饱和度。
 *
 * 暗封面的主色可能只有 10% 明度，直接拿去推导就是一团黑 —— 用户看到的是
 * 「背景全黑，只有波纹在动」，而不是「这张封面的氛围」。
 * 这里只调 HSV 的 S/V，不动色相，所以颜色还是封面的那个颜色。
 */
private fun lift(color: Color): Color? {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    // 近乎黑白灰的封面没有可用色相：硬把明度提上去，HSV 里的 hue 是 0，
    // 结果是一块「凭空的暗红」。这种封面直接退回默认靛蓝，比瞎猜好看。
    if (hsv[1] < 0.18f || hsv[2] < 0.20f) return null
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceIn(0.50f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
fun rememberCoverPalette(url: String?): CoverPalette {
    var palette by remember(url) { mutableStateOf<CoverPalette?>(null) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        paletteCache.get(url)?.let {
            palette = it
            return@LaunchedEffect
        }
        val image = loadImage(url) ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) { extractPalette(image) }
        paletteCache.put(url, extracted)
        palette = extracted
    }

    return palette ?: CoverPalette.Fallback
}
