package com.dailymusic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur

/**
 * 液态玻璃表面。
 *
 * 用 Miuix 的 `miuix-blur`（AGSL runtime shader）而不是 `background(半透明白)` 那种假玻璃：
 *
 * - `textureBlur` 对「背后已绘制的内容」做真实高斯模糊，滚动时能看见内容在玻璃下流动
 * - `colors` 提饱和 + 微提亮，这是液态玻璃观感的关键；只模糊不提饱和会发灰
 * - `highlight` 边缘高光环（Miuix 的 BloomStroke 预设），给玻璃一层「厚度」
 *
 * 这里刻意用高层的 `textureBlur` 而不是手写 `drawBackdrop { blur(); colorControls() }`：
 * 后者要自己算 `padding`（采样外扩）和 layer 偏移，很容易出现「只糊到一小块、
 * 其余是色块」的错位。textureBlur 内部把 padding / 偏移 / 降采样都处理好了。
 *
 * 染色不在这里做：调用方在 `liquidGlass(...)` 之后接 `background(tint, shape)`，
 * 让 background 用自己的 shape 裁切，圆角处不会露出直角。
 *
 * 注意：miuix-blur 依赖 runtime shader，仅 API 33+ 可用 —— 这也是本应用 minSdk = 33 的原因。
 * 调用方必须把 `Modifier.layerBackdrop(backdrop)` 放在内容容器上，否则拿不到背景。
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: LayerBackdrop,
    shape: Shape,
    blurRadius: Dp = 18.dp,
    saturation: Float = 1.65f,
    brightness: Float = 0.02f,
): Modifier {
    val dark = isSystemInDarkTheme()
    val highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
    val colors = BlurDefaults.blurColors(
        saturation = saturation,
        brightness = brightness,
        contrast = 1f,
    )
    return this.textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = blurRadius.value,
        colors = colors,
        highlight = highlight,
    )
}
