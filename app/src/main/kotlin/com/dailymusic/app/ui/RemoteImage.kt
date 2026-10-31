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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
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
        imageCache.get(url)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val bytes = Http.getBytes(url) ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        } ?: return@LaunchedEffect
        val image = decoded.asImageBitmap()
        imageCache.put(url, image)
        bitmap = image
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
