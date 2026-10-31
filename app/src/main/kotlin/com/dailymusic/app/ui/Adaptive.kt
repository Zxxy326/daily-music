package com.dailymusic.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 宽度断点。跟 Material 3 的 window size class 对齐：
 * Compact 手机竖屏 / Medium 手机横屏·小折叠展开 / Expanded 平板。
 *
 * 只按宽度判断，不看高度 —— 手机横屏也会走 Medium（侧边导航），
 * 这正是横屏下底栏最尴尬的场景。
 */
enum class WindowClass { Compact, Medium, Expanded }

@Composable
@ReadOnlyComposable
fun windowClass(): WindowClass {
    val width = LocalConfiguration.current.screenWidthDp
    return when {
        width >= 840 -> WindowClass.Expanded
        width >= 600 -> WindowClass.Medium
        else -> WindowClass.Compact
    }
}
